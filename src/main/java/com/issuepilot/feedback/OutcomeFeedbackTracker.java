package com.issuepilot.feedback;

import com.issuepilot.model.EvidenceModelsV2.OutcomeFeedbackEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.OutcomeFeedbackEvidenceV2.HierarchyCounts;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V2.3 User Outcome Feedback Tracker.
 * Manages deterministic, auditable event logging, duplicate outcome prevention,
 * and multi-level hierarchical aggregation conforming strictly to Section 9.
 */
public class OutcomeFeedbackTracker {

    public enum EventType {
        RECOMMENDATION_SHOWN,
        ACCEPTED,
        REJECTED,
        COMPLETED,
        NOT_COMPLETED,
        REOPENED
    }

    public record OutcomeEvent(
            String eventId,
            String userId,
            String issueKey,
            String technology,
            String difficultyBand,
            EventType eventType,
            Instant timestamp,
            String cohortKey,
            String exposureWindow) {

        public OutcomeEvent {
            if (userId == null || userId.isBlank()) {
                throw new IllegalArgumentException("userId must not be blank");
            }
            if (issueKey == null || issueKey.isBlank()) {
                throw new IllegalArgumentException("issueKey must not be blank");
            }
            if (eventType == null) {
                throw new IllegalArgumentException("eventType must not be null");
            }
            if (timestamp == null) {
                timestamp = Instant.now();
            }
            if (eventId == null || eventId.isBlank()) {
                eventId = UUID.randomUUID().toString();
            }
        }
    }

    private static class IssueDecisionState {
        final String issueKey;
        final String technology;
        final String difficultyBand;
        EventType acceptanceDecision; // ACCEPTED or REJECTED
        EventType completionDecision; // COMPLETED or NOT_COMPLETED
        final List<OutcomeEvent> eventHistory = new ArrayList<>();

        IssueDecisionState(String issueKey, String technology, String difficultyBand) {
            this.issueKey = issueKey;
            this.technology = canonicalTech(technology);
            this.difficultyBand = canonicalBand(difficultyBand);
        }
    }

    private final Map<String, Map<String, IssueDecisionState>> userIssueStates = new ConcurrentHashMap<>();
    private final List<OutcomeEvent> auditLog = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    private static String canonicalTech(String tech) {
        return tech != null && !tech.isBlank() ? tech.trim().toLowerCase(Locale.ROOT) : null;
    }

    private static String canonicalBand(String band) {
        return band != null && !band.isBlank() ? band.trim().toUpperCase(Locale.ROOT) : null;
    }

    public synchronized boolean recordEvent(OutcomeEvent event) {
        if (event.eventId() != null && !event.eventId().isBlank()) {
            if (!seenEventIds.add(event.eventId().trim())) {
                // Duplicate event ID ignored (idempotency)
                return false;
            }
        }
        auditLog.add(event);

        if (event.eventType() == EventType.RECOMMENDATION_SHOWN) {
            // Recommendation shown is exposure-logged for auditing
            return true;
        }

        String userId = event.userId().trim();
        String issueKey = event.issueKey().trim();

        Map<String, IssueDecisionState> userMap = userIssueStates.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        IssueDecisionState state = userMap.computeIfAbsent(issueKey, k -> new IssueDecisionState(issueKey, event.technology(), event.difficultyBand()));
        state.eventHistory.add(event);

        if (event.eventType() == EventType.ACCEPTED || event.eventType() == EventType.REJECTED) {
            if (state.acceptanceDecision != null) {
                // Contradictory decision (ACCEPTED then REJECTED, or REJECTED then ACCEPTED) or duplicate decision ignored
                return false;
            }
            if (state.completionDecision != null && event.eventType() == EventType.REJECTED) {
                // Cannot reject an issue that has already completed
                return false;
            }
            state.acceptanceDecision = event.eventType();
            return true;
        }

        if (event.eventType() == EventType.COMPLETED || event.eventType() == EventType.NOT_COMPLETED) {
            if (state.completionDecision != null) {
                // Duplicate completion event ignored
                return false;
            }
            if (state.acceptanceDecision == EventType.REJECTED) {
                // Conflicting state: issue was rejected, cannot complete
                return false;
            }
            // Preserve completion event independently; do NOT create a synthetic acceptance trial
            state.completionDecision = event.eventType();
            return true;
        }

        if (event.eventType() == EventType.REOPENED) {
            // Reopening an accepted issue reverses its terminal completion state, returning it to in-progress
            if (state.completionDecision != null) {
                state.completionDecision = null;
                return true;
            }
            return false;
        }

        return true;
    }

