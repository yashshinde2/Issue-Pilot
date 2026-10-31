package com.issuepilot.feedback;

import com.issuepilot.model.EvidenceModelsV2.FreshnessEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.FreshnessEvidenceV2.FreshnessComputationResult;
import com.issuepilot.model.EvidenceModelsV2.OutcomeFeedbackEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.OutcomeFeedbackEvidenceV2.FeedbackComputationResult;
import com.issuepilot.model.EvidenceModelsV2.OutcomeFeedbackEvidenceV2.HierarchicalLevelResult;
import com.issuepilot.model.EvidenceModelsV2.OutcomeFeedbackEvidenceV2.HierarchyCounts;
import com.issuepilot.feedback.OutcomeFeedbackTracker.EventType;
import com.issuepilot.feedback.OutcomeFeedbackTracker.OutcomeEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Issue Pilot V2.3 Phase 6 — Outcome Feedback (F) & Freshness (N) Tests")
class OutcomeFeedbackAndFreshnessV23Test {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("Freshness half-life boundaries match exact mathematical specification")
    void testFreshnessHalfLifeBoundaries() {
        assertEquals(100.0, FreshnessEvidenceV2.calculateAgeScore(0.0), EPS);
        assertEquals(50.0, FreshnessEvidenceV2.calculateAgeScore(14.0), EPS);
        assertEquals(25.0, FreshnessEvidenceV2.calculateAgeScore(28.0), EPS);

        assertEquals(100.0, FreshnessEvidenceV2.calculateActivityScore(0.0), EPS);
        assertEquals(50.0, FreshnessEvidenceV2.calculateActivityScore(7.0), EPS);
        assertEquals(25.0, FreshnessEvidenceV2.calculateActivityScore(14.0), EPS);
    }

    @Test
    @DisplayName("Combined Freshness score matches Section 10 weights (40% Age, 60% Activity)")
    void testCombinedFreshness() {
        // (0, 0) -> 100.0
        FreshnessEvidenceV2 fn00 = new FreshnessEvidenceV2(0.0, 0.0);
        FreshnessComputationResult res00 = fn00.computeFreshness();
        assertTrue(res00.available());
        assertEquals(100.0, res00.score(), EPS);
        assertEquals(1.00, res00.coverageAn(), EPS);
        assertEquals(2, res00.signalCount());

        // (14, 7) -> 50.0
        FreshnessEvidenceV2 fn14_7 = new FreshnessEvidenceV2(14.0, 7.0);
        FreshnessComputationResult res14_7 = fn14_7.computeFreshness();
        assertEquals(50.0, res14_7.score(), EPS);
        assertEquals(1.00, res14_7.coverageAn(), EPS);

        // (14, 0) -> 0.40(50.0) + 0.60(100.0) = 80.0
        FreshnessEvidenceV2 fn14_0 = new FreshnessEvidenceV2(14.0, 0.0);
        FreshnessComputationResult res14_0 = fn14_0.computeFreshness();
        assertEquals(80.0, res14_0.score(), EPS);

        // (0, 7) -> 0.40(100.0) + 0.60(50.0) = 70.0
        FreshnessEvidenceV2 fn0_7 = new FreshnessEvidenceV2(0.0, 7.0);
        FreshnessComputationResult res0_7 = fn0_7.computeFreshness();
        assertEquals(70.0, res0_7.score(), EPS);
    }

    @Test
    @DisplayName("Bot-only activity and missing activity timestamp fallback to Age-only with 0.40 coverage")
    void testBotOnlyAndMissingActivity() {
        FreshnessEvidenceV2 fnBot = new FreshnessEvidenceV2(14.0, 0.0);
        fnBot.setIsBotActivity(true);
        FreshnessComputationResult resBot = fnBot.computeFreshness();
        assertTrue(resBot.available());
        assertEquals(50.0, resBot.score(), EPS);
        assertEquals(0.40, resBot.coverageAn(), EPS);
        assertEquals(1, resBot.signalCount());
        assertNotNull(resBot.ageScore());
        assertNull(resBot.activityScore());

        FreshnessEvidenceV2 fnNoAct = new FreshnessEvidenceV2();
        fnNoAct.setAgeDays(14.0);
        fnNoAct.setAgeAvailable(true);
        fnNoAct.setActivityAvailable(false);
        FreshnessComputationResult resNoAct = fnNoAct.computeFreshness();
        assertTrue(resNoAct.available());
        assertEquals(50.0, resNoAct.score(), EPS);
        assertEquals(0.40, resNoAct.coverageAn(), EPS);
    }

