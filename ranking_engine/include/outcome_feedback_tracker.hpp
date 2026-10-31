#pragma once

#include "types.hpp"
#include <algorithm>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace issue_pilot {

enum class OutcomeEventType {
  RECOMMENDATION_SHOWN,
  ACCEPTED,
  REJECTED,
  COMPLETED,
  NOT_COMPLETED,
  REOPENED
};

struct OutcomeEvent {
  std::string event_id;
  std::string user_id;
  std::string issue_key;
  std::string technology;
  std::string difficulty_band;
  OutcomeEventType event_type{OutcomeEventType::RECOMMENDATION_SHOWN};
  int64_t timestamp_utc{0};
  std::string cohort_key;
  std::string exposure_window;
};

class OutcomeFeedbackTracker {
private:
  std::unordered_set<std::string> seen_event_ids_;

  struct IssueDecisionState {
    std::string issue_key;
    std::string technology;
    std::string difficulty_band;
    bool has_acceptance_decision{false};
    OutcomeEventType acceptance_decision{
        OutcomeEventType::RECOMMENDATION_SHOWN};
    bool has_completion_decision{false};
    OutcomeEventType completion_decision{
        OutcomeEventType::RECOMMENDATION_SHOWN};
    std::vector<OutcomeEvent> event_history;
  };

  std::unordered_map<std::string,
                     std::unordered_map<std::string, IssueDecisionState>>
      user_issue_states_;
  std::vector<OutcomeEvent> audit_log_;

  static std::string canonical_tech(const std::string &tech) {
    std::string s = trim_str(tech);
    return to_lower_str(s);
  }

  static std::string canonical_band(const std::string &band) {
    std::string s = trim_str(band);
    std::transform(s.begin(), s.end(), s.begin(), ::toupper);
    return s;
  }

public:
  OutcomeFeedbackTracker() = default;

  bool record_event(const OutcomeEvent &event) {
    audit_log_.push_back(event);

    std::string ev_id = trim_str(event.event_id);
    if (!ev_id.empty()) {
      if (seen_event_ids_.find(ev_id) != seen_event_ids_.end()) {
        // Exact duplicate event ID ignored
        return false;
      }
      seen_event_ids_.insert(ev_id);
    }

    if (event.event_type == OutcomeEventType::RECOMMENDATION_SHOWN) {
      return true; // Exposure-logged
    }

    std::string user_id = trim_str(event.user_id);
    std::string issue_key = trim_str(event.issue_key);
    if (user_id.empty() || issue_key.empty()) {
      return false;
    }

    auto &user_map = user_issue_states_[user_id];
    auto it = user_map.find(issue_key);
    if (it == user_map.end()) {
      IssueDecisionState new_state;
      new_state.issue_key = issue_key;
      new_state.technology = canonical_tech(event.technology);
      new_state.difficulty_band = canonical_band(event.difficulty_band);
      it = user_map.emplace(issue_key, std::move(new_state)).first;
    }

    auto &state = it->second;
    state.event_history.push_back(event);

    if (event.event_type == OutcomeEventType::ACCEPTED ||
        event.event_type == OutcomeEventType::REJECTED) {
      if (state.has_acceptance_decision) {
        // Duplicate acceptance event ignored
        return false;
      }
      if (state.has_completion_decision &&
          event.event_type == OutcomeEventType::REJECTED) {
        // Cannot reject an issue that was already completed
        return false;
      }
      state.has_acceptance_decision = true;
      state.acceptance_decision = event.event_type;
      return true;
    }

    if (event.event_type == OutcomeEventType::COMPLETED ||
        event.event_type == OutcomeEventType::NOT_COMPLETED) {
      if (state.has_completion_decision) {
        // Duplicate completion event ignored
        return false;
      }
      if (state.has_acceptance_decision &&
          state.acceptance_decision == OutcomeEventType::REJECTED) {
        // Conflicting state: rejected issue cannot be completed
        return false;
      }
      // Preserve completion event independently; do NOT create a synthetic acceptance trial
      state.has_completion_decision = true;
      state.completion_decision = event.event_type;
      return true;
    }

    if (event.event_type == OutcomeEventType::REOPENED) {
      // Reopening an accepted issue reverses its terminal completion state
      if (state.has_completion_decision) {
        state.has_completion_decision = false;
        return true;
      }
      return false;
    }

    return true;
  }

