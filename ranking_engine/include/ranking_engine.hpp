#pragma once

#include "types.hpp"
#include <vector>
#include <string>
#include <memory>
#include <algorithm>

namespace issue_pilot {

class RankingEngine {
public:
    static constexpr const char* ALGORITHM_VERSION = "1.0.0-phase1";

    RankingEngine(RankingWeights weights = RankingWeights()) 
        : weights_(weights) {}

    const RankingWeights& get_weights() const { return weights_; }
    void set_weights(const RankingWeights& w) { weights_ = w; }

    // Component Calculations
    double calculate_skill_match(const ContributorProfile& profile, 
                                 IssueRecord& issue) const;

    double calculate_difficulty_match(double capability, 
                                      const DifficultyEvidence& diff_evidence,
                                      double& out_issue_difficulty) const;

    double calculate_responsiveness(const ResponsivenessEvidence& evidence) const;

    double calculate_repository_health(const RepositoryHealthEvidence& evidence) const;

    bool calculate_feedback(const ContributorProfile& profile,
                            double& out_feedback,
                            double& out_acceptance,
                            double& out_completion) const;

    double calculate_freshness(const FreshnessEvidence& evidence) const;

    // Full Score Calculation for a Single Candidate Issue
    ComponentScores compute_scores(const ContributorProfile& profile,
                                  IssueRecord& issue) const;

    // Rank a set of candidates with eligibility filtering and tie-breaking
    std::vector<RankingResult> rank_candidates(const ContributorProfile& profile,
                                              std::vector<IssueRecord>& candidates) const;

    // Generate human-readable explanation
    static std::string generate_explanation(const ComponentScores& scores);

private:
    RankingWeights weights_;
};

} // namespace issue_pilot