    @Test
    @DisplayName("Missing creation timestamp invalidates Freshness completely")
    void testMissingCreationTimestamp() {
        FreshnessEvidenceV2 fnNoAge = new FreshnessEvidenceV2();
        fnNoAge.setAgeAvailable(false);
        fnNoAge.setIdleDays(7.0);
        FreshnessComputationResult res = fnNoAge.computeFreshness();
        assertFalse(res.available());
        assertEquals(0.0, res.score(), EPS);
        assertEquals(0.0, res.coverageAn(), EPS);
    }

    @Test
    @DisplayName("Clock-skew tolerance clamps small future drift and rejects material future timestamps")
    void testFreshnessClockSkewTolerance() {
        FreshnessEvidenceV2 fnSkew = new FreshnessEvidenceV2(-0.0005, -0.0008);
        FreshnessComputationResult res = fnSkew.computeFreshness();
        assertTrue(res.available());
        assertEquals(100.0, res.score(), EPS);

        // Exceeding tolerance (> 0.001d / ~86.4s in future)
        assertThrows(IllegalArgumentException.class, () -> FreshnessEvidenceV2.calculateAgeScore(-0.002));
        assertThrows(IllegalArgumentException.class, () -> FreshnessEvidenceV2.calculateActivityScore(-0.002));
        assertThrows(IllegalArgumentException.class, () -> FreshnessEvidenceV2.calculateAgeScore(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> FreshnessEvidenceV2.calculateActivityScore(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("Freshness source-confidence caps are strictly enforced")
    void testFreshnessSourceConfidenceCaps() {
        FreshnessEvidenceV2 fn = new FreshnessEvidenceV2(0.0, 0.0);
        assertEquals(0.90, fn.computeFreshness("github", null).confidence(), EPS);
        assertEquals(0.85, fn.computeFreshness("gitlab", null).confidence(), EPS);
        assertEquals(0.80, fn.computeFreshness("codeberg", null).confidence(), EPS);
        assertEquals(0.70, fn.computeFreshness("bugzilla", null).confidence(), EPS);
        assertEquals(0.50, fn.computeFreshness("inferred", null).confidence(), EPS);
    }

    @Test
    @DisplayName("Feedback Beta(2,2) prior behavior and minimum 5-trial gating")
    void testFeedbackBeta22PriorAndTrialsGating() {
        // Level 1 only: 5 trials, 4 successes -> p1 = (4 + 4*0.5) / (5 + 4) = 6/9 = 2/3
        HierarchyCounts l1_5_4 = new HierarchyCounts(4, 5);
        HierarchicalLevelResult resL1 = OutcomeFeedbackEvidenceV2.evaluateHierarchy(null, null, l1_5_4);
        assertTrue(resL1.available());
        assertEquals("user_global", resL1.levelName());
        assertEquals(2.0 / 3.0, resL1.smoothedP(), EPS);

        // Fewer than 5 trials -> Unavailable
        HierarchyCounts l1_4_3 = new HierarchyCounts(3, 4);
        HierarchicalLevelResult resUnder5 = OutcomeFeedbackEvidenceV2.evaluateHierarchy(null, null, l1_4_3);
        assertFalse(resUnder5.available());
        assertEquals("none", resUnder5.levelName());
    }

    @Test
    @DisplayName("Feedback 4-level hierarchical backoff selects most specific level with >= 5 trials")
    void testHierarchicalBackoff() {
        // L1: 8/10 -> p1 = (8 + 2)/14 = 10/14 = 5/7
        // L2: 5/6 -> p2 = (5 + 4*(5/7))/10 = (55/7)/10 = 55/70 = 11/14
        // L3: 2/2 -> trials < 5, backoff to L2
        HierarchyCounts l1 = new HierarchyCounts(8, 10);
        HierarchyCounts l2 = new HierarchyCounts(5, 6);
        HierarchyCounts l3_low = new HierarchyCounts(2, 2);

        HierarchicalLevelResult resBackoff = OutcomeFeedbackEvidenceV2.evaluateHierarchy(l3_low, l2, l1);
        assertTrue(resBackoff.available());
        assertEquals("user_tech", resBackoff.levelName());
        assertEquals(11.0 / 14.0, resBackoff.smoothedP(), EPS);

        // L3 with 6/6 trials (>= 5) -> selects L3
        // p3 = (6 + 4*(11/14))/10 = (128/14)/10 = 128/140 = 32/35
        HierarchyCounts l3_ok = new HierarchyCounts(6, 6);
        HierarchicalLevelResult resL3 = OutcomeFeedbackEvidenceV2.evaluateHierarchy(l3_ok, l2, l1);
        assertTrue(resL3.available());
        assertEquals("user_tech_diff", resL3.levelName());
        assertEquals(32.0 / 35.0, resL3.smoothedP(), EPS);
    }

    @Test
    @DisplayName("Feedback available-weight normalization across acceptance-only, completion-only, and both")
    void testFeedbackOutcomeNormalization() {
        // Acceptance only: score = accScore, coverage = 0.40
        OutcomeFeedbackEvidenceV2 fbAccOnly = new OutcomeFeedbackEvidenceV2();
        fbAccOnly.setL1Accept(new HierarchyCounts(4, 5)); // 2/3
        fbAccOnly.setL1Complete(new HierarchyCounts(1, 3)); // < 5 trials -> unavailable
        FeedbackComputationResult resAcc = fbAccOnly.computeFeedback();
        assertTrue(resAcc.available());
        assertEquals(100.0 * 2.0 / 3.0, resAcc.score(), EPS);
        assertEquals(0.40, resAcc.coverageAf(), EPS);
        assertEquals(5.0 / 15.0, resAcc.confidence(), EPS);

        // Cohort subset invariant validation: completion cannot exceed acceptance (n_complete <= a_accept <= n_accept)
        OutcomeFeedbackEvidenceV2 fbBad1 = new OutcomeFeedbackEvidenceV2();
        fbBad1.setL1Accept(new HierarchyCounts(1, 2));
        fbBad1.setL1Complete(new HierarchyCounts(5, 5)); // 5 comp trials > 1 acc success
        assertThrows(IllegalArgumentException.class, fbBad1::computeFeedback);

        OutcomeFeedbackEvidenceV2 fbBad2 = new OutcomeFeedbackEvidenceV2();
        fbBad2.setL1Accept(new HierarchyCounts(4, 5));
        fbBad2.setL1Complete(new HierarchyCounts(5, 5)); // 5 comp trials > 4 acc successes
        assertThrows(IllegalArgumentException.class, fbBad2::computeFeedback);

        // Both available (consistent cohort: n_complete = 5 <= a_accept = 6 <= n_accept = 8):
        // acc: p1 = (6 + 2)/12 = 8/12 = 2/3 -> 66.6667
        // comp: p1 = (5 + 2)/9 = 7/9 -> 77.7778
        OutcomeFeedbackEvidenceV2 fbBoth = new OutcomeFeedbackEvidenceV2();
        fbBoth.setL1Accept(new HierarchyCounts(6, 8));
        fbBoth.setL1Complete(new HierarchyCounts(5, 5));
        FeedbackComputationResult resBoth = fbBoth.computeFeedback();
        assertTrue(resBoth.available());
        double expectedScore = 0.40 * (100.0 * 2.0 / 3.0) + 0.60 * (100.0 * 7.0 / 9.0);
        assertEquals(expectedScore, resBoth.score(), EPS);
        assertEquals(1.00, resBoth.coverageAf(), EPS);
    }

    @Test
    @DisplayName("OutcomeFeedbackTracker enforces deterministic conflict resolution, deduplication, and permutation invariance")
    void testOutcomeFeedbackTrackerDeterministicResolutionAndPermutations() {
        // 1. Event ID Deduplication (Idempotency)
        OutcomeFeedbackTracker trackerDedup = new OutcomeFeedbackTracker();
        Instant t1 = Instant.ofEpochMilli(1000L);
        OutcomeEvent e1 = new OutcomeEvent("e1", "user1", "gh:org/repo#1", "python", "EASY", EventType.ACCEPTED, t1, "c1", "w1");
        assertTrue(trackerDedup.recordEvent(e1));
        assertFalse(trackerDedup.recordEvent(e1), "Duplicate event ID must be rejected immediately (idempotency)");
        OutcomeFeedbackEvidenceV2 evDedup = trackerDedup.buildEvidence("user1", "python", "EASY");
        assertEquals(1, evDedup.getL1Accept().trials());
        assertEquals(1, evDedup.getL1Accept().successes());

        // 2. ACCEPTED(T=100) and REJECTED(T=200): later event wins under all arrival permutations
        // Forward: [ACCEPTED, REJECTED]
        OutcomeFeedbackTracker trAr1 = new OutcomeFeedbackTracker();
        OutcomeEvent evAcc1 = new OutcomeEvent("ev_acc", "u1", "gh:r#1", "python", "EASY", EventType.ACCEPTED, Instant.ofEpochMilli(100L), "c", "w");
        OutcomeEvent evRej1 = new OutcomeEvent("ev_rej", "u1", "gh:r#1", "python", "EASY", EventType.REJECTED, Instant.ofEpochMilli(200L), "c", "w");
        assertTrue(trAr1.recordEvent(evAcc1));
        assertTrue(trAr1.recordEvent(evRej1));
        OutcomeFeedbackEvidenceV2 evAr1 = trAr1.buildEvidence("u1", "python", "EASY");

        // Reverse: [REJECTED, ACCEPTED]
        OutcomeFeedbackTracker trAr2 = new OutcomeFeedbackTracker();
        assertTrue(trAr2.recordEvent(evRej1));
        assertTrue(trAr2.recordEvent(evAcc1));
        OutcomeFeedbackEvidenceV2 evAr2 = trAr2.buildEvidence("u1", "python", "EASY");

        assertEquals(1, evAr1.getL1Accept().trials(), "Must have exactly 1 trial");
        assertEquals(0, evAr1.getL1Accept().successes(), "T=200 REJECTED must supersede T=100 ACCEPTED");
        assertEquals(evAr1.getL1Accept().trials(), evAr2.getL1Accept().trials());
        assertEquals(evAr1.getL1Accept().successes(), evAr2.getL1Accept().successes());

        // 3. REJECTED(T=100) and ACCEPTED(T=200): later event wins under all arrival permutations
        OutcomeFeedbackTracker trRa1 = new OutcomeFeedbackTracker();
        OutcomeEvent evRej2 = new OutcomeEvent("ev_rej2", "u1", "gh:r#2", "python", "EASY", EventType.REJECTED, Instant.ofEpochMilli(100L), "c", "w");
        OutcomeEvent evAcc2 = new OutcomeEvent("ev_acc2", "u1", "gh:r#2", "python", "EASY", EventType.ACCEPTED, Instant.ofEpochMilli(200L), "c", "w");
        assertTrue(trRa1.recordEvent(evRej2));
        assertTrue(trRa1.recordEvent(evAcc2));
        OutcomeFeedbackEvidenceV2 evRa1 = trRa1.buildEvidence("u1", "python", "EASY");

        OutcomeFeedbackTracker trRa2 = new OutcomeFeedbackTracker();
        assertTrue(trRa2.recordEvent(evAcc2));
        assertTrue(trRa2.recordEvent(evRej2));
        OutcomeFeedbackEvidenceV2 evRa2 = trRa2.buildEvidence("u1", "python", "EASY");

        assertEquals(1, evRa1.getL1Accept().trials(), "Must have exactly 1 trial");
        assertEquals(1, evRa1.getL1Accept().successes(), "T=200 ACCEPTED must supersede T=100 REJECTED");
        assertEquals(evRa1.getL1Accept().trials(), evRa2.getL1Accept().trials());
        assertEquals(evRa1.getL1Accept().successes(), evRa2.getL1Accept().successes());

        // 4. Duplicate ACCEPTED with different event IDs: exactly 1 trial (no trial inflation)
        OutcomeFeedbackTracker trDupAcc1 = new OutcomeFeedbackTracker();
        OutcomeEvent dAcc1 = new OutcomeEvent("d_acc1", "u1", "gh:r#3", "python", "EASY", EventType.ACCEPTED, Instant.ofEpochMilli(100L), "c", "w");
        OutcomeEvent dAcc2 = new OutcomeEvent("d_acc2", "u1", "gh:r#3", "python", "EASY", EventType.ACCEPTED, Instant.ofEpochMilli(200L), "c", "w");
        assertTrue(trDupAcc1.recordEvent(dAcc1));
        assertTrue(trDupAcc1.recordEvent(dAcc2));
        OutcomeFeedbackEvidenceV2 evDupAcc1 = trDupAcc1.buildEvidence("u1", "python", "EASY");

        OutcomeFeedbackTracker trDupAcc2 = new OutcomeFeedbackTracker();
        assertTrue(trDupAcc2.recordEvent(dAcc2));
        assertTrue(trDupAcc2.recordEvent(dAcc1));
        OutcomeFeedbackEvidenceV2 evDupAcc2 = trDupAcc2.buildEvidence("u1", "python", "EASY");

        assertEquals(1, evDupAcc1.getL1Accept().trials(), "Duplicate ACCEPTED must produce exactly 1 trial");
        assertEquals(1, evDupAcc1.getL1Accept().successes());
        assertEquals(evDupAcc1.getL1Accept().trials(), evDupAcc2.getL1Accept().trials());
        assertEquals(evDupAcc1.getL1Accept().successes(), evDupAcc2.getL1Accept().successes());

        // 5. Duplicate REJECTED with different event IDs: exactly 1 trial (no trial inflation)
        OutcomeFeedbackTracker trDupRej1 = new OutcomeFeedbackTracker();
        OutcomeEvent dRej1 = new OutcomeEvent("d_rej1", "u1", "gh:r#4", "python", "EASY", EventType.REJECTED, Instant.ofEpochMilli(100L), "c", "w");
        OutcomeEvent dRej2 = new OutcomeEvent("d_rej2", "u1", "gh:r#4", "python", "EASY", EventType.REJECTED, Instant.ofEpochMilli(200L), "c", "w");
        assertTrue(trDupRej1.recordEvent(dRej1));
        assertTrue(trDupRej1.recordEvent(dRej2));
        OutcomeFeedbackEvidenceV2 evDupRej1 = trDupRej1.buildEvidence("u1", "python", "EASY");

        OutcomeFeedbackTracker trDupRej2 = new OutcomeFeedbackTracker();
        assertTrue(trDupRej2.recordEvent(dRej2));
        assertTrue(trDupRej2.recordEvent(dRej1));
        OutcomeFeedbackEvidenceV2 evDupRej2 = trDupRej2.buildEvidence("u1", "python", "EASY");

        assertEquals(1, evDupRej1.getL1Accept().trials(), "Duplicate REJECTED must produce exactly 1 trial");
        assertEquals(0, evDupRej1.getL1Accept().successes());
        assertEquals(evDupRej1.getL1Accept().trials(), evDupRej2.getL1Accept().trials());
        assertEquals(evDupRej1.getL1Accept().successes(), evDupRej2.getL1Accept().successes());

        // 6. Interleaved acceptance conflict, completion, and REOPENED under multiple arrival permutations
        OutcomeEvent evA = new OutcomeEvent("eA", "u1", "gh:r#5", "python", "EASY", EventType.ACCEPTED, Instant.ofEpochMilli(100L), "c", "w");
        OutcomeEvent evC1 = new OutcomeEvent("eC1", "u1", "gh:r#5", "python", "EASY", EventType.COMPLETED, Instant.ofEpochMilli(200L), "c", "w");
        OutcomeEvent evR = new OutcomeEvent("eR", "u1", "gh:r#5", "python", "EASY", EventType.REOPENED, Instant.ofEpochMilli(300L), "c", "w");
        OutcomeEvent evC2 = new OutcomeEvent("eC2", "u1", "gh:r#5", "python", "EASY", EventType.COMPLETED, Instant.ofEpochMilli(400L), "c", "w");
        OutcomeEvent evX = new OutcomeEvent("eX", "u1", "gh:r#5", "python", "EASY", EventType.REJECTED, Instant.ofEpochMilli(500L), "c", "w");

        List<OutcomeEvent> perm1 = List.of(evA, evC1, evR, evC2, evX); // Forward
        List<OutcomeEvent> perm2 = List.of(evX, evC2, evR, evC1, evA); // Reverse
        List<OutcomeEvent> perm3 = List.of(evR, evA, evX, evC2, evC1); // Scrambled

        for (List<OutcomeEvent> perm : List.of(perm1, perm2, perm3)) {
            OutcomeFeedbackTracker trP = new OutcomeFeedbackTracker();
            for (OutcomeEvent ev : perm) {
                assertTrue(trP.recordEvent(ev));
            }
            OutcomeFeedbackEvidenceV2 evP = trP.buildEvidence("u1", "python", "EASY");
            assertEquals(1, evP.getL1Accept().trials());
            assertEquals(1, evP.getL1Accept().successes(), "Retroactive REJECTED after completion must be ignored");
            assertEquals(1, evP.getL1Complete().trials());
            assertEquals(1, evP.getL1Complete().successes(), "Reopening then re-completing must yield exactly 1 completed trial");
        }

        // 7. Same-time conflicting events with stable tie-breaking by event ID
        OutcomeEvent evtA = new OutcomeEvent("evt_a", "u1", "gh:r#6", "python", "EASY", EventType.ACCEPTED, Instant.ofEpochMilli(100L), "c", "w");
        OutcomeEvent evtB = new OutcomeEvent("evt_b", "u1", "gh:r#6", "python", "EASY", EventType.REJECTED, Instant.ofEpochMilli(100L), "c", "w");

        OutcomeFeedbackTracker trSt1 = new OutcomeFeedbackTracker();
        assertTrue(trSt1.recordEvent(evtA));
        assertTrue(trSt1.recordEvent(evtB));
        OutcomeFeedbackEvidenceV2 evSt1 = trSt1.buildEvidence("u1", "python", "EASY");

        OutcomeFeedbackTracker trSt2 = new OutcomeFeedbackTracker();
        assertTrue(trSt2.recordEvent(evtB));
        assertTrue(trSt2.recordEvent(evtA));
        OutcomeFeedbackEvidenceV2 evSt2 = trSt2.buildEvidence("u1", "python", "EASY");

        assertEquals(1, evSt1.getL1Accept().trials());
        assertEquals(0, evSt1.getL1Accept().successes());
        assertEquals(evSt1.getL1Accept().trials(), evSt2.getL1Accept().trials());
        assertEquals(evSt1.getL1Accept().successes(), evSt2.getL1Accept().successes());

        // 8. Missing ordering metadata fallback
        OutcomeEvent mA = new OutcomeEvent("m_a", "u1", "gh:r#7", "python", "EASY", EventType.ACCEPTED, null, "c", "w");
        OutcomeEvent mB = new OutcomeEvent("m_b", "u1", "gh:r#7", "python", "EASY", EventType.REJECTED, null, "c", "w");

        OutcomeFeedbackTracker trM1 = new OutcomeFeedbackTracker();
        assertTrue(trM1.recordEvent(mA));
        assertTrue(trM1.recordEvent(mB));
        OutcomeFeedbackEvidenceV2 evM1 = trM1.buildEvidence("u1", "python", "EASY");

        OutcomeFeedbackTracker trM2 = new OutcomeFeedbackTracker();
        assertTrue(trM2.recordEvent(mB));
        assertTrue(trM2.recordEvent(mA));
        OutcomeFeedbackEvidenceV2 evM2 = trM2.buildEvidence("u1", "python", "EASY");

        assertEquals(1, evM1.getL1Accept().trials());
        assertEquals(0, evM1.getL1Accept().successes());
        assertEquals(evM1.getL1Accept().trials(), evM2.getL1Accept().trials());
        assertEquals(evM1.getL1Accept().successes(), evM2.getL1Accept().successes());
    }

    @Test
    @DisplayName("Randomized property tests for Freshness and Hierarchical Feedback")
    void testRandomizedPropertyTesting() {
        Random rnd = new Random(8888);
        for (int i = 0; i < 10000; i++) {
            double age = rnd.nextDouble() * 500.0;
            double idle = rnd.nextDouble() * 500.0;
            FreshnessEvidenceV2 fn = new FreshnessEvidenceV2(age, idle);
            FreshnessComputationResult r = fn.computeFreshness();
            assertTrue(r.score() >= 0.0 && r.score() <= 100.0);
            assertTrue(r.confidence() >= 0.0 && r.confidence() <= 1.0);
            assertTrue(r.available());

            // Monotonicity checks
            assertTrue(FreshnessEvidenceV2.calculateAgeScore(age + 1.0) <= FreshnessEvidenceV2.calculateAgeScore(age)
                    + 1e-9);
            assertTrue(FreshnessEvidenceV2
                    .calculateActivityScore(idle + 1.0) <= FreshnessEvidenceV2.calculateActivityScore(idle) + 1e-9);

            // Random hierarchy trials
            int t1 = rnd.nextInt(25);
            int s1 = t1 > 0 ? rnd.nextInt(t1 + 1) : 0;
            int t2 = t1 > 0 ? rnd.nextInt(t1 + 1) : 0;
            int s2 = t2 > 0 ? rnd.nextInt(Math.min(s1, t2) + 1) : 0;
            int t3 = t2 > 0 ? rnd.nextInt(t2 + 1) : 0;
            int s3 = t3 > 0 ? rnd.nextInt(Math.min(s2, t3) + 1) : 0;

            HierarchyCounts l1 = new HierarchyCounts(s1, t1);
            HierarchyCounts l2 = new HierarchyCounts(s2, t2);
            HierarchyCounts l3 = new HierarchyCounts(s3, t3);

            // Invariant assertion: completions <= accepted successes <= accepted trials
            if (i % 100 == 0) {
                OutcomeFeedbackTracker propTracker = new OutcomeFeedbackTracker();
                for (int ev = 0; ev < 50; ev++) {
                    String ik = "gh:repo#" + (ev % 10);
                    EventType[] types = {EventType.ACCEPTED, EventType.REJECTED, EventType.COMPLETED, EventType.NOT_COMPLETED, EventType.REOPENED};
                    EventType et = types[rnd.nextInt(types.length)];
                    propTracker.recordEvent(new OutcomeEvent("p_" + i + "_" + ev, "u1", ik, "python", "EASY", et, Instant.now(), "c", "w"));
                }
                OutcomeFeedbackEvidenceV2 propEv = propTracker.buildEvidence("u1", "python", "EASY");
                assertTrue(propEv.getL1Complete().trials() <= propEv.getL1Accept().successes(),
                        "L1 Subset invariant: n_complete <= a_accept");
                assertTrue(propEv.getL1Accept().successes() <= propEv.getL1Accept().trials(),
                        "L1 Subset invariant: a_accept <= n_accept");
                assertTrue(propEv.getL2Complete().trials() <= propEv.getL2Accept().successes(),
                        "L2 Subset invariant: n_complete <= a_accept");
                assertTrue(propEv.getL3Complete().trials() <= propEv.getL3Accept().successes(),
                        "L3 Subset invariant: n_complete <= a_accept");
            }

            HierarchicalLevelResult hRes = OutcomeFeedbackEvidenceV2.evaluateHierarchy(l3, l2, l1);
            if (t1 < 5) {
                assertFalse(hRes.available());
            } else {
                assertTrue(hRes.available());
                assertTrue(hRes.smoothedP() >= 0.0 && hRes.smoothedP() <= 1.0);
                if (t3 >= 5) {
                    assertEquals("user_tech_diff", hRes.levelName());
                } else if (t2 >= 5) {
                    assertEquals("user_tech", hRes.levelName());
                } else {
                    assertEquals("user_global", hRes.levelName());
                }
            }
        }
    }
}