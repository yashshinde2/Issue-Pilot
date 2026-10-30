#pragma once

#include <string>
#include <vector>
#include <unordered_map>
#include <sstream>
#include <algorithm>
#include <cmath>
#include "unicode_nfc.hpp"

namespace issue_pilot {

namespace versions {
static constexpr const char* ALGORITHM_VERSION = "2.3.0";
static constexpr const char* FEATURE_SCHEMA_VERSION = "2.3.0";
static constexpr const char* TECHNOLOGY_REGISTRY_VERSION = "1.0.0";
static constexpr const char* LABEL_TAXONOMY_VERSION = "1.0.0";
} // namespace versions

// Trims leading and trailing whitespace
inline std::string trim_str(const std::string& str) {
    size_t first = str.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    size_t last = str.find_last_not_of(" \t\r\n");
    return str.substr(first, (last - first + 1));
}

inline std::string to_lower_str(std::string str) {
    std::transform(str.begin(), str.end(), str.begin(), [](unsigned char c){ return std::tolower(c); });
    return str;
}

// Canonical Four-Part Issue Key Helper Functions
inline std::string build_issue_key(const std::string& source,
                                   const std::string& source_instance,
                                   const std::string& canonical_repository_key,
                                   const std::string& native_issue_id) {
    std::string s = to_lower_str(trim_str(source));
    std::string inst = unicode::to_nfc(trim_str(source_instance));
    std::string repo = unicode::to_nfc(trim_str(canonical_repository_key));
    std::string nat = unicode::to_nfc(trim_str(native_issue_id));
    if (s.empty() || inst.empty() || repo.empty() || nat.empty()) {
        throw std::invalid_argument("Issue key segments must not be empty");
    }
    if (s.find('|') != std::string::npos || inst.find('|') != std::string::npos ||
        repo.find('|') != std::string::npos || nat.find('|') != std::string::npos) {
        throw std::invalid_argument("Issue key segments must not contain '|'");
    }
    return s + "|" + inst + "|" + repo + "|" + nat;
}

inline bool parse_issue_key(const std::string& key,
                            std::string& out_source,
                            std::string& out_source_instance,
                            std::string& out_canonical_repo,
                            std::string& out_native_id) {
    std::string trimmed_key = trim_str(key);
    if (trimmed_key.empty()) return false;
    std::vector<std::string> parts;
    std::string token;
    std::istringstream tokenStream(trimmed_key);
    while (std::getline(tokenStream, token, '|')) {
        parts.push_back(token);
    }
    if (!trimmed_key.empty() && trimmed_key.back() == '|') {
        parts.push_back("");
    }
    if (parts.size() != 4) return false;
    for (size_t i = 0; i < parts.size(); ++i) {
        parts[i] = unicode::to_nfc(trim_str(parts[i]));
        if (parts[i].empty()) return false;
    }
    out_source = to_lower_str(parts[0]);
    out_source_instance = parts[1];
    out_canonical_repo = parts[2];
    out_native_id = parts[3];
    return true;
}

inline double validate_confidence(double conf) {
    if (std::isnan(conf) || std::isinf(conf) || conf < 0.0 || conf > 1.0) {
        throw std::invalid_argument("Confidence must be a finite number in [0.0, 1.0], got: " + std::to_string(conf));
    }
    return conf;
}

// Generic Typed Evidence Envelope (Algorithm V2.3 Section 2)
template <typename T>
struct Evidence {
    T value{};
    bool available{false};
    double confidence{0.0}; // [0, 1]
    std::string source;
    std::string source_instance;
    std::string method;
    int sample_size{0};
    double coverage{0.0};
    std::string observed_at;
    std::string window_start;
    std::string window_end;
    bool pagination_complete{true};
    std::string approximation_reason;
    std::vector<std::string> evidence_refs;
    std::string schema_version{"2.3.0"};

    static Evidence<T> of(const T& val, double conf, const std::string& src, const std::string& inst, const std::string& mth) {
        Evidence<T> e;
        e.value = val;
        e.available = true;
        e.confidence = validate_confidence(conf);
        e.source = src;
        e.source_instance = inst;
        e.method = mth;
        e.pagination_complete = true;
        return e;
    }

    static Evidence<T> known_zero(double conf, const std::string& src, const std::string& inst, const std::string& mth, const T& zero_val) {
        Evidence<T> e;
        e.value = zero_val;
        e.available = true;
        e.confidence = validate_confidence(conf);
        e.source = src;
        e.source_instance = inst;
        e.method = mth;
        e.pagination_complete = true;
        return e;
    }

    static Evidence<T> unavailable(const std::string& reason, const std::string& src, const std::string& inst) {
        Evidence<T> e;
        e.available = false;
        e.confidence = 0.0;
        e.source = src;
        e.source_instance = inst;
        e.approximation_reason = reason;
        e.pagination_complete = false;
        return e;
    }
};

// V2.3 Difficulty Evidence: 4 signals with weights SC (30%), TC (30%), DI (20%), CC (20%)
struct DifficultyEvidenceV2 {
    double scope_change_size{50.0};      // SC
    double technical_complexity{50.0};    // TC
    double dependency_arch_impact{50.0};  // DI
    double coordination_complexity{50.0}; // CC
};

// V2.3 Responsiveness Evidence: RT (30%), RR (30%), PR (25%), MA (15%)
struct ResponsivenessEvidenceV2 {
    double response_time_score{80.0};
    double response_rate_score{80.0};
    double pr_review_score{80.0};
    double recent_maintainer_act{80.0};
};

// V2.3 Repository Health Evidence: C90 (30%), PR30 (25%), AU90 (25%), MA90 (20%)
struct RepositoryHealthEvidenceV2 {
    double c90_score{80.0};
    double pr30_score{80.0};
    double au90_score{80.0};
    double ma90_score{80.0};
    bool c90_available{true};
    bool pr30_available{true};
    bool au90_available{true};
    bool ma90_available{true};

    double compute_health() const {
        double sum_w = 0.0;
        double w_val = 0.0;
        if (c90_available) { w_val += 0.30 * c90_score; sum_w += 0.30; }
        if (pr30_available) { w_val += 0.25 * pr30_score; sum_w += 0.25; }
        if (au90_available) { w_val += 0.25 * au90_score; sum_w += 0.25; }
        if (ma90_available) { w_val += 0.20 * ma90_score; sum_w += 0.20; }
        if (sum_w <= 0.0) return 0.0;
        return w_val / sum_w;
    }
};

// V2.3 Freshness Evidence: AgeScore (40%), ActivityScore (60%)
struct FreshnessEvidenceV2 {
    double age_score{80.0};
    double activity_score{80.0};
    double age_days{0.0};
    double idle_days{0.0};
};

// Technology Relevance for an Issue
struct TechnologyRelevance {
    std::string technology;
    double raw_relevance{0.0};
    double normalized_relevance{0.0};
};

// Contributor Skill Entry
struct ContributorSkill {
    std::string technology;
    double score{0.0};      // 0 - 100
    double confidence{1.0}; // 0 - 1
    std::string level;
    std::string source{"observed"};
};

// V2.3 Contributor Skill Evidence per Technology conforming strictly to Sections 5.2 and 5.3
struct ContributorSkillEvidenceV2 {
    std::string technology;

    int unique_contributed_repositories{0};
    bool repo_available{false};

    int standalone_commits{0}; // human commits not linked to PR
    bool standalone_commits_available{false};

    int unique_authored_prs{0};
    bool pr_volume_available{false};

    int merged_decided_prs{0};
    int decided_prs{0};
    bool merge_rate_available{false};

    double months_since_last_activity{0.0};
    bool recency_available{false};

    double profile_coverage{1.0};
    double source_quality{1.0};
    bool profile_coverage_known{true};
    bool source_quality_known{true};

    bool has_questionnaire{false};
    std::string questionnaire_level;
    double questionnaire_score{0.0};

    static double sat(double x, double k) {
        if (x <= 0.0) return 0.0;
        return 100.0 * (1.0 - std::exp(-x / k));
    }

    static double recency_decay(double months) {
        if (months < 0.0) months = 0.0;
        return 100.0 * std::exp(-months / 12.0);
    }

    static double recency_half_life(double months) {
        if (months < 0.0) months = 0.0;
        return 100.0 * std::pow(0.5, months / 12.0);
    }

    static double merge_rate(int merged, int decided) {
        return 100.0 * (static_cast<double>(merged) + 2.0) / (static_cast<double>(decided) + 4.0);
    }

    static double questionnaire_level_to_score(const std::string& lvl) {
        std::string l = to_lower_str(trim_str(lvl));
        if (l == "beginner") return 20.0;
        if (l == "basic") return 40.0;
        if (l == "intermediate") return 60.0;
        if (l == "advanced") return 75.0;
        if (l == "expert") return 90.0;
        return 0.0;
    }

    struct SkillComputationResult {
        bool available{false};
        double score{0.0};
        double confidence{0.0};
        std::string source{"unavailable"};
        double nominal_coverage_at{0.0};
        int effective_episodes{0};
        std::string explanation;
    };

