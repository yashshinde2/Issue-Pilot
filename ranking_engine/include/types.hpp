#pragma once

#include <string>
#include <vector>
#include <map>
#include <unordered_map>
#include <cmath>
#include <algorithm>
#include <iostream>
#include <sstream>
#include <iomanip>

namespace issue_pilot {

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
};

// Contributor Profile
struct ContributorProfile {
    std::string user_id;
    std::unordered_map<std::string, ContributorSkill> skills;
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

    std::vector<TechnologyRelevance> technologies;
    DifficultyEvidence difficulty_evidence;
    ResponsivenessEvidence responsiveness_evidence;
    RepositoryHealthEvidence health_evidence;
    FreshnessEvidence freshness_evidence;

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
