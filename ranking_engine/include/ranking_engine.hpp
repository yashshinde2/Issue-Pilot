// Ranking Engine

#pragma once

#include "types.hpp"
#include <algorithm>
#include <memory>
#include <string>
#include <vector>

namespace issue_pilot {

class RankingEngine {
public:
  static constexpr const char *ALGORITHM_VERSION = "1.0.0-phase1";

  RankingEngine(RankingWeights weights = RankingWeights())
      : weights_(weights) {}

  const RankingWeights &get_weights() const { return weights_; }
  void set_weights(const RankingWeights &w) { weights_ = w; }

  // Component Calculations
  double calculate_skill_match(const ContributorProfile &profile,
                               IssueRecord &issue) const;

  // V2.3 Multi-Technology Aggregation and Skill Match (Section 5.3)
  bool calculate_skill_match_v2(const ContributorProfile &profile,
                                const IssueRecord &issue,
                                double &out_skill_score,
                                double &out_confidence,
                                double &out_coverage) const;

  double calculate_difficulty_match(double capability,
                                    const DifficultyEvidence &diff_evidence,
                                    double &out_issue_difficulty) const;

  bool calculate_difficulty_fit_v2(double issue_difficulty, double confidence_id,
                                   double contributor_capability, double confidence_cap,
                                   double &out_d, double &out_c_d,
                                   double &out_penalty, double &out_g) const;

  double calculate_responsiveness(const ResponsivenessEvidence &evidence) const;

  double
  calculate_responsiveness_v2(const ResponsivenessEvidenceV2 &evidence) const;

  double
  calculate_repository_health(const RepositoryHealthEvidence &evidence) const;

  double
  calculate_repository_health_v2(const RepositoryHealthEvidenceV2 &evidence) const;

  bool calculate_feedback(const ContributorProfile &profile,
                          double &out_feedback, double &out_acceptance,
                          double &out_completion) const;

  bool calculate_feedback_v2(const OutcomeFeedbackEvidenceV2 &evidence,
                             double &out_feedback, double &out_confidence,
                             double &out_coverage) const;

  double calculate_freshness(const FreshnessEvidence &evidence) const;

  bool calculate_freshness_v2(const FreshnessEvidenceV2 &evidence,
                              double &out_freshness, double &out_confidence,
                              double &out_coverage) const;

  // Full Score Calculation for a Single Candidate Issue
  ComponentScores compute_scores(const ContributorProfile &profile,
                                 IssueRecord &issue) const;

  // Rank a set of candidates with eligibility filtering and tie-breaking
  std::vector<RankingResult>
  rank_candidates(const ContributorProfile &profile,
                  std::vector<IssueRecord> &candidates) const;

  // Generate human-readable explanation
  static std::string generate_explanation(const ComponentScores &scores);

private:
  RankingWeights weights_;
};

} // namespace issue_pilot