    public OutcomeFeedbackEvidenceV2 buildEvidence(String userId, String targetTechnology, String targetDifficultyBand) {
        OutcomeFeedbackEvidenceV2 evidence = new OutcomeFeedbackEvidenceV2();
        evidence.setUserId(userId);
        String canTech = canonicalTech(targetTechnology);
        String canBand = canonicalBand(targetDifficultyBand);
        evidence.setTechnology(canTech);
        evidence.setDifficultyBand(canBand);

        Map<String, IssueDecisionState> userMap = userIssueStates.get(userId != null ? userId.trim() : "");
        if (userMap == null || userMap.isEmpty()) {
            // Cold start: empty hierarchy counts
            evidence.setL1Accept(new HierarchyCounts(0, 0));
            evidence.setL2Accept(new HierarchyCounts(0, 0));
            evidence.setL3Accept(new HierarchyCounts(0, 0));
            evidence.setL1Complete(new HierarchyCounts(0, 0));
            evidence.setL2Complete(new HierarchyCounts(0, 0));
            evidence.setL3Complete(new HierarchyCounts(0, 0));
            return evidence;
        }

        int l1AccTrials = 0, l1AccSuccess = 0;
        int l2AccTrials = 0, l2AccSuccess = 0;
        int l3AccTrials = 0, l3AccSuccess = 0;

        int l1CompTrials = 0, l1CompSuccess = 0;
        int l2CompTrials = 0, l2CompSuccess = 0;
        int l3CompTrials = 0, l3CompSuccess = 0;

        for (IssueDecisionState s : userMap.values()) {
            boolean matchTech = canTech != null && Objects.equals(canTech, s.technology);
            boolean matchDiff = matchTech && canBand != null && Objects.equals(canBand, s.difficultyBand);

            // Acceptance accounting
            if (s.acceptanceDecision != null) {
                l1AccTrials++;
                if (s.acceptanceDecision == EventType.ACCEPTED) {
                    l1AccSuccess++;
                }

                if (matchTech) {
                    l2AccTrials++;
                    if (s.acceptanceDecision == EventType.ACCEPTED) {
                        l2AccSuccess++;
                    }
                }

                if (matchDiff) {
                    l3AccTrials++;
                    if (s.acceptanceDecision == EventType.ACCEPTED) {
                        l3AccSuccess++;
                    }
                }
            }

            // Completion accounting:
            // V2.3 Section 9: completion trials are accepted issues with a final completed/not-completed outcome (n_complete <= a_accept)
            if (s.acceptanceDecision == EventType.ACCEPTED && s.completionDecision != null) {
                l1CompTrials++;
                if (s.completionDecision == EventType.COMPLETED) {
                    l1CompSuccess++;
                }

                if (matchTech) {
                    l2CompTrials++;
                    if (s.completionDecision == EventType.COMPLETED) {
                        l2CompSuccess++;
                    }
                }

                if (matchDiff) {
                    l3CompTrials++;
                    if (s.completionDecision == EventType.COMPLETED) {
                        l3CompSuccess++;
                    }
                }
            }
        }

        evidence.setL1Accept(new HierarchyCounts(l1AccSuccess, l1AccTrials));
        evidence.setL2Accept(new HierarchyCounts(l2AccSuccess, l2AccTrials));
        evidence.setL3Accept(new HierarchyCounts(l3AccSuccess, l3AccTrials));

        evidence.setL1Complete(new HierarchyCounts(l1CompSuccess, l1CompTrials));
        evidence.setL2Complete(new HierarchyCounts(l2CompSuccess, l2CompTrials));
        evidence.setL3Complete(new HierarchyCounts(l3CompSuccess, l3CompTrials));

        return evidence;
    }

    public List<OutcomeEvent> getAuditLog() {
        return Collections.unmodifiableList(new ArrayList<>(auditLog));
    }

    public void clear() {
        userIssueStates.clear();
        auditLog.clear();
        seenEventIds.clear();
    }
}