    SkillComputationResult compute_skill() const {
        SkillComputationResult res;

        double q_score = 0.0;
        bool has_q = false;
        if (has_questionnaire) {
            if (questionnaire_score > 0.0) {
                q_score = questionnaire_score;
                has_q = true;
            } else if (!questionnaire_level.empty()) {
                double qs = questionnaire_level_to_score(questionnaire_level);
                if (qs > 0.0) {
                    q_score = qs;
                    has_q = true;
                }
            }
        }

        double sum_available_weights = 0.0;
        double weighted_sum = 0.0;

        if (repo_available) {
            double r_score = sat(unique_contributed_repositories, 4.0);
            weighted_sum += 0.15 * r_score;
            sum_available_weights += 0.15;
        }

        if (standalone_commits_available) {
            double c_score = sat(standalone_commits, 80.0);
            weighted_sum += 0.20 * c_score;
            sum_available_weights += 0.20;
        }

        if (pr_volume_available) {
            double pr_score = sat(unique_authored_prs, 8.0);
            weighted_sum += 0.20 * pr_score;
            sum_available_weights += 0.20;
        }

        if (merge_rate_available && decided_prs >= 5) {
            double mr_score = merge_rate(merged_decided_prs, decided_prs);
            weighted_sum += 0.30 * mr_score;
            sum_available_weights += 0.30;
        }

        if (recency_available) {
            double rec_score = recency_decay(months_since_last_activity);
            weighted_sum += 0.15 * rec_score;
            sum_available_weights += 0.15;
        }

        res.nominal_coverage_at = sum_available_weights;

        bool observed_available = false;
        double skill_obs = 0.0;
        double c_obs = 0.0;
        int n_eff = 0;

        if (profile_coverage_known && source_quality_known && sum_available_weights >= 0.40) {
            skill_obs = weighted_sum / sum_available_weights;
            observed_available = true;

            int pr_count = pr_volume_available ? std::max(0, unique_authored_prs) : 0;
            int commit_count = standalone_commits_available ? std::max(0, standalone_commits) : 0;
            n_eff = pr_count + commit_count;
            res.effective_episodes = n_eff;

            double p_cov = std::max(0.0, std::min(1.0, profile_coverage));
            double s_qual = std::max(0.0, std::min(1.0, source_quality));
            c_obs = std::min(1.0, static_cast<double>(n_eff) / 10.0) * p_cov * s_qual;
        }

        // Case 1: Pure questionnaire
        if (!observed_available && has_q) {
            res.available = true;
            res.score = q_score;
            res.confidence = 0.40;
            res.source = "self_reported";
            res.explanation = "Observed evidence below coverage threshold; scored via self-reported questionnaire.";
            return res;
        }

        // Case 2: Blended observed + questionnaire
        if (observed_available && has_q) {
            double q_conf = 0.40;
            double q_weight = std::min(0.20, q_conf / (c_obs + q_conf));
            res.available = true;
            res.score = (1.0 - q_weight) * skill_obs + q_weight * q_score;
            res.confidence = (1.0 - q_weight) * c_obs + q_weight * q_conf;
            res.source = "blended";
            res.explanation = "Blended observed and questionnaire.";
            return res;
        }

        // Case 3: Pure observed
        if (observed_available) {
            res.available = true;
            res.score = skill_obs;
            res.confidence = c_obs;
            res.source = "observed";
            res.explanation = "Observed skill score.";
            return res;
        }

        // Case 4: Neither available
        res.available = false;
        res.source = "unavailable";
        res.explanation = "Nominal coverage A_t < 0.40 and no questionnaire report available.";
        return res;
    }
};

// Contributor Profile
struct ContributorProfile {
    std::string user_id;
    std::unordered_map<std::string, ContributorSkill> skills;
    std::unordered_map<std::string, ContributorSkillEvidenceV2> skill_evidence_v2;
    double capability{70.0}; // 0 - 100
    // Feedback historical statistics
    int accepted_count{0};
    int rejected_count{0};
    int completed_count{0};
    double similarity_to_history{75.0}; // 0 - 100
    bool has_feedback_history{false};
};

// Difficulty Evidence Breakdown
struct DifficultyEvidence {
    double scope_change_size{50.0};          // SC (25%)
    double technical_complexity{50.0};        // TC (25%)
    double historical_effort{50.0};           // HE (25%)
    double dependency_arch_impact{50.0};      // DI (15%)
    double coordination_complexity{50.0};     // CC (10%)
    bool available{true};

    double compute_issue_difficulty() const {
        return 0.25 * scope_change_size +
               0.25 * technical_complexity +
               0.25 * historical_effort +
               0.15 * dependency_arch_impact +
               0.10 * coordination_complexity;
    }
};

// Responsiveness Evidence Breakdown
struct ResponsivenessEvidence {
    double response_time_score{80.0};      // 30%
    double response_rate_score{80.0};      // 30%
    double pr_review_score{80.0};          // 25%
    double recent_maintainer_act{80.0};    // 15%
    bool available{true};

