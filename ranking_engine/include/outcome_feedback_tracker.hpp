#pragma once

#include "types.hpp"
#include <algorithm>
#include <map>
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
  int64_t decision_revision{1};
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
    std::string ev_id = trim_str(event.event_id);
    if (ev_id.empty()) {
      // Mandatory event_id integrity check: cannot track, deduplicate, or tie-break without authoritative non-blank ID
      return false;
    }
    if (seen_event_ids_.find(ev_id) != seen_event_ids_.end()) {
      // Duplicate event ID rejected (idempotent delivery deduplication)
      return false;
    }

    if (event.timestamp_utc <= 0) {
      // Mandatory timestamp authority check: cannot fabricate chronology (missing or non-positive rejected)
      return false;
    }

    if (event.decision_revision <= 0) {
      // Mandatory decision_revision check: must be >= 1
      return false;
    }

    std::string user_id = trim_str(event.user_id);
    std::string issue_key = trim_str(event.issue_key);
    if (user_id.empty() || issue_key.empty()) {
      return false;
    }

    seen_event_ids_.insert(ev_id);
    audit_log_.push_back(event);

    if (event.event_type == OutcomeEventType::RECOMMENDATION_SHOWN) {
      return true; // Exposure-logged
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

    // Sort events deterministically by (decision_revision, timestamp_utc, event_id)
    std::sort(state.event_history.begin(), state.event_history.end(),
              [](const OutcomeEvent &a, const OutcomeEvent &b) {
                if (a.decision_revision != b.decision_revision) {
                  return a.decision_revision < b.decision_revision;
                }
                if (a.timestamp_utc != b.timestamp_utc) {
                  return a.timestamp_utc < b.timestamp_utc;
                }
                return a.event_id < b.event_id;
              });

    // Group events by decision_revision
    std::map<int64_t, std::vector<OutcomeEvent>> rev_groups;
    for (const auto &ev : state.event_history) {
      rev_groups[ev.decision_revision].push_back(ev);
    }

    bool has_acc = false;
    OutcomeEventType eff_acc = OutcomeEventType::RECOMMENDATION_SHOWN;
    bool has_comp = false;
    OutcomeEventType eff_comp = OutcomeEventType::RECOMMENDATION_SHOWN;

    for (const auto &pair : rev_groups) {
      const auto &rev_events = pair.second;
      bool r_has_acc = false;
      bool r_has_rej = false;
      bool r_has_comp = false;
      OutcomeEventType r_comp_type = OutcomeEventType::RECOMMENDATION_SHOWN;
      bool r_has_reopen = false;

      for (const auto &ev : rev_events) {
        if (ev.event_type == OutcomeEventType::ACCEPTED) r_has_acc = true;
        else if (ev.event_type == OutcomeEventType::REJECTED) r_has_rej = true;
        else if (ev.event_type == OutcomeEventType::COMPLETED || ev.event_type == OutcomeEventType::NOT_COMPLETED) {
          r_has_comp = true;
          r_comp_type = ev.event_type;
        } else if (ev.event_type == OutcomeEventType::REOPENED) {
          r_has_reopen = true;
        }
      }

      if (r_has_acc && r_has_rej) {
        // Conflicting decisions at the EXACT SAME revision: QUARANTINE!
        has_acc = false;
        eff_acc = OutcomeEventType::RECOMMENDATION_SHOWN;
      } else if (r_has_acc) {
        has_acc = true;
        eff_acc = OutcomeEventType::ACCEPTED;
      } else if (r_has_rej) {
        if (!has_comp) {
          has_acc = true;
          eff_acc = OutcomeEventType::REJECTED;
        }
      }

      if (r_has_reopen) {
        has_comp = false;
        eff_comp = OutcomeEventType::RECOMMENDATION_SHOWN;
      } else if (r_has_comp) {
        if (has_acc && eff_acc == OutcomeEventType::REJECTED) {
          // rejected cannot complete
        } else {
          has_comp = true;
          eff_comp = r_comp_type;
        }
      }
    }

    state.has_acceptance_decision = has_acc;
    state.acceptance_decision = eff_acc;
    state.has_completion_decision = has_comp;
    state.completion_decision = eff_comp;
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