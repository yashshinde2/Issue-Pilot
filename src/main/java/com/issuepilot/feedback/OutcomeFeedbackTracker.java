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

    public enum CommitStatus {
        COMMITTED,
        IDEMPOTENT_REPLAY,
        STALE_REVISION_CONFLICT,
        CONCURRENT_CAS_CONFLICT
    }

    public record CommitResult(
            CommitStatus status,
            OutcomeEvent event,
            long committedRevision,
            String message) {}

    public record OutcomeEvent(
            String eventId,
            String userId,
            String issueKey,
            String technology,
            String difficultyBand,
            EventType eventType,
            Instant timestamp,
            String cohortKey,
            String exposureWindow,
            long decisionRevision,
            String exposureId) {

        public OutcomeEvent(
                String eventId,
                String userId,
                String issueKey,
                String technology,
                String difficultyBand,
                EventType eventType,
                Instant timestamp,
                String cohortKey,
                String exposureWindow,
                long decisionRevision) {
            this(eventId, userId, issueKey, technology, difficultyBand, eventType, timestamp, cohortKey, exposureWindow, decisionRevision, null);
        }

        public OutcomeEvent(
                String eventId,
                String userId,
                String issueKey,
                String technology,
                String difficultyBand,
                EventType eventType,
                Instant timestamp,
                String cohortKey,
                String exposureWindow) {
            this(eventId, userId, issueKey, technology, difficultyBand, eventType, timestamp, cohortKey, exposureWindow, 1L, null);
        }

        public OutcomeEvent {
            if (eventType == null) {
                throw new IllegalArgumentException("eventType must not be null");
            }
        }
    }

    public static class ExposureDecisionState {
        final String exposureKey;
        final String issueKey;
        final String technology;
        final String difficultyBand;
        long currentRevision = 0L;
        EventType acceptanceDecision; // ACCEPTED or REJECTED
        EventType completionDecision; // COMPLETED or NOT_COMPLETED
        final List<OutcomeEvent> eventHistory = new ArrayList<>();
        final Map<String, OutcomeEvent> committedEvents = new ConcurrentHashMap<>();

        ExposureDecisionState(String exposureKey, String issueKey, String technology, String difficultyBand) {
            this.exposureKey = exposureKey;
            this.issueKey = issueKey;
            this.technology = canonicalTech(technology);
            this.difficultyBand = canonicalBand(difficultyBand);
        }

        public String getIssueKey() {
            return issueKey;
        }

        public String getExposureKey() {
            return exposureKey;
        }

        public long getCurrentRevision() {
            return currentRevision;
        }

        public EventType getAcceptanceDecision() {
            return acceptanceDecision;
        }

        public EventType getCompletionDecision() {
            return completionDecision;
        }

        public List<OutcomeEvent> getEventHistory() {
            return Collections.unmodifiableList(eventHistory);
        }
    }

    private final Map<String, Map<String, ExposureDecisionState>> userExposureStates = new ConcurrentHashMap<>();
    private final List<OutcomeEvent> auditLog = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    private static String canonicalTech(String tech) {
        return tech != null && !tech.isBlank() ? tech.trim().toLowerCase(Locale.ROOT) : null;
    }

    private static String canonicalBand(String band) {
        return band != null && !band.isBlank() ? band.trim().toUpperCase(Locale.ROOT) : null;
    }

    public synchronized CommitResult commitDecision(
            String eventId,
            String userId,
            String issueKey,
            String exposureId,
            String technology,
            String difficultyBand,
            EventType eventType,
            Instant timestamp,
            long expectedBaseRevision) {

        if (eventId == null || eventId.isBlank()) {
            return new CommitResult(CommitStatus.CONCURRENT_CAS_CONFLICT, null, -1L, "eventId is required");
        }
        if (userId == null || userId.isBlank() || issueKey == null || issueKey.isBlank()) {
            return new CommitResult(CommitStatus.CONCURRENT_CAS_CONFLICT, null, -1L, "userId and issueKey are required");
        }
        if (eventType == null) {
            return new CommitResult(CommitStatus.CONCURRENT_CAS_CONFLICT, null, -1L, "eventType is required");
        }
        Instant effectiveTs = timestamp != null ? timestamp : Instant.now();

        String uId = userId.trim();
        String iKey = issueKey.trim();
        String expId = exposureId != null && !exposureId.isBlank() ? exposureId.trim() : null;
        String expKey = expId != null ? expId : iKey;

        Map<String, ExposureDecisionState> userMap = userExposureStates.computeIfAbsent(uId, k -> new ConcurrentHashMap<>());
        ExposureDecisionState state = userMap.computeIfAbsent(expKey, k -> new ExposureDecisionState(expKey, iKey, technology, difficultyBand));

        // Check idempotent replay by eventId
        if (state.committedEvents.containsKey(eventId.trim())) {
            OutcomeEvent existing = state.committedEvents.get(eventId.trim());
            return new CommitResult(CommitStatus.IDEMPOTENT_REPLAY, existing, existing.decisionRevision(), "Idempotent duplicate event replay");
        }

        // Compare-And-Swap check against authoritative committed revision
        if (expectedBaseRevision != state.currentRevision) {
            if (expectedBaseRevision < state.currentRevision - 1) {
                return new CommitResult(CommitStatus.STALE_REVISION_CONFLICT, null, state.currentRevision,
                        "Stale offline decision: base revision " + expectedBaseRevision + " is behind current " + state.currentRevision);
            } else {
                return new CommitResult(CommitStatus.CONCURRENT_CAS_CONFLICT, null, state.currentRevision,
                        "Concurrent CAS conflict: expected base revision " + expectedBaseRevision + " != current " + state.currentRevision);
            }
        }

        // CAS succeeded: increment revision and commit
        long allocatedRevision = state.currentRevision + 1;
        state.currentRevision = allocatedRevision;

        OutcomeEvent committedEvent = new OutcomeEvent(
                eventId.trim(),
                uId,
                iKey,
                technology,
                difficultyBand,
                eventType,
                effectiveTs,
                null,
                null,
                allocatedRevision,
                expId
        );

        state.committedEvents.put(eventId.trim(), committedEvent);
        recordEvent(committedEvent);

        return new CommitResult(CommitStatus.COMMITTED, committedEvent, allocatedRevision, "Committed revision " + allocatedRevision);
    }

    public synchronized boolean recordEvent(OutcomeEvent event) {
        if (event == null) {
            return false;
        }
        // Mandatory eventId integrity check: cannot track, deduplicate, or tie-break without authoritative non-blank ID
        if (event.eventId() == null || event.eventId().isBlank()) {
            return false;
        }
        String evId = event.eventId().trim();
        if (!seenEventIds.add(evId)) {
            // Duplicate event ID rejected (idempotent delivery deduplication)
            return false;
        }

        // Mandatory timestamp authority check: cannot fabricate chronology (missing or non-positive rejected)
        if (event.timestamp() == null || event.timestamp().toEpochMilli() <= 0L) {
            return false;
        }

        // Mandatory decisionRevision check: must be >= 1
        if (event.decisionRevision() <= 0L) {
            return false;
        }

        // Mandatory user and issue identifiers
        if (event.userId() == null || event.userId().isBlank() || event.issueKey() == null || event.issueKey().isBlank()) {
            return false;
        }

        auditLog.add(event);

        if (event.eventType() == EventType.RECOMMENDATION_SHOWN) {
            // Recommendation shown is exposure-logged for auditing
            return true;
        }

        String userId = event.userId().trim();
        String issueKey = event.issueKey().trim();
        String expId = event.exposureId() != null && !event.exposureId().isBlank() ? event.exposureId().trim() : null;
        String expKey = expId != null ? expId : issueKey;

        Map<String, ExposureDecisionState> userMap = userExposureStates.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        ExposureDecisionState state = userMap.computeIfAbsent(expKey, k -> new ExposureDecisionState(expKey, issueKey, event.technology(), event.difficultyBand()));
        state.eventHistory.add(event);

        // Sort events deterministically by (decisionRevision, timestamp, eventId)
        state.eventHistory.sort((a, b) -> {
            int cmpRev = Long.compare(a.decisionRevision(), b.decisionRevision());
            if (cmpRev != 0) {
                return cmpRev;
            }
            int cmpTs = Long.compare(a.timestamp().toEpochMilli(), b.timestamp().toEpochMilli());
            if (cmpTs != 0) {
                return cmpTs;
            }
            return a.eventId().trim().compareTo(b.eventId().trim());
        });

        // Deterministic state machine replay grouped by per-exposure decision revision
        Map<Long, List<OutcomeEvent>> revGroups = new TreeMap<>();
        for (OutcomeEvent ev : state.eventHistory) {
            revGroups.computeIfAbsent(ev.decisionRevision(), k -> new ArrayList<>()).add(ev);
        }

        EventType effectiveAcc = null;
        EventType effectiveComp = null;

        for (Map.Entry<Long, List<OutcomeEvent>> entry : revGroups.entrySet()) {
            List<OutcomeEvent> revEvents = entry.getValue();

            boolean hasAcc = false;
            boolean hasRej = false;
            boolean hasComp = false;
            boolean hasNotComp = false;
            boolean hasReopen = false;

            for (OutcomeEvent ev : revEvents) {
                EventType t = ev.eventType();
                if (t == EventType.ACCEPTED) hasAcc = true;
                else if (t == EventType.REJECTED) hasRej = true;
                else if (t == EventType.COMPLETED) hasComp = true;
                else if (t == EventType.NOT_COMPLETED) hasNotComp = true;
                else if (t == EventType.REOPENED) hasReopen = true;
            }

            // 1. Acceptance transition at this revision
            if (hasAcc && hasRej) {
                // Same-revision conflict: quarantine acceptance!
                effectiveAcc = null;
            } else if (hasAcc) {
                effectiveAcc = EventType.ACCEPTED;
            } else if (hasRej) {
                if (effectiveComp != null) {
                    // Cannot retroactively reject an issue that was already completed at a prior revision
                } else {
                    effectiveAcc = EventType.REJECTED;
                }
            }

            // 2. Completion transition at this revision
            // Check for same-revision completion conflicts across transitions:
            // COMPLETED vs NOT_COMPLETED, COMPLETED vs REOPENED, NOT_COMPLETED vs REOPENED
            int compSignals = (hasComp ? 1 : 0) + (hasNotComp ? 1 : 0) + (hasReopen ? 1 : 0);
            if (compSignals > 1) {
                // Conflicting completion signals at same revision: quarantine completion!
                effectiveComp = null;
            } else if (hasReopen) {
                // Reopening reverses previous terminal completion outcome
                effectiveComp = null;
            } else if (hasComp) {
                effectiveComp = EventType.COMPLETED;
            } else if (hasNotComp) {
                effectiveComp = EventType.NOT_COMPLETED;
            }

            // 3. Strict mathematical invariant: completion requires accepted status
            if (effectiveAcc != EventType.ACCEPTED) {
                effectiveComp = null;
            }
        }

        state.acceptanceDecision = effectiveAcc;
        state.completionDecision = effectiveComp;
        return true;
    }

    public OutcomeFeedbackEvidenceV2 buildEvidence(String userId, String targetTechnology, String targetDifficultyBand) {
        OutcomeFeedbackEvidenceV2 evidence = new OutcomeFeedbackEvidenceV2();
        evidence.setUserId(userId);
        String canTech = canonicalTech(targetTechnology);
        String canBand = canonicalBand(targetDifficultyBand);
        evidence.setTechnology(canTech);
        evidence.setDifficultyBand(canBand);

        Map<String, ExposureDecisionState> userMap = userExposureStates.get(userId != null ? userId.trim() : "");
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

        for (ExposureDecisionState s : userMap.values()) {
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
        userExposureStates.clear();
        auditLog.clear();
        seenEventIds.clear();
    }
}
