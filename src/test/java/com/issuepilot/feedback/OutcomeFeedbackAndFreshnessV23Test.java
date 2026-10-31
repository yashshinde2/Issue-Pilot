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

        // Completion only: score = compScore, coverage = 0.60
        OutcomeFeedbackEvidenceV2 fbCompOnly = new OutcomeFeedbackEvidenceV2();
        fbCompOnly.setL1Accept(new HierarchyCounts(1, 2)); // < 5 trials -> unavailable
        fbCompOnly.setL1Complete(new HierarchyCounts(5, 5)); // (5+2)/9 = 7/9
        FeedbackComputationResult resComp = fbCompOnly.computeFeedback();
        assertTrue(resComp.available());
        assertEquals(100.0 * 7.0 / 9.0, resComp.score(), EPS);
        assertEquals(0.60, resComp.coverageAf(), EPS);

        // Both available: 0.40 * (66.6667) + 0.60 * (77.7778) = 73.3333
        OutcomeFeedbackEvidenceV2 fbBoth = new OutcomeFeedbackEvidenceV2();
        fbBoth.setL1Accept(new HierarchyCounts(4, 5));
        fbBoth.setL1Complete(new HierarchyCounts(5, 5));
        FeedbackComputationResult resBoth = fbBoth.computeFeedback();
        assertTrue(resBoth.available());
        double expectedScore = 0.40 * (100.0 * 2.0 / 3.0) + 0.60 * (100.0 * 7.0 / 9.0);
        assertEquals(expectedScore, resBoth.score(), EPS);
        assertEquals(1.00, resBoth.coverageAf(), EPS);
    }

    @Test
    @DisplayName("OutcomeFeedbackTracker enforces deduplication, idempotency, and out-of-order resolution")
    void testOutcomeFeedbackTrackerDeduplicationAndOrder() {
        OutcomeFeedbackTracker tracker = new OutcomeFeedbackTracker();

        // 1. First ACCEPTED
        OutcomeEvent e1 = new OutcomeEvent("e1", "user1", "gh:org/repo#1", "python", "EASY", EventType.ACCEPTED,
                Instant.now(), "cohort1", "win1");
        assertTrue(tracker.recordEvent(e1));

        // 2. Duplicate ACCEPTED ignored
        OutcomeEvent e2 = new OutcomeEvent("e2", "user1", "gh:org/repo#1", "python", "EASY", EventType.ACCEPTED,
                Instant.now(), "cohort1", "win1");
        assertFalse(tracker.recordEvent(e2));

        // 3. Conflicting REJECTED ignored
        OutcomeEvent e3 = new OutcomeEvent("e3", "user1", "gh:org/repo#1", "python", "EASY", EventType.REJECTED,
                Instant.now(), "cohort1", "win1");
        assertFalse(tracker.recordEvent(e3));

        // 4. Out-of-order: COMPLETED without prior ACCEPTED auto-registers ACCEPTED
        OutcomeEvent e4 = new OutcomeEvent("e4", "user1", "gh:org/repo#2", "python", "EASY", EventType.COMPLETED,
                Instant.now(), "cohort1", "win1");
        assertTrue(tracker.recordEvent(e4));

        // 5. Duplicate COMPLETED ignored
        OutcomeEvent e5 = new OutcomeEvent("e5", "user1", "gh:org/repo#2", "python", "EASY", EventType.COMPLETED,
                Instant.now(), "cohort1", "win1");
        assertFalse(tracker.recordEvent(e5));

        OutcomeFeedbackEvidenceV2 ev = tracker.buildEvidence("user1", "python", "EASY");
        assertEquals(2, ev.getL1Accept().trials());
        assertEquals(2, ev.getL1Accept().successes());
        assertEquals(1, ev.getL1Complete().trials());
        assertEquals(1, ev.getL1Complete().successes());
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