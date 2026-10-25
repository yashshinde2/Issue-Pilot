#pragma once

#include <string>
#include <vector>
#include <unordered_set>
#include <map>
#include <cmath>
#include <algorithm>

namespace issue_pilot {

// Feature snapshot of an issue for affinity comparison
struct FeatureProfile {
    std::string source;
    std::string platform_issue_id;
    std::string repository_full_name;
    std::vector<std::string> technologies;
    std::vector<std::string> labels;
    std::string issue_type;            // e.g., "bug", "feature", "enhancement"
    double difficulty{-1.0};           // [0, 100], -1 if unavailable
    bool difficulty_available{false};
    std::vector<std::string> structured_metadata; // product, component, language, category
};

// Vote record passed to the affinity engine
struct IssueVoteRecord {
    std::string user_id;
    std::string source;
    std::string platform_issue_id;
    int vote{0}; // +1 for like, -1 for dislike
    FeatureProfile profile;
    std::string updated_at;
};

// Candidate issue to evaluate affinity for
struct CandidateIssue {
    std::string id;
    std::string source;
    std::string platform_issue_id;
    FeatureProfile profile;
};

// Configurable weights for feature groups
struct AffinityWeights {
    double technology_weight{0.30};
    double label_weight{0.30};
    double issue_type_weight{0.15};
    double difficulty_weight{0.10};
    double structured_metadata_weight{0.10};
    double repository_weight{0.05};

    double lambda{1.0}; // Smoothing constant for affinity
    double k{3.0};      // Evidence-scaling constant for confidence
    double correlation_threshold{0.85}; // Similarity threshold for correlated evidence damping
};

// Structured output for each candidate issue
struct AffinityResult {
    std::string id;
    std::string source;
    std::string platform_issue_id;
    double affinity_score{0.0};      // [-100, 100]
    double confidence{0.0};          // [0, 1]
    double positive_evidence{0.0};
    double negative_evidence{0.0};
    bool conflict_indicator{false};
    double conflict_ratio{0.0};      // [0, 1]
    std::vector<std::string> matched_feature_groups;
    std::vector<std::string> explanations;
};

class VoteAffinityEngine {
public:
    static constexpr const char* ALGORITHM_VERSION = "2.0.0-affinity";

    explicit VoteAffinityEngine(AffinityWeights weights = AffinityWeights())
        : weights_(weights) {}

    const AffinityWeights& get_weights() const { return weights_; }
    void set_weights(const AffinityWeights& w) { weights_ = w; }

    // Compute pair-wise similarity between two feature profiles
    // Returns similarity in [0, 1] and populates matched_groups
    double compute_similarity(
        const FeatureProfile& candidate,
        const FeatureProfile& voted,
        std::vector<std::string>* out_matched_groups = nullptr
    ) const;

    // Evaluate affinity for a list of candidate issues against a user's vote history
    std::vector<AffinityResult> evaluate(
        const std::vector<CandidateIssue>& candidates,
        const std::vector<IssueVoteRecord>& raw_votes
    ) const;

    // Helpers for normalization & similarity calculation
    static std::string normalize_token(const std::string& token);
    static std::vector<std::string> normalize_tokens(const std::vector<std::string>& tokens);
    static double jaccard_similarity(const std::vector<std::string>& a, const std::vector<std::string>& b);
    static double categorical_similarity(const std::string& a, const std::string& b);
    static double difficulty_similarity(double diff_a, double diff_b);

private:
    AffinityWeights weights_;
};

} // namespace issue_pilot