  OutcomeFeedbackEvidenceV2
  build_evidence(const std::string &user_id,
                 const std::string &target_technology,
                 const std::string &target_difficulty_band) const {
    OutcomeFeedbackEvidenceV2 evidence;
    evidence.user_id = trim_str(user_id);
    std::string can_tech = canonical_tech(target_technology);
    std::string can_band = canonical_band(target_difficulty_band);
    evidence.technology = can_tech;
    evidence.difficulty_band = can_band;

    auto u_it = user_issue_states_.find(evidence.user_id);
    if (u_it == user_issue_states_.end() || u_it->second.empty()) {
      // Cold start: empty counts
      evidence.l1_accept = HierarchyCounts(0, 0);
      evidence.l2_accept = HierarchyCounts(0, 0);
      evidence.l3_accept = HierarchyCounts(0, 0);
      evidence.l1_complete = HierarchyCounts(0, 0);
      evidence.l2_complete = HierarchyCounts(0, 0);
      evidence.l3_complete = HierarchyCounts(0, 0);
      return evidence;
    }

    int l1_acc_trials = 0, l1_acc_success = 0;
    int l2_acc_trials = 0, l2_acc_success = 0;
    int l3_acc_trials = 0, l3_acc_success = 0;

    int l1_comp_trials = 0, l1_comp_success = 0;
    int l2_comp_trials = 0, l2_comp_success = 0;
    int l3_comp_trials = 0, l3_comp_success = 0;

    for (const auto &pair : u_it->second) {
      const auto &s = pair.second;
      bool match_tech = !can_tech.empty() && (can_tech == s.technology);
      bool match_diff =
          match_tech && !can_band.empty() && (can_band == s.difficulty_band);

      if (s.has_acceptance_decision) {
        l1_acc_trials++;
        if (s.acceptance_decision == OutcomeEventType::ACCEPTED) {
          l1_acc_success++;
        }
        if (match_tech) {
          l2_acc_trials++;
          if (s.acceptance_decision == OutcomeEventType::ACCEPTED) {
            l2_acc_success++;
          }
        }
        if (match_diff) {
          l3_acc_trials++;
          if (s.acceptance_decision == OutcomeEventType::ACCEPTED) {
            l3_acc_success++;
          }
        }
      }

      // Completion accounting:
      // V2.3 Section 9: completion trials are accepted issues with a final completed/not-completed outcome (n_complete <= a_accept)
      if (s.has_acceptance_decision && s.acceptance_decision == OutcomeEventType::ACCEPTED && s.has_completion_decision) {
        l1_comp_trials++;
        if (s.completion_decision == OutcomeEventType::COMPLETED) {
          l1_comp_success++;
        }
        if (match_tech) {
          l2_comp_trials++;
          if (s.completion_decision == OutcomeEventType::COMPLETED) {
            l2_comp_success++;
          }
        }
        if (match_diff) {
          l3_comp_trials++;
          if (s.completion_decision == OutcomeEventType::COMPLETED) {
            l3_comp_success++;
          }
        }
      }
    }

    evidence.l1_accept = HierarchyCounts(l1_acc_success, l1_acc_trials);
    evidence.l2_accept = HierarchyCounts(l2_acc_success, l2_acc_trials);
    evidence.l3_accept = HierarchyCounts(l3_acc_success, l3_acc_trials);

    evidence.l1_complete = HierarchyCounts(l1_comp_success, l1_comp_trials);
    evidence.l2_complete = HierarchyCounts(l2_comp_success, l2_comp_trials);
    evidence.l3_complete = HierarchyCounts(l3_comp_success, l3_comp_trials);

    return evidence;
  }

  const std::vector<OutcomeEvent> &get_audit_log() const { return audit_log_; }

  void clear() {
    user_issue_states_.clear();
    audit_log_.clear();
    seen_event_ids_.clear();
  }
};

} // namespace issue_pilot