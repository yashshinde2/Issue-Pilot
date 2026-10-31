package com.issuepilot.responsiveness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.EvidenceModelsV2.RepositoryHealthEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.RepositoryHealthEvidenceV2.HealthComputationResult;
import com.issuepilot.model.EvidenceModelsV2.ResponsivenessEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.ResponsivenessEvidenceV2.ResponsivenessComputationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 5: Maintainer Responsiveness (R) and Repository Health (H) Tests")
class ResponsivenessAndHealthV23Test {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Load and execute all 10 Golden Responsiveness & Health Fixtures from disk")
    void testGoldenFixturesFromDisk() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/fixtures/golden_responsiveness_health_v23_fixtures.json");
        assertNotNull(stream, "golden_responsiveness_health_v23_fixtures.json must be readable from classpath");

        JsonNode root = objectMapper.readTree(stream);
        assertEquals("2.3.0", root.get("schema_version").asText());
        assertEquals("2.3.0", root.get("algorithm_version").asText());
        assertTrue(root.has("fixtures"), "Must contain fixtures array");

        JsonNode fixtures = root.get("fixtures");
        assertTrue(fixtures.isArray());
        assertEquals(10, fixtures.size());

        // Case 1: Full R and H
        JsonNode f1 = fixtures.get(0);
        assertEquals("rh_case_1_full_r_and_h", f1.get("id").asText());
        ResponsivenessEvidenceV2 r1 = new ResponsivenessEvidenceV2();
        r1.setResponseTimeScore(80.0); r1.setRtSamples(10); r1.setRtAvailable(true);
        r1.setResponseRateScore(75.0); r1.setRrSamples(8); r1.setRrAvailable(true);
        r1.setPrReviewScore(70.0); r1.setPrSamples(6); r1.setPrAvailable(true);
        r1.setRecentMaintainerAct(60.0); r1.setMaSamples(5); r1.setMaAvailable(true);

        ResponsivenessComputationResult resR1 = r1.computeResponsiveness();
        assertTrue(resR1.available());
        assertEquals(73.0, resR1.score(), 1e-4);
        assertEquals(1.0, resR1.coverageAr(), 1e-6);
        assertEquals(1.0, resR1.confidence(), 1e-6);

        RepositoryHealthEvidenceV2 h1 = new RepositoryHealthEvidenceV2();
        h1.setC90Score(100.0); h1.setC90Available(true);
        h1.setPr30Score(80.0); h1.setPr30Available(true);
        h1.setAu90Score(60.0); h1.setAu90Available(true);
        h1.setMa90Score(40.0); h1.setMa90Available(true);

        HealthComputationResult resH1 = h1.computeHealth();
        assertTrue(resH1.available());
        assertEquals(73.0, resH1.score(), 1e-4);
        assertEquals(1.0, resH1.coverageAh(), 1e-6);
        assertEquals(1.0, resH1.confidence(), 1e-6);

        // Case 2: R sample gate N >= 5
        JsonNode f2 = fixtures.get(1);
        assertEquals("rh_case_2_r_sample_gate_5_vs_4", f2.get("id").asText());
        ResponsivenessEvidenceV2 r2 = new ResponsivenessEvidenceV2();
        r2.setResponseTimeScore(80.0); r2.setRtSamples(5); r2.setRtAvailable(true);
        r2.setResponseRateScore(70.0); r2.setRrSamples(5); r2.setRrAvailable(true);
        r2.setPrReviewScore(90.0); r2.setPrSamples(4); r2.setPrAvailable(false);
        r2.setRecentMaintainerAct(85.0); r2.setMaSamples(3); r2.setMaAvailable(false);

        ResponsivenessComputationResult resR2 = r2.computeResponsiveness();
        assertTrue(resR2.available());
        assertEquals(75.0, resR2.score(), 1e-4);
        assertEquals(0.60, resR2.coverageAr(), 1e-6);
        assertEquals(2, resR2.signalCount());

        // Case 3: R coverage boundary 0.45 vs 0.40
        JsonNode f3 = fixtures.get(2);
        assertEquals("rh_case_3_r_coverage_boundary_045", f3.get("id").asText());
        ResponsivenessEvidenceV2 r3Pass = new ResponsivenessEvidenceV2();
        r3Pass.setResponseTimeScore(80.0); r3Pass.setRtSamples(5); r3Pass.setRtAvailable(true);
        r3Pass.setRecentMaintainerAct(60.0); r3Pass.setMaSamples(5); r3Pass.setMaAvailable(true);

