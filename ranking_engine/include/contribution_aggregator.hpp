#pragma once

#include "types.hpp"
#include "technology_registry.hpp"
#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include <algorithm>

namespace issue_pilot {

struct ContributionPr {
    std::string pr_id;
    std::string repository_key;
    std::string technology;
    bool is_merged{false};
    bool is_closed{false};
    bool is_bot{false};
    bool is_fork{false};
    double days_since_activity{0.0}; // from snapshot time
};

struct ContributionCommit {
    std::string commit_sha;
    std::string repository_key;
    std::string technology;
    std::string linked_pr_id;
    bool is_bot{false};
    bool is_fork{false};
    double days_since_commit{0.0}; // from snapshot time
};

struct QuestionnaireReport {
    std::string technology;
    std::string level; // beginner, basic, intermediate, advanced, expert
    double explicit_score{0.0};
};

class ContributionEpisodeAggregator {
public:
    static std::unordered_map<std::string, ContributorSkillEvidenceV2> aggregate(
        const std::vector<ContributionPr>& prs,
        const std::vector<ContributionCommit>& commits,
        const std::vector<QuestionnaireReport>& questionnaires,
        double profile_coverage = 1.0,
        double source_quality = 1.0,
        bool profile_coverage_known = true,
        bool source_quality_known = true
    ) {
        // 1. Deduplicate PRs across API pages
        std::unordered_map<std::string, ContributionPr> deduplicated_prs;
        std::unordered_set<std::string> known_pr_ids;

        for (const auto& pr : prs) {
            if (pr.is_bot || pr.is_fork || pr.pr_id.empty()) continue;
            std::string repo = to_lower_str(trim_str(pr.repository_key));
            std::string key = repo + "|" + trim_str(pr.pr_id);
            if (deduplicated_prs.find(key) == deduplicated_prs.end()) {
                deduplicated_prs[key] = pr;
                known_pr_ids.insert(trim_str(pr.pr_id));
                known_pr_ids.insert(key);
            }
        }

        // 2. Deduplicate commits and identify standalone commits
        std::unordered_map<std::string, ContributionCommit> deduplicated_commits;
        for (const auto& c : commits) {
            if (c.is_bot || c.is_fork || c.commit_sha.empty()) continue;
            std::string repo = to_lower_str(trim_str(c.repository_key));
            std::string key = repo + "|" + trim_str(c.commit_sha);
            if (deduplicated_commits.find(key) == deduplicated_commits.end()) {
                deduplicated_commits[key] = c;
            }
        }

        std::vector<ContributionCommit> standalone_commits;
        for (const auto& kv : deduplicated_commits) {
            const auto& c = kv.second;
            bool is_linked = false;
            if (!c.linked_pr_id.empty()) {
                std::string lid = trim_str(c.linked_pr_id);
                std::string repo = to_lower_str(trim_str(c.repository_key));
                if (known_pr_ids.count(lid) || known_pr_ids.count(repo + "|" + lid)) {
                    is_linked = true;
                } else {
                    is_linked = true;
                }
            }
            if (!is_linked) {
                standalone_commits.push_back(c);
            }
        }

        // 3. Group by canonical technology
        std::unordered_set<std::string> all_technologies;
        std::unordered_map<std::string, std::vector<ContributionPr>> tech_prs;
        std::unordered_map<std::string, std::vector<ContributionCommit>> tech_commits;
        std::unordered_map<std::string, std::unordered_set<std::string>> tech_repos;
        std::unordered_map<std::string, double> tech_min_days_activity;

        for (const auto& kv : deduplicated_prs) {
            const auto& pr = kv.second;
            std::string canon = TechnologyRegistry::resolve_canonical_name(pr.technology);
            if (canon.empty()) canon = pr.technology;
            if (canon.empty()) continue;

            all_technologies.insert(canon);
            tech_prs[canon].push_back(pr);

            if (!pr.repository_key.empty()) {
                tech_repos[canon].insert(to_lower_str(trim_str(pr.repository_key)));
            }

            double days = pr.days_since_activity >= 0.0 ? pr.days_since_activity : 0.0;
            if (tech_min_days_activity.find(canon) == tech_min_days_activity.end() || days < tech_min_days_activity[canon]) {
                tech_min_days_activity[canon] = days;
            }
        }

        for (const auto& c : standalone_commits) {
            std::string canon = TechnologyRegistry::resolve_canonical_name(c.technology);
            if (canon.empty()) canon = c.technology;
            if (canon.empty()) continue;

            all_technologies.insert(canon);
            tech_commits[canon].push_back(c);

            if (!c.repository_key.empty()) {
                tech_repos[canon].insert(to_lower_str(trim_str(c.repository_key)));
            }

            double days = c.days_since_commit >= 0.0 ? c.days_since_commit : 0.0;
            if (tech_min_days_activity.find(canon) == tech_min_days_activity.end() || days < tech_min_days_activity[canon]) {
                tech_min_days_activity[canon] = days;
            }
        }

        // Questionnaire entries
        std::unordered_map<std::string, QuestionnaireReport> q_map;
        for (const auto& q : questionnaires) {
            if (q.technology.empty()) continue;
            std::string canon = TechnologyRegistry::resolve_canonical_name(q.technology);
            if (canon.empty()) canon = q.technology;
            all_technologies.insert(canon);
            q_map[canon] = q;
        }

        // 4. Build ContributorSkillEvidenceV2
        std::unordered_map<std::string, ContributorSkillEvidenceV2> result;

        for (const auto& tech : all_technologies) {
            ContributorSkillEvidenceV2 ev;
            ev.technology = tech;

            const auto& pr_list = tech_prs[tech];
            const auto& commit_list = tech_commits[tech];
            const auto& repos = tech_repos[tech];

            // Repos
            ev.unique_contributed_repositories = static_cast<int>(repos.size());
            ev.repo_available = true;

            // Standalone Commits
            ev.standalone_commits = static_cast<int>(commit_list.size());
            ev.standalone_commits_available = true;

            // PR volume
            ev.unique_authored_prs = static_cast<int>(pr_list.size());
            ev.pr_volume_available = true;

            // Merge rate
            int decided = 0;
            int merged = 0;
            for (const auto& pr : pr_list) {
                if (pr.is_merged || pr.is_closed) {
                    decided++;
                    if (pr.is_merged) merged++;
                }
            }
            ev.decided_prs = decided;
            ev.merged_decided_prs = merged;
            ev.merge_rate_available = (decided >= 5);

            // Recency
            if (tech_min_days_activity.find(tech) != tech_min_days_activity.end()) {
                double months = tech_min_days_activity[tech] / 30.4375;
                ev.months_since_last_activity = months;
                ev.recency_available = true;
            } else {
                ev.recency_available = false;
            }

            ev.profile_coverage = profile_coverage;
            ev.source_quality = source_quality;
            ev.profile_coverage_known = profile_coverage_known;
            ev.source_quality_known = source_quality_known;

            // Questionnaire
            if (q_map.find(tech) != q_map.end()) {
                const auto& q = q_map[tech];
                ev.has_questionnaire = true;
                ev.questionnaire_level = q.level;
                if (q.explicit_score > 0.0) {
                    ev.questionnaire_score = q.explicit_score;
                } else if (!q.level.empty()) {
                    ev.questionnaire_score = ContributorSkillEvidenceV2::questionnaire_level_to_score(q.level);
                }
            }

            result[tech] = ev;
        }

        return result;
    }
};

} // namespace issue_pilot

