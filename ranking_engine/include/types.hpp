#pragma once

#include "unicode_nfc.hpp"
#include <algorithm>
#include <cmath>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace issue_pilot {

namespace versions {
static constexpr const char *ALGORITHM_VERSION = "2.3.0";
static constexpr const char *FEATURE_SCHEMA_VERSION = "2.3.0";
static constexpr const char *TECHNOLOGY_REGISTRY_VERSION = "1.0.0";
static constexpr const char *LABEL_TAXONOMY_VERSION = "1.0.0";
} // namespace versions

// Trims leading and trailing whitespace
inline std::string trim_str(const std::string &str) {
  size_t first = str.find_first_not_of(" \t\r\n");
  if (first == std::string::npos)
    return "";
  size_t last = str.find_last_not_of(" \t\r\n");
  return str.substr(first, (last - first + 1));
}

inline std::string to_lower_str(std::string str) {
  std::transform(str.begin(), str.end(), str.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  return str;
}

// Canonical Four-Part Issue Key Helper Functions
inline std::string build_issue_key(const std::string &source,
                                   const std::string &source_instance,
                                   const std::string &canonical_repository_key,
                                   const std::string &native_issue_id) {
  std::string s = to_lower_str(trim_str(source));
  std::string inst = unicode::to_nfc(trim_str(source_instance));
  std::string repo = unicode::to_nfc(trim_str(canonical_repository_key));
  std::string nat = unicode::to_nfc(trim_str(native_issue_id));
  if (s.empty() || inst.empty() || repo.empty() || nat.empty()) {
    throw std::invalid_argument("Issue key segments must not be empty");
  }
  if (s.find('|') != std::string::npos || inst.find('|') != std::string::npos ||
      repo.find('|') != std::string::npos ||
      nat.find('|') != std::string::npos) {
    throw std::invalid_argument("Issue key segments must not contain '|'");
  }
  return s + "|" + inst + "|" + repo + "|" + nat;
}

inline bool parse_issue_key(const std::string &key, std::string &out_source,
                            std::string &out_source_instance,
                            std::string &out_canonical_repo,
                            std::string &out_native_id) {
  std::string trimmed_key = trim_str(key);
  if (trimmed_key.empty())
    return false;
  std::vector<std::string> parts;
  std::string token;
  std::istringstream tokenStream(trimmed_key);
  while (std::getline(tokenStream, token, '|')) {
    parts.push_back(token);
  }
  if (!trimmed_key.empty() && trimmed_key.back() == '|') {
    parts.push_back("");
  }
  if (parts.size() != 4)
    return false;
  for (size_t i = 0; i < parts.size(); ++i) {
    parts[i] = unicode::to_nfc(trim_str(parts[i]));
    if (parts[i].empty())
      return false;
  }
  out_source = to_lower_str(parts[0]);
  out_source_instance = parts[1];
  out_canonical_repo = parts[2];
  out_native_id = parts[3];
  return true;
}

inline double validate_confidence(double conf) {
  if (std::isnan(conf) || std::isinf(conf) || conf < 0.0 || conf > 1.0) {
    throw std::invalid_argument(
        "Confidence must be a finite number in [0.0, 1.0], got: " +
        std::to_string(conf));
  }
  return conf;
}

// Generic Typed Evidence Envelope (Algorithm V2.3 Section 2)
template <typename T> struct Evidence {
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

  static Evidence<T> of(const T &val, double conf, const std::string &src,
                        const std::string &inst, const std::string &mth) {
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

  static Evidence<T> known_zero(double conf, const std::string &src,
                                const std::string &inst, const std::string &mth,
                                const T &zero_val) {
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

  static Evidence<T> unavailable(const std::string &reason,
                                 const std::string &src,
                                 const std::string &inst) {
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
  double scope_change_size{50.0};       // SC
  double technical_complexity{50.0};    // TC
  double dependency_arch_impact{50.0};  // DI
  double coordination_complexity{50.0}; // CC

  bool scope_available{false};
  bool technical_available{false};
  bool dependency_available{false};
  bool coordination_available{false};

  double scope_confidence{1.0};
  double technical_confidence{1.0};
  double dependency_confidence{1.0};
  double coordination_confidence{1.0};

  std::string scope_directness{"explicit_structured"};
  std::string technical_directness{"explicit_structured"};
  std::string dependency_directness{"explicit_structured"};
  std::string coordination_directness{"explicit_structured"};

  double label_adjustment{0.0};

  static bool is_valid_anchor(double val) {
    if (!std::isfinite(val)) return false;
    return std::abs(val - 0.0) < 1e-6 ||
           std::abs(val - 25.0) < 1e-6 ||
           std::abs(val - 50.0) < 1e-6 ||
           std::abs(val - 75.0) < 1e-6 ||
           std::abs(val - 100.0) < 1e-6;
  }

  static double directness_to_confidence(const std::string& directness) {
    std::string d = to_lower_str(trim_str(directness));
    if (d == "structured" || d == "explicit_structured") return 1.00;
    if (d == "unambiguous_prose" || d == "explicit_prose") return 0.80;
    if (d == "lexical_context" || d == "contextual_lexical") return 0.55;
    return 0.55;
  }

  static double compute_label_corroboration(const std::vector<std::string>& labels) {
    if (labels.empty()) return 0.0;
    double adj = 0.0;
    for (const auto& l : labels) {
      std::string norm = to_lower_str(trim_str(l));
      std::replace(norm.begin(), norm.end(), '_', '-');
      if (norm.find("good-first-issue") != std::string::npos ||
          norm.find("good first issue") != std::string::npos ||
          norm.find("easy") != std::string::npos ||
          norm.find("beginner") != std::string::npos ||
          norm.find("starter") != std::string::npos ||
          norm.find("trivial") != std::string::npos ||
          norm.find("low-hanging-fruit") != std::string::npos ||
          norm.find("first-timers-only") != std::string::npos ||
          norm.find("quick-win") != std::string::npos) {
        adj -= 1.5;
      } else if (norm.find("hard") != std::string::npos ||
                 norm.find("complex") != std::string::npos ||
                 norm.find("advanced") != std::string::npos ||
                 norm.find("expert") != std::string::npos ||
                 norm.find("epic") != std::string::npos ||
                 norm.find("high-complexity") != std::string::npos ||
                 norm.find("difficult") != std::string::npos) {
        adj += 1.5;
      }
    }
    return std::max(-3.0, std::min(3.0, adj));
  }

  struct DifficultyComputationResult {
    bool available{false};
    double issue_difficulty{0.0};
    double raw_difficulty{0.0};
    double confidence{0.0};
    double coverage_aid{0.0};
    double label_adjustment{0.0};
    std::string explanation;
  };

  struct FitComputationResult {
    bool available{false};
    double difficulty_match_score{0.0};
    double confidence{0.0};
    double fit_gap{0.0};
    double penalty{0.0};
    std::string explanation;
  };

  DifficultyComputationResult compute_difficulty(const std::vector<std::string>& labels) const {
    DifficultyComputationResult res;
    double a_id = 0.0;
    double weighted_sum = 0.0;
    double conf_sum = 0.0;

    // SC: 0.30
    if (scope_available && is_valid_anchor(scope_change_size)) {
      validate_confidence(scope_confidence);
      a_id += 0.30;
      weighted_sum += 0.30 * scope_change_size;
      conf_sum += 0.30 * scope_confidence;
    }

    // TC: 0.30
    if (technical_available && is_valid_anchor(technical_complexity)) {
      validate_confidence(technical_confidence);
      a_id += 0.30;
      weighted_sum += 0.30 * technical_complexity;
      conf_sum += 0.30 * technical_confidence;
    }

    // DI: 0.20
    if (dependency_available && is_valid_anchor(dependency_arch_impact)) {
      validate_confidence(dependency_confidence);
      a_id += 0.20;
      weighted_sum += 0.20 * dependency_arch_impact;
      conf_sum += 0.20 * dependency_confidence;
    }

    // CC: 0.20
    if (coordination_available && is_valid_anchor(coordination_complexity)) {
      validate_confidence(coordination_confidence);
      a_id += 0.20;
      weighted_sum += 0.20 * coordination_complexity;
      conf_sum += 0.20 * coordination_confidence;
    }

    res.coverage_aid = a_id;
    if (a_id < 0.50 - 1e-9) {
      res.available = false;
      res.explanation = "Coverage A_ID < 0.50 gate; difficulty is unavailable.";
      return res;
    }

    res.available = true;
    res.raw_difficulty = weighted_sum / a_id;
    res.confidence = conf_sum;

    double l_adj = 0.0;
    if (std::abs(label_adjustment) > 1e-6) {
      l_adj = std::max(-3.0, std::min(3.0, label_adjustment));
    } else if (!labels.empty()) {
      l_adj = compute_label_corroboration(labels);
    }
    res.label_adjustment = l_adj;
    res.issue_difficulty = std::max(0.0, std::min(100.0, res.raw_difficulty + l_adj));
    return res;
  }

  static FitComputationResult compute_fit(
      double issue_difficulty, double confidence_id,
      double contributor_capability, double confidence_cap
  ) {
    FitComputationResult res;
    if (!std::isfinite(issue_difficulty) || !std::isfinite(contributor_capability)) {
      res.available = false;
      res.explanation = "Missing issue difficulty or contributor capability.";
      return res;
    }

    double c_id = std::max(0.0, std::min(1.0, confidence_id));
    double c_cap = std::max(0.0, std::min(1.0, confidence_cap));

    double g = issue_difficulty - contributor_capability;
    double penalty = 0.0;
    if (std::abs(g) <= 5.0) {
      penalty = 0.0;
    } else if (g > 5.0) {
      penalty = 1.5 * (g - 5.0);
    } else {
      penalty = -g - 5.0; // (-g - 5) == (|g| - 5)
    }

    res.available = true;
    res.fit_gap = g;
    res.penalty = penalty;
    res.difficulty_match_score = std::max(0.0, std::min(100.0, 100.0 - penalty));
    res.confidence = std::min(c_id, c_cap);
    return res;
  }
};

// V2.3 Responsiveness Evidence: RT (30%), RR (30%), PR (25%), MA (15%)
// Minimum 5 samples per signal, count >= 2 signals, A_R >= 0.45.
// Role mapping verified required (Bugzilla R unavailable without verified role mapping).
// Pagination completeness required.
struct ResponsivenessComputationResult {
  bool available{false};
  double score{0.0};
  double confidence{0.0};
  double coverage_ar{0.0};
  int signal_count{0};
  std::string explanation;

  static ResponsivenessComputationResult unavailable(double coverage, int count, const std::string& reason) {
    ResponsivenessComputationResult r;
    r.available = false;
    r.score = 0.0;
    r.confidence = 0.0;
    r.coverage_ar = coverage;
    r.signal_count = count;
    r.explanation = reason;
    return r;
  }
};

struct ResponsivenessEvidenceV2 {
  double response_time_score{0.0};
  bool rt_available{false};
  int rt_samples{0};
  double rt_confidence{1.0};

  double response_rate_score{0.0};
  bool rr_available{false};
  int rr_samples{0};
  double rr_confidence{1.0};

  double pr_review_score{0.0};
  bool pr_available{false};
  int pr_samples{0};
  double pr_confidence{1.0};

  double recent_maintainer_act{0.0};
  bool ma_available{false};
  int ma_samples{0};
  double ma_confidence{1.0};

  bool pagination_complete{true};
  bool role_mapping_verified{true};

  static constexpr double CLOCK_SKEW_TOLERANCE_DAYS = 0.001; // ~86.4 seconds

  struct IssueResponseEvent {
    double age_hours{0.0};
    double first_reply_hours{-1.0};
    bool has_reply{false};
    bool complete_observation{true};

    bool is_mature() const {
      return age_hours >= 168.0 && complete_observation;
    }
    bool is_timely_replied() const {
      return is_mature() && has_reply && first_reply_hours >= 0.0 && first_reply_hours <= 168.0;
    }
  };

  static double evaluate_rr_cohort(const std::vector<IssueResponseEvent>& issues) {
    int mature_total = 0;
    int replied_within_7d = 0;
    for (const auto& e : issues) {
      if (e.is_mature()) {
        mature_total++;
        if (e.is_timely_replied()) {
          replied_within_7d++;
        }
      }
    }
    return calculate_rr_score(replied_within_7d, mature_total);
  }

  static double calculate_rt_score(double median_hours) {
    if (!std::isfinite(median_hours) || median_hours < 0.0) {
      throw std::invalid_argument("Negative or non-finite reply duration: " + std::to_string(median_hours));
    }
    return 100.0 / (1.0 + median_hours / 48.0);
  }

  static double calculate_rr_score(int replied_7d, int mature_total_7d) {
    if (mature_total_7d <= 0) return 0.0;
    return 100.0 * (static_cast<double>(std::max(0, replied_7d)) / static_cast<double>(mature_total_7d));
  }

  static double calculate_pr_score(double median_review_hours) {
    if (!std::isfinite(median_review_hours) || median_review_hours < 0.0) {
      throw std::invalid_argument("Negative or non-finite review duration: " + std::to_string(median_review_hours));
    }
    return 100.0 / (1.0 + median_review_hours / 72.0);
  }

  static double calculate_ma_score(double days_since_act) {
    if (!std::isfinite(days_since_act)) {
      throw std::invalid_argument("Non-finite days_since_act: " + std::to_string(days_since_act));
    }
    if (days_since_act < -CLOCK_SKEW_TOLERANCE_DAYS) {
      throw std::invalid_argument("Future timestamp exceeds clock skew tolerance: " + std::to_string(days_since_act));
    }
    if (days_since_act < 0.0) {
      days_since_act = 0.0;
    }
    return 100.0 * std::pow(0.5, days_since_act / 14.0);
  }

  ResponsivenessComputationResult compute_responsiveness() const {
    if (!role_mapping_verified) {
      return ResponsivenessComputationResult::unavailable(0.0, 0, "Maintainer role mapping unverified (e.g. Bugzilla without explicit maintainer role).");
    }
    if (!pagination_complete) {
      return ResponsivenessComputationResult::unavailable(0.0, 0, "Comment pagination incomplete.");
    }

    double a_r = 0.0;
    double weighted_sum = 0.0;
    double conf_sum = 0.0;
    int count = 0;

    // RT: 0.30, needs samples >= 5
    if (rt_available && rt_samples >= 5) {
      if (rt_confidence < 0.0 || rt_confidence > 1.0 || !std::isfinite(rt_confidence)) {
        throw std::invalid_argument("Invalid RT confidence");
      }
      a_r += 0.30;
      weighted_sum += 0.30 * std::max(0.0, std::min(100.0, response_time_score));
      conf_sum += 0.30 * rt_confidence;
      count++;
    }

    // RR: 0.30, needs samples >= 5
    if (rr_available && rr_samples >= 5) {
      if (rr_confidence < 0.0 || rr_confidence > 1.0 || !std::isfinite(rr_confidence)) {
        throw std::invalid_argument("Invalid RR confidence");
      }
      a_r += 0.30;
      weighted_sum += 0.30 * std::max(0.0, std::min(100.0, response_rate_score));
      conf_sum += 0.30 * rr_confidence;
      count++;
    }

    // PR: 0.25, needs samples >= 5
    if (pr_available && pr_samples >= 5) {
      if (pr_confidence < 0.0 || pr_confidence > 1.0 || !std::isfinite(pr_confidence)) {
        throw std::invalid_argument("Invalid PR confidence");
      }
      a_r += 0.25;
      weighted_sum += 0.25 * std::max(0.0, std::min(100.0, pr_review_score));
      conf_sum += 0.25 * pr_confidence;
      count++;
    }

    // MA: 0.15, needs samples >= 5
    if (ma_available && ma_samples >= 5) {
      if (ma_confidence < 0.0 || ma_confidence > 1.0 || !std::isfinite(ma_confidence)) {
        throw std::invalid_argument("Invalid MA confidence");
      }
      a_r += 0.15;
      weighted_sum += 0.15 * std::max(0.0, std::min(100.0, recent_maintainer_act));
      conf_sum += 0.15 * ma_confidence;
      count++;
    }

    if (count < 2 || a_r < 0.45 - 1e-9) {
      return ResponsivenessComputationResult::unavailable(a_r, count, "R requires >= 2 available signals and A_R >= 0.45.");
    }

    ResponsivenessComputationResult res;
    res.available = true;
    res.score = weighted_sum / a_r;
    res.confidence = conf_sum;
    res.coverage_ar = a_r;
    res.signal_count = count;
    res.explanation = "R computed successfully";
    return res;
  }
};

// V2.3 Repository Health Evidence: C90 (30%), PR30 (25%), AU90 (25%), MA90 (20%)
// Requires at least 2 direct signals and available nominal weight A_H >= 0.40.
// Bugzilla H is strictly unavailable.
struct HealthComputationResult {
  bool available{false};
  double score{0.0};
  double confidence{0.0};
  double coverage_ah{0.0};
  int signal_count{0};
  std::string explanation;

  static HealthComputationResult unavailable(double coverage, int count, const std::string& reason) {
    HealthComputationResult r;
    r.available = false;
    r.score = 0.0;
    r.confidence = 0.0;
    r.coverage_ah = coverage;
    r.signal_count = count;
    r.explanation = reason;
    return r;
  }
};

struct RepositoryHealthEvidenceV2 {
  double c90_score{0.0};
  bool c90_available{false};
  double c90_confidence{1.0};

  double pr30_score{0.0};
  bool pr30_available{false};
  double pr30_confidence{1.0};

  double au90_score{0.0};
  bool au90_available{false};
  double au90_confidence{1.0};

  double ma90_score{0.0};
  bool ma90_available{false};
  double ma90_confidence{1.0};

  bool code_host_available{true};

  static double calculate_c90_score(int human_commits_90d) {
    return 100.0 * std::min(1.0, std::log(1.0 + std::max(0, human_commits_90d)) / std::log(61.0));
  }

  static double calculate_pr30_score(int merged_prs_30d) {
    return 100.0 * std::min(1.0, std::max(0, merged_prs_30d) / 8.0);
  }

  static double calculate_au90_score(int distinct_authors_90d) {
    return 100.0 * std::min(1.0, std::max(0, distinct_authors_90d) / 6.0);
  }

  static constexpr double CLOCK_SKEW_TOLERANCE_DAYS = 0.001; // ~86.4 seconds

  static double calculate_ma90_score(double days_since_code_activity) {
    if (!std::isfinite(days_since_code_activity)) {
      throw std::invalid_argument("Non-finite days_since_code_activity: " + std::to_string(days_since_code_activity));
    }
    if (days_since_code_activity < -CLOCK_SKEW_TOLERANCE_DAYS) {
      throw std::invalid_argument("Future timestamp exceeds clock skew tolerance: " + std::to_string(days_since_code_activity));
    }
    if (days_since_code_activity < 0.0) {
      days_since_code_activity = 0.0;
    }
    return 100.0 * std::pow(0.5, days_since_code_activity / 90.0);
  }

  HealthComputationResult compute_health() const {
    if (!code_host_available) {
      return HealthComputationResult::unavailable(0.0, 0, "Direct code-host evidence unavailable (Bugzilla H unavailable).");
    }

    double a_h = 0.0;
    double weighted_sum = 0.0;
    double conf_sum = 0.0;
    int count = 0;

    // C90: 0.30
    if (c90_available && !std::isnan(c90_score)) {
      if (c90_confidence < 0.0 || c90_confidence > 1.0 || !std::isfinite(c90_confidence)) {
        throw std::invalid_argument("Invalid C90 confidence");
      }
      a_h += 0.30;
      weighted_sum += 0.30 * std::max(0.0, std::min(100.0, c90_score));
      conf_sum += 0.30 * c90_confidence;
      count++;
    }

    // PR30: 0.25
    if (pr30_available && !std::isnan(pr30_score)) {
      if (pr30_confidence < 0.0 || pr30_confidence > 1.0 || !std::isfinite(pr30_confidence)) {
        throw std::invalid_argument("Invalid PR30 confidence");
      }
      a_h += 0.25;
      weighted_sum += 0.25 * std::max(0.0, std::min(100.0, pr30_score));
      conf_sum += 0.25 * pr30_confidence;
      count++;
    }

    // AU90: 0.25
    if (au90_available && !std::isnan(au90_score)) {
      if (au90_confidence < 0.0 || au90_confidence > 1.0 || !std::isfinite(au90_confidence)) {
        throw std::invalid_argument("Invalid AU90 confidence");
      }
      a_h += 0.25;
      weighted_sum += 0.25 * std::max(0.0, std::min(100.0, au90_score));
      conf_sum += 0.25 * au90_confidence;
      count++;
    }

    // MA90: 0.20
    if (ma90_available && !std::isnan(ma90_score)) {
      if (ma90_confidence < 0.0 || ma90_confidence > 1.0 || !std::isfinite(ma90_confidence)) {
        throw std::invalid_argument("Invalid MA90 confidence");
      }
      a_h += 0.20;
      weighted_sum += 0.20 * std::max(0.0, std::min(100.0, ma90_score));
      conf_sum += 0.20 * ma90_confidence;
      count++;
    }

    if (count < 2 || a_h < 0.40 - 1e-9) {
      return HealthComputationResult::unavailable(a_h, count, "H requires >= 2 direct signals and A_H >= 0.40.");
    }

    HealthComputationResult res;
    res.available = true;
    res.score = weighted_sum / a_h;
    res.confidence = conf_sum;
    res.coverage_ah = a_h;
    res.signal_count = count;
    res.explanation = "H computed successfully";
    return res;
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

// V2.3 Contributor Skill Evidence per Technology conforming strictly to
// Sections 5.2 and 5.3
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
    if (x <= 0.0)
      return 0.0;
    return 100.0 * (1.0 - std::exp(-x / k));
  }

  static double recency_decay(double months) {
    if (months < 0.0)
      months = 0.0;
    return 100.0 * std::exp(-months / 12.0);
  }

  static double recency_half_life(double months) {
    if (months < 0.0)
      months = 0.0;
    return 100.0 * std::pow(0.5, months / 12.0);
  }

  static double merge_rate(int merged, int decided) {
    return 100.0 * (static_cast<double>(merged) + 2.0) /
           (static_cast<double>(decided) + 4.0);
  }

  static double questionnaire_level_to_score(const std::string &lvl) {
    std::string l = to_lower_str(trim_str(lvl));
    if (l == "beginner")
      return 20.0;
    if (l == "basic")
      return 40.0;
    if (l == "intermediate")
      return 60.0;
    if (l == "advanced")
      return 75.0;
    if (l == "expert")
      return 90.0;
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

    if (profile_coverage_known && source_quality_known &&
        sum_available_weights >= 0.40) {
      skill_obs = weighted_sum / sum_available_weights;
      observed_available = true;

      int pr_count = pr_volume_available ? std::max(0, unique_authored_prs) : 0;
      int commit_count =
          standalone_commits_available ? std::max(0, standalone_commits) : 0;
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
      res.explanation = "Observed evidence below coverage threshold; scored "
                        "via self-reported questionnaire.";
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
    res.explanation =
        "Nominal coverage A_t < 0.40 and no questionnaire report available.";
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
  double scope_change_size{50.0};       // SC (25%)
  double technical_complexity{50.0};    // TC (25%)
  double historical_effort{50.0};       // HE (25%)
  double dependency_arch_impact{50.0};  // DI (15%)
  double coordination_complexity{50.0}; // CC (10%)
  bool available{true};

  double compute_issue_difficulty() const {
    return 0.25 * scope_change_size + 0.25 * technical_complexity +
           0.25 * historical_effort + 0.15 * dependency_arch_impact +
           0.10 * coordination_complexity;
  }
};

// Responsiveness Evidence Breakdown
struct ResponsivenessEvidence {
  double response_time_score{80.0};   // 30%
  double response_rate_score{80.0};   // 30%
  double pr_review_score{80.0};       // 25%
  double recent_maintainer_act{80.0}; // 15%
  bool available{true};

  double compute_responsiveness() const {
    return 0.30 * response_time_score + 0.30 * response_rate_score +
           0.25 * pr_review_score + 0.15 * recent_maintainer_act;
  }
};

// Repository Health Evidence Breakdown
struct RepositoryHealthEvidence {
  double recent_commits{80.0};      // 25%
  double pr_activity{80.0};         // 20%
  double issue_activity{80.0};      // 20%
  double active_contributors{80.0}; // 20%
  double recent_releases{80.0};     // 15%
  bool available{true};

  double compute_health() const {
    return 0.25 * recent_commits + 0.20 * pr_activity + 0.20 * issue_activity +
           0.20 * active_contributors + 0.15 * recent_releases;
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
    if (!issue_key.empty())
      return issue_key;
    std::string repo = !canonical_repository_key.empty()
                           ? canonical_repository_key
                           : repository_full_name;
    if (repo.empty())
      repo = "unknown/unknown";
    std::string nat =
        !native_id.empty() ? native_id : std::to_string(issue_number);
    return source + "|" + source_instance + "|" + repo + "|" + nat;
  }

  bool is_eligible() const {
    if (state != "open")
      return false;
    if (is_assigned)
      return false;
    if (issue_url.empty())
      return false;
    return true;
  }

  void normalize_technologies() {
    double total_raw = 0.0;
    for (const auto &t : technologies) {
      total_raw += (t.raw_relevance > 0.0 ? t.raw_relevance : 0.0);
    }
    if (total_raw > 0.0) {
      for (auto &t : technologies) {
        t.normalized_relevance = t.raw_relevance / total_raw;
      }
    } else if (!technologies.empty()) {
      double uniform = 1.0 / static_cast<double>(technologies.size());
      for (auto &t : technologies) {
        t.normalized_relevance = uniform;
      }
    }
  }
};

// 6 Ranking Parameters Component Weights
struct RankingWeights {
  double skill_weight{0.35};          // 35%
  double difficulty_weight{0.25};     // 25%
  double responsiveness_weight{0.15}; // 15%
  double health_weight{0.10};         // 10%
  double feedback_weight{0.10};       // 10%
  double freshness_weight{0.05};      // 5%
};

// Component Scores and Explanations
struct ComponentScores {
  double skill_match{0.0};
  bool skill_available{true};

  double difficulty_match{0.0};
  double computed_issue_difficulty{0.0};
  bool difficulty_available{true};
  double skill_confidence{1.0};
  double difficulty_confidence{1.0};
  double fit_gap{0.0};
  double fit_penalty{0.0};

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