        ResponsivenessComputationResult resR3Pass = r3Pass.computeResponsiveness();
        assertTrue(resR3Pass.available());
        assertEquals(0.45, resR3Pass.coverageAr(), 1e-6);
        assertEquals(73.333333, resR3Pass.score(), 1e-4);

        ResponsivenessEvidenceV2 r3Fail = new ResponsivenessEvidenceV2();
        r3Fail.setPrReviewScore(80.0); r3Fail.setPrSamples(5); r3Fail.setPrAvailable(true);
        r3Fail.setRecentMaintainerAct(60.0); r3Fail.setMaSamples(5); r3Fail.setMaAvailable(true);

        ResponsivenessComputationResult resR3Fail = r3Fail.computeResponsiveness();
        assertFalse(resR3Fail.available());
        assertEquals(0.40, resR3Fail.coverageAr(), 1e-6);

        // Case 4: R signal count gate 2 vs 1
        JsonNode f4 = fixtures.get(3);
        assertEquals("rh_case_4_r_signal_count_gate_2_vs_1", f4.get("id").asText());
        ResponsivenessEvidenceV2 r4 = new ResponsivenessEvidenceV2();
        r4.setResponseTimeScore(85.0); r4.setRtSamples(20); r4.setRtAvailable(true);

        ResponsivenessComputationResult resR4 = r4.computeResponsiveness();
        assertFalse(resR4.available());
        assertEquals(1, resR4.signalCount());
        assertEquals(0.30, resR4.coverageAr(), 1e-6);

        // Case 5: H coverage and renormalization over 0.80
        JsonNode f5 = fixtures.get(4);
        assertEquals("rh_case_5_h_coverage_and_renormalization", f5.get("id").asText());
        RepositoryHealthEvidenceV2 h5 = new RepositoryHealthEvidenceV2();
        h5.setC90Score(100.0); h5.setC90Available(true);
        h5.setPr30Score(80.0); h5.setPr30Available(true);
        h5.setAu90Score(60.0); h5.setAu90Available(true);
        h5.setMa90Available(false);

        HealthComputationResult resH5 = h5.computeHealth();
        assertTrue(resH5.available());
        assertEquals(0.80, resH5.coverageAh(), 1e-6);
        assertEquals(81.25, resH5.score(), 1e-4);
        assertEquals(3, resH5.signalCount());

        // Case 6: H signal count gate 2 vs 1
        JsonNode f6 = fixtures.get(5);
        assertEquals("rh_case_6_h_signal_count_gate_2_vs_1", f6.get("id").asText());
        RepositoryHealthEvidenceV2 h6 = new RepositoryHealthEvidenceV2();
        h6.setC90Score(100.0); h6.setC90Available(true);
        h6.setPr30Available(false); h6.setAu90Available(false); h6.setMa90Available(false);

        HealthComputationResult resH6 = h6.computeHealth();
        assertFalse(resH6.available());
        assertEquals(1, resH6.signalCount());
        assertEquals(0.30, resH6.coverageAh(), 1e-6);

        // Case 7: RR mature cohort and right-censoring
        JsonNode f7 = fixtures.get(6);
        assertEquals("rh_case_7_rr_mature_cohort_and_right_censoring", f7.get("id").asText());
        double rrScore = ResponsivenessEvidenceV2.calculateRrScore(3, 5);
        assertEquals(60.0, rrScore, 1e-6);

        // Case 8: Author self-reply and bot exclusion (first maintainer reply at 36h)
        JsonNode f8 = fixtures.get(7);
        assertEquals("rh_case_8_author_self_reply_and_bot_exclusion", f8.get("id").asText());
        double rtScore = ResponsivenessEvidenceV2.calculateRtScore(36.0);
        assertEquals(57.142857, rtScore, 1e-4);

        // Case 9: Window filtering H
        JsonNode f9 = fixtures.get(8);
        assertEquals("rh_case_9_window_filtering_h", f9.get("id").asText());
        double c90 = RepositoryHealthEvidenceV2.calculateC90Score(10);
        double pr30 = RepositoryHealthEvidenceV2.calculatePr30Score(6);
        double au90 = RepositoryHealthEvidenceV2.calculateAu90Score(5);
        double ma90 = RepositoryHealthEvidenceV2.calculateMa90Score(15.0);

        assertEquals(58.330548, c90, 1e-4);
        assertEquals(75.0, pr30, 1e-4);
        assertEquals(83.333333, au90, 1e-4);
        assertEquals(89.089872, ma90, 1e-4);

        RepositoryHealthEvidenceV2 h9 = new RepositoryHealthEvidenceV2();
        h9.setC90Score(c90); h9.setC90Available(true);
        h9.setPr30Score(pr30); h9.setPr30Available(true);
        h9.setAu90Score(au90); h9.setAu90Available(true);
        h9.setMa90Score(ma90); h9.setMa90Available(true);

        HealthComputationResult resH9 = h9.computeHealth();
        assertEquals(74.900472, resH9.score(), 1e-4);

        // Case 10: Bugzilla strict unavailability
        JsonNode f10 = fixtures.get(9);
        assertEquals("rh_case_10_bugzilla_strict_unavailability", f10.get("id").asText());
        ResponsivenessEvidenceV2 r10 = new ResponsivenessEvidenceV2();
        r10.setResponseTimeScore(80.0); r10.setRtSamples(10); r10.setRtAvailable(true);
        r10.setResponseRateScore(80.0); r10.setRrSamples(10); r10.setRrAvailable(true);
        r10.setRoleMappingVerified(false);
        assertFalse(r10.computeResponsiveness().available());