    double compute_responsiveness() const {
        return 0.30 * response_time_score +
               0.30 * response_rate_score +
               0.25 * pr_review_score +
               0.15 * recent_maintainer_act;
    }
};

// Repository Health Evidence Breakdown
struct RepositoryHealthEvidence {
    double recent_commits{80.0};     // 25%
    double pr_activity{80.0};         // 20%
    double issue_activity{80.0};      // 20%
    double active_contributors{80.0}; // 20%
    double recent_releases{80.0};     // 15%
    bool available{true};

    double compute_health() const {
        return 0.25 * recent_commits +
               0.20 * pr_activity +
               0.20 * issue_activity +
               0.20 * active_contributors +
               0.15 * recent_releases;
    }
};

// Freshness Evidence Breakdown
struct FreshnessEvidence {
    double age_score{80.0};      // 40%
    double activity_score{80.0}; // 60%
    bool available{true};

    double compute_freshness() const {
        return 0.40 * age_score + 0.60 * activity_score;
    }
};

// Issue Candidate Record
struct IssueRecord {
    std::string id;
    std::string issue_key;
    std::string source{"github"};
    std::string source_instance{"api.github.com"};
    std::string canonical_repository_key;
    std::string native_id;
    std::string repository_full_name;
    int issue_number{0};
    std::string title;
    std::string body;
    std::string issue_url;
    std::vector<std::string> labels;
    std::string state{"open"};
    bool is_assigned{false};
    std::string updated_at;
    std::string created_at;

    bool skill_available{true};
    std::vector<TechnologyRelevance> technologies;

    // V1 Evidence models (preserved for backward compatibility)
    DifficultyEvidence difficulty_evidence;
    ResponsivenessEvidence responsiveness_evidence;
    RepositoryHealthEvidence health_evidence;
    FreshnessEvidence freshness_evidence;

    // V2.3 Typed Evidence Envelopes
    Evidence<DifficultyEvidenceV2> difficulty_evidence_v2;
    Evidence<ResponsivenessEvidenceV2> responsiveness_evidence_v2;
    Evidence<RepositoryHealthEvidenceV2> health_evidence_v2;
    Evidence<FreshnessEvidenceV2> freshness_evidence_v2;

    std::string get_issue_key() const {
        if (!issue_key.empty()) return issue_key;
        std::string repo = !canonical_repository_key.empty() ? canonical_repository_key : repository_full_name;
        if (repo.empty()) repo = "unknown/unknown";
        std::string nat = !native_id.empty() ? native_id : std::to_string(issue_number);
        return source + "|" + source_instance + "|" + repo + "|" + nat;
    }

    bool is_eligible() const {
        if (state != "open") return false;
        if (is_assigned) return false;
        if (issue_url.empty()) return false;
        return true;
    }

    void normalize_technologies() {
        double total_raw = 0.0;
        for (const auto& t : technologies) {
            total_raw += (t.raw_relevance > 0.0 ? t.raw_relevance : 0.0);
        }
        if (total_raw > 0.0) {
            for (auto& t : technologies) {
                t.normalized_relevance = t.raw_relevance / total_raw;
            }
        } else if (!technologies.empty()) {
            double uniform = 1.0 / static_cast<double>(technologies.size());
            for (auto& t : technologies) {
                t.normalized_relevance = uniform;
            }
        }
    }
};

// 6 Ranking Parameters Component Weights
struct RankingWeights {
    double skill_weight{0.35};        // 35%
    double difficulty_weight{0.25};   // 25%
    double responsiveness_weight{0.15};// 15%
    double health_weight{0.10};       // 10%
    double feedback_weight{0.10};     // 10%
    double freshness_weight{0.05};    // 5%
};

// Component Scores and Explanations
struct ComponentScores {
    double skill_match{0.0};
    bool skill_available{true};

    double difficulty_match{0.0};
    double computed_issue_difficulty{0.0};
    bool difficulty_available{true};

    double responsiveness{0.0};
    bool responsiveness_available{true};

    double repository_health{0.0};
    bool health_available{true};

    double feedback{0.0};
    double acceptance_score{0.0};
    double completion_score{0.0};
    bool feedback_available{true};

    double freshness{0.0};
    bool freshness_available{true};

    // Effective renormalized weights used
    double effective_skill_weight{0.35};
    double effective_diff_weight{0.25};
    double effective_resp_weight{0.15};
    double effective_health_weight{0.10};
    double effective_fb_weight{0.10};
    double effective_fresh_weight{0.05};

    double weighted_skill{0.0};
    double weighted_difficulty{0.0};
    double weighted_responsiveness{0.0};
    double weighted_health{0.0};
    double weighted_feedback{0.0};
    double weighted_freshness{0.0};

    double final_score{0.0};
    std::vector<std::string> missing_components;
    std::string explanation;
};

// Ranking Result
struct RankingResult {
    IssueRecord issue;
    ComponentScores scores;
    int rank{0};
    bool verified{false};
    std::string verification_notes;
};

} // namespace issue_pilot