        RepositoryHealthEvidenceV2 h10 = new RepositoryHealthEvidenceV2();
        h10.setC90Score(80.0); h10.setC90Available(true);
        h10.setPr30Score(80.0); h10.setPr30Available(true);
        h10.setCodeHostAvailable(false);
        assertFalse(h10.computeHealth().available());
    }

    @Test
    @DisplayName("Verify component mathematical formulas at nominal midpoints")
    void testMathematicalMidpoints() {
        assertEquals(50.0, ResponsivenessEvidenceV2.calculateRtScore(48.0), 1e-6);
        assertEquals(50.0, ResponsivenessEvidenceV2.calculateRrScore(4, 8), 1e-6);
        assertEquals(50.0, ResponsivenessEvidenceV2.calculatePrScore(72.0), 1e-6);
        assertEquals(50.0, ResponsivenessEvidenceV2.calculateMaScore(14.0), 1e-6);

        assertEquals(100.0, RepositoryHealthEvidenceV2.calculateC90Score(60), 1e-6);
        assertEquals(100.0, RepositoryHealthEvidenceV2.calculatePr30Score(8), 1e-6);
        assertEquals(100.0, RepositoryHealthEvidenceV2.calculateAu90Score(6), 1e-6);
        assertEquals(50.0, RepositoryHealthEvidenceV2.calculateMa90Score(90.0), 1e-6);
    }

    @Test
    @DisplayName("Verify sample count gate boundaries (N >= 5)")
    void testSampleCountGateBoundaries() {
        ResponsivenessEvidenceV2 r = new ResponsivenessEvidenceV2();
        r.setResponseTimeScore(80.0);
        r.setRtSamples(4); // rejected
        r.setRtAvailable(true);

        r.setResponseRateScore(80.0);
        r.setRrSamples(5); // accepted
        r.setRrAvailable(true);

        r.setPrReviewScore(80.0);
        r.setPrSamples(5); // accepted
        r.setPrAvailable(true);

        ResponsivenessComputationResult res = r.computeResponsiveness();
        assertTrue(res.available());
        assertEquals(2, res.signalCount());
        assertEquals(0.55, res.coverageAr(), 1e-6);
        assertEquals(80.0, res.score(), 1e-6);
    }

    @Test
    @DisplayName("Verify pagination incompleteness invalidates Responsiveness")
    void testPaginationIncomplete() {
        ResponsivenessEvidenceV2 r = new ResponsivenessEvidenceV2();
        r.setResponseTimeScore(80.0); r.setRtSamples(10); r.setRtAvailable(true);
        r.setResponseRateScore(80.0); r.setRrSamples(10); r.setRrAvailable(true);
        r.setPaginationComplete(false);

        ResponsivenessComputationResult res = r.computeResponsiveness();
        assertFalse(res.available());
        assertTrue(res.explanation().contains("pagination incomplete"));
    }

    @Test
    @DisplayName("Verify strict rejection of invalid and non-finite confidence inputs")
    void testConfidenceValidation() {
        ResponsivenessEvidenceV2 r = new ResponsivenessEvidenceV2();
        r.setResponseTimeScore(80.0); r.setRtSamples(10); r.setRtAvailable(true);
        r.setResponseRateScore(80.0); r.setRrSamples(10); r.setRrAvailable(true);

        r.setRtConfidence(1.2);
        assertThrows(IllegalArgumentException.class, r::computeResponsiveness);

        r.setRtConfidence(-0.1);
        assertThrows(IllegalArgumentException.class, r::computeResponsiveness);

        r.setRtConfidence(Double.NaN);
        assertThrows(IllegalArgumentException.class, r::computeResponsiveness);

        RepositoryHealthEvidenceV2 h = new RepositoryHealthEvidenceV2();
        h.setC90Score(80.0); h.setC90Available(true);
        h.setPr30Score(80.0); h.setPr30Available(true);

        h.setC90Confidence(1.5);
        assertThrows(IllegalArgumentException.class, h::computeHealth);

        h.setC90Confidence(-0.5);
        assertThrows(IllegalArgumentException.class, h::computeHealth);

        h.setC90Confidence(Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, h::computeHealth);
    }

    @Test
    @DisplayName("Verify rejection of negative durations and handling of clock skew")
    void testNegativeDurationsAndClockSkew() {
        assertThrows(IllegalArgumentException.class, () -> ResponsivenessEvidenceV2.calculateRtScore(-5.0));
        assertThrows(IllegalArgumentException.class, () -> ResponsivenessEvidenceV2.calculatePrScore(-10.0));

        // Tolerated negative duration / clock skew clamped to zero in recency
        assertEquals(100.0, ResponsivenessEvidenceV2.calculateMaScore(-0.01), 1e-6);
        assertEquals(100.0, RepositoryHealthEvidenceV2.calculateMa90Score(-0.05), 1e-6);
    }

    @Test
    @DisplayName("Verify confidence aggregation and non-default propagation (C_R, C_H)")
    void testConfidenceAggregationAndPropagation() {
        ResponsivenessEvidenceV2 r = new ResponsivenessEvidenceV2();
        r.setResponseTimeScore(80.0);
        r.setRtSamples(8);
        r.setRtAvailable(true);
        r.setRtConfidence(0.80);

        r.setResponseRateScore(70.0);
        r.setRrSamples(6);
        r.setRrAvailable(true);
        r.setRrConfidence(0.70);

        ResponsivenessComputationResult resR = r.computeResponsiveness();
        assertTrue(resR.available());
        assertEquals(75.0, resR.score(), 1e-4);
        assertEquals(0.60, resR.coverageAr(), 1e-6);
        // C_R = 0.30 * 0.80 + 0.30 * 0.70 = 0.24 + 0.21 = 0.45
        assertEquals(0.45, resR.confidence(), 1e-4);

        RepositoryHealthEvidenceV2 h = new RepositoryHealthEvidenceV2();
        h.setC90Score(100.0);
        h.setC90Available(true);
        h.setC90Confidence(0.90);

        h.setPr30Score(80.0);
        h.setPr30Available(true);
        h.setPr30Confidence(0.80);

        HealthComputationResult resH = h.computeHealth();
        assertTrue(resH.available());
        // C_H = 0.30 * 0.90 + 0.25 * 0.80 = 0.27 + 0.20 = 0.47
        assertEquals(0.47, resH.confidence(), 1e-4);
        assertEquals(0.55, resH.coverageAh(), 1e-6);
        // Score = (0.30*100 + 0.25*80) / 0.55 = 50 / 0.55 = 90.909091
        assertEquals(90.909091, resH.score(), 1e-4);
    }

    @Test
    @DisplayName("Verify empty repository with complete queries yields known zero, not unavailable")
    void testEmptyRepositoryVsUnavailableData() {
        // Complete queries returning 0 commits, 0 PRs, 0 authors, 365d idle
        RepositoryHealthEvidenceV2 emptyRepo = RepositoryHealthEvidenceV2.fromMeasurements(0, 0, 0, 365.0);
        HealthComputationResult res = emptyRepo.computeHealth();

        assertTrue(res.available(), "Complete queries proving zero activity must be available with known zero");
        assertEquals(0.0, emptyRepo.getC90Score(), 1e-6);
        assertEquals(0.0, emptyRepo.getPr30Score(), 1e-6);
        assertEquals(0.0, emptyRepo.getAu90Score(), 1e-6);
        assertTrue(emptyRepo.getMa90Score() < 7.0); // 100 * 0.5^(365/90) ~ 6.0136

        // Conversely, missing repository measurements must be unavailable
        RepositoryHealthEvidenceV2 missingRepo = new RepositoryHealthEvidenceV2();
        missingRepo.setC90Available(false);
        missingRepo.setPr30Available(false);
        assertFalse(missingRepo.computeHealth().available(), "Missing data must be unavailable, not zero");
    }

    @Test
    @DisplayName("Verify window filtering excludes activity outside 30-day and 90-day windows")
    void testTimeWindowExclusions() {
        // PR merged 35 days ago is strictly outside PR30 window -> 0 PRs in 30d
        assertEquals(0.0, RepositoryHealthEvidenceV2.calculatePr30Score(0), 1e-6);

        // Commits 120 days ago are strictly outside 90d window -> 0 commits in 90d
        assertEquals(0.0, RepositoryHealthEvidenceV2.calculateC90Score(0), 1e-6);

        // Authors active only outside 90d are strictly outside AU90 window -> 0 authors in 90d
        assertEquals(0.0, RepositoryHealthEvidenceV2.calculateAu90Score(0), 1e-6);
    }

    @Test
    @DisplayName("Verify exact 5-observation boundary and 4-observation rejection for all R signals")
    void testExactFiveObservationSampleGateForAllSignals() {
        // Test RT
        ResponsivenessEvidenceV2 rRt = new ResponsivenessEvidenceV2();
        rRt.setResponseTimeScore(80.0); rRt.setRtSamples(4); rRt.setRtAvailable(true);
        rRt.setResponseRateScore(80.0); rRt.setRrSamples(5); rRt.setRrAvailable(true);
        // RT has N=4, so only RR is available -> count = 1 < 2 -> overall unavailable
        assertFalse(rRt.computeResponsiveness().available());

        rRt.setRtSamples(5); // Now N=5 -> count = 2 -> overall available
        assertTrue(rRt.computeResponsiveness().available());

        // Test PR
        ResponsivenessEvidenceV2 rPr = new ResponsivenessEvidenceV2();
        rPr.setResponseTimeScore(80.0); rPr.setRtSamples(5); rPr.setRtAvailable(true);
        rPr.setPrReviewScore(70.0); rPr.setPrSamples(4); rPr.setPrAvailable(true);
        // PR has N=4 -> only RT available -> count = 1 < 2 -> overall unavailable
        assertFalse(rPr.computeResponsiveness().available());

        rPr.setPrSamples(5); // Now N=5 -> count = 2, coverage = 0.55 -> overall available
        assertTrue(rPr.computeResponsiveness().available());

        // Test MA
        ResponsivenessEvidenceV2 rMa = new ResponsivenessEvidenceV2();
        rMa.setResponseTimeScore(80.0); rMa.setRtSamples(5); rMa.setRtAvailable(true);
        rMa.setRecentMaintainerAct(60.0); rMa.setMaSamples(4); rMa.setMaAvailable(true);
        // MA has N=4 -> only RT available -> count = 1 < 2 -> overall unavailable
        assertFalse(rMa.computeResponsiveness().available());

        rMa.setMaSamples(5); // Now N=5 -> count = 2, coverage = 0.45 -> overall available
        assertTrue(rMa.computeResponsiveness().available());
    }

    @Test
    @DisplayName("Verify unverified maintainer roles and missing permissions invalidate Responsiveness")
    void testAmbiguousRolesAndMissingPermissions() {
        ResponsivenessEvidenceV2 r = new ResponsivenessEvidenceV2();
        r.setResponseTimeScore(80.0); r.setRtSamples(10); r.setRtAvailable(true);
        r.setResponseRateScore(80.0); r.setRrSamples(10); r.setRrAvailable(true);
        r.setRoleMappingVerified(null); // unverified/ambiguous role

        // False or null role mapping must fail closed
        ResponsivenessComputationResult resNull = r.computeResponsiveness();
        assertFalse(resNull.available());

        r.setRoleMappingVerified(false);
        ResponsivenessComputationResult resFalse = r.computeResponsiveness();
        assertFalse(resFalse.available());
        assertTrue(resFalse.explanation().contains("unverified"));
    }
}

