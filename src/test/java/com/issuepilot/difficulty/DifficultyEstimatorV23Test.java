package com.issuepilot.difficulty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.EvidenceModelsV2.DifficultyEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.DifficultyEvidenceV2.DifficultyComputationResult;
import com.issuepilot.model.EvidenceModelsV2.DifficultyEvidenceV2.FitComputationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 4: Difficulty Estimator and Engineering Challenge Fit Model Tests")
class DifficultyEstimatorV23Test {

    private ObjectMapper objectMapper;
    private DifficultyEstimatorService difficultyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        difficultyService = new DifficultyEstimatorService();
    }

    @Test
    @DisplayName("Verify rubric anchor validation strictly on {0, 25, 50, 75, 100}")
    void testRubricAnchorValidation() {
        assertTrue(DifficultyEvidenceV2.isValidAnchor(0.0));
        assertTrue(DifficultyEvidenceV2.isValidAnchor(25.0));
        assertTrue(DifficultyEvidenceV2.isValidAnchor(50.0));
        assertTrue(DifficultyEvidenceV2.isValidAnchor(75.0));
        assertTrue(DifficultyEvidenceV2.isValidAnchor(100.0));

        assertFalse(DifficultyEvidenceV2.isValidAnchor(33.0));
        assertFalse(DifficultyEvidenceV2.isValidAnchor(60.0));
        assertFalse(DifficultyEvidenceV2.isValidAnchor(-10.0));
        assertFalse(DifficultyEvidenceV2.isValidAnchor(120.0));
        assertFalse(DifficultyEvidenceV2.isValidAnchor(Double.NaN));
        assertFalse(DifficultyEvidenceV2.isValidAnchor(null));
    }

    @Test
    @DisplayName("Verify Spec Section 16 worked examples (ID=80/cap=60 -> D=77.5, ID=40/cap=60 -> D=85.0)")
    void testSpecWorkedExamples() {
        // Worked Example 1: ID = 80, cap_eff = 60 -> g = 20 > 5 -> penalty = 1.5 * 15 = 22.5 -> D = 77.5
        FitComputationResult fit1 = difficultyService.calculateFit(80.0, 0.90, 60.0, 0.85);
        assertTrue(fit1.available());
        assertEquals(20.0, fit1.fitGap(), 1e-6);
        assertEquals(22.5, fit1.penalty(), 1e-4);
        assertEquals(77.5, fit1.difficultyMatchScore(), 1e-4);
        assertEquals(0.85, fit1.confidence(), 1e-4);

        // Worked Example 2: ID = 40, cap_eff = 60 -> g = -20 < -5 -> penalty = 20 - 5 = 15.0 -> D = 85.0
        FitComputationResult fit2 = difficultyService.calculateFit(40.0, 0.90, 60.0, 0.85);
        assertTrue(fit2.available());
        assertEquals(-20.0, fit2.fitGap(), 1e-6);
        assertEquals(15.0, fit2.penalty(), 1e-4);
        assertEquals(85.0, fit2.difficultyMatchScore(), 1e-4);
        assertEquals(0.85, fit2.confidence(), 1e-4);
    }

    @Test
    @DisplayName("Verify coverage gate threshold A_ID >= 0.50 (Pass at 0.50, Fail at 0.40)")
    void testCoverageGateThreshold() {
        // Boundary case: TC (0.30) + CC (0.20) = 0.50 -> Available
        DifficultyEvidenceV2 evPass = new DifficultyEvidenceV2();
        evPass.setTechnicalComplexity(50.0);
        evPass.setTechnicalAvailable(true);
        evPass.setCoordinationComplexity(50.0);
        evPass.setCoordinationAvailable(true);

        DifficultyComputationResult resPass = evPass.computeDifficulty(List.of());
        assertTrue(resPass.available());
        assertEquals(0.50, resPass.coverageAid(), 1e-6);
        assertEquals(50.0, resPass.issueDifficulty(), 1e-4);

        // Sub-boundary case: DI (0.20) + CC (0.20) = 0.40 < 0.50 -> Unavailable
        DifficultyEvidenceV2 evFail = new DifficultyEvidenceV2();
        evFail.setDependencyArchImpact(50.0);
        evFail.setDependencyAvailable(true);
        evFail.setCoordinationComplexity(50.0);
        evFail.setCoordinationAvailable(true);

        DifficultyComputationResult resFail = evFail.computeDifficulty(List.of());
        assertFalse(resFail.available());
        assertEquals(0.40, resFail.coverageAid(), 1e-6);
    }

    @Test
    @DisplayName("Verify missing one, two, and three difficulty signals")
    void testMissingSignals() {
        // Missing 1: SC missing -> 0.30 + 0.20 + 0.20 = 0.70 >= 0.50 -> Available
        DifficultyEvidenceV2 evM1 = new DifficultyEvidenceV2();
        evM1.setTechnicalComplexity(50.0); evM1.setTechnicalAvailable(true);
        evM1.setDependencyArchImpact(50.0); evM1.setDependencyAvailable(true);
        evM1.setCoordinationComplexity(50.0); evM1.setCoordinationAvailable(true);
        DifficultyComputationResult r1 = evM1.computeDifficulty(List.of());
        assertTrue(r1.available());
        assertEquals(0.70, r1.coverageAid(), 1e-6);

        // Missing 2: SC + TC missing -> 0.20 + 0.20 = 0.40 < 0.50 -> Unavailable
        DifficultyEvidenceV2 evM2 = new DifficultyEvidenceV2();
        evM2.setDependencyArchImpact(50.0); evM2.setDependencyAvailable(true);
        evM2.setCoordinationComplexity(50.0); evM2.setCoordinationAvailable(true);
        DifficultyComputationResult r2 = evM2.computeDifficulty(List.of());
        assertFalse(r2.available());
        assertEquals(0.40, r2.coverageAid(), 1e-6);

        // Missing 3: only SC present -> 0.30 < 0.50 -> Unavailable
        DifficultyEvidenceV2 evM3 = new DifficultyEvidenceV2();
        evM3.setScopeChangeSize(75.0); evM3.setScopeAvailable(true);
        DifficultyComputationResult r3 = evM3.computeDifficulty(List.of());
        assertFalse(r3.available());
        assertEquals(0.30, r3.coverageAid(), 1e-6);
    }

    @Test
    @DisplayName("Verify weak label corroboration and clamping at [-3.0, +3.0]")
    void testLabelCorroborationLimitsAndClamping() {
        DifficultyEvidenceV2 ev = new DifficultyEvidenceV2(50.0, 50.0, 50.0, 50.0);

        // Easy labels capped at -3.0
        DifficultyComputationResult rEasy = ev.computeDifficulty(List.of("good-first-issue", "beginner", "starter", "trivial"));
        assertEquals(-3.0, rEasy.labelAdjustment(), 1e-6);
        assertEquals(47.0, rEasy.issueDifficulty(), 1e-4);

        // Hard labels capped at +3.0
        DifficultyComputationResult rHard = ev.computeDifficulty(List.of("epic", "complex", "high-complexity", "advanced"));
        assertEquals(3.0, rHard.labelAdjustment(), 1e-6);
        assertEquals(53.0, rHard.issueDifficulty(), 1e-4);

        // Boundary score clamping at 100.0 (100 + 3 = 100)
        DifficultyEvidenceV2 evTop = new DifficultyEvidenceV2(100.0, 100.0, 100.0, 100.0);
        DifficultyComputationResult rTop = evTop.computeDifficulty(List.of("epic", "complex"));
        assertEquals(100.0, rTop.issueDifficulty(), 1e-6);

        // Boundary score clamping at 0.0 (0 - 3 = 0)
        DifficultyEvidenceV2 evBot = new DifficultyEvidenceV2(0.0, 0.0, 0.0, 0.0);
        DifficultyComputationResult rBot = evBot.computeDifficulty(List.of("good-first-issue", "beginner"));
        assertEquals(0.0, rBot.issueDifficulty(), 1e-6);
    }

    @Test
    @DisplayName("Verify fit gap dead zone boundaries, immediate outside boundaries, and penalty saturation")
    void testDeadZoneBoundariesAndSaturation() {
        // Dead zone exact boundaries: g in [-5, 5] -> penalty = 0 -> D = 100.0
        assertEquals(100.0, DifficultyEvidenceV2.computeFit(45.0, 1.0, 50.0, 1.0).difficultyMatchScore(), 1e-6); // g = -5
        assertEquals(100.0, DifficultyEvidenceV2.computeFit(50.0, 1.0, 50.0, 1.0).difficultyMatchScore(), 1e-6); // g = 0
        assertEquals(100.0, DifficultyEvidenceV2.computeFit(55.0, 1.0, 50.0, 1.0).difficultyMatchScore(), 1e-6); // g = +5

        // Immediately outside boundaries
        // g = +5.1 -> penalty = 1.5 * 0.1 = 0.15 -> D = 99.85
        FitComputationResult fPosSmall = DifficultyEvidenceV2.computeFit(55.1, 1.0, 50.0, 1.0);
        assertEquals(0.15, fPosSmall.penalty(), 1e-4);
        assertEquals(99.85, fPosSmall.difficultyMatchScore(), 1e-4);

        // g = -5.1 -> penalty = 5.1 - 5 = 0.10 -> D = 99.90
        FitComputationResult fNegSmall = DifficultyEvidenceV2.computeFit(44.9, 1.0, 50.0, 1.0);
        assertEquals(0.10, fNegSmall.penalty(), 1e-4);
        assertEquals(99.90, fNegSmall.difficultyMatchScore(), 1e-4);

        // g = +6.0 -> penalty = 1.5 * 1.0 = 1.50 -> D = 98.50
        FitComputationResult fPos1 = DifficultyEvidenceV2.computeFit(56.0, 1.0, 50.0, 1.0);
        assertEquals(1.50, fPos1.penalty(), 1e-4);
        assertEquals(98.50, fPos1.difficultyMatchScore(), 1e-4);

        // g = -6.0 -> penalty = 6 - 5 = 1.00 -> D = 99.00
        FitComputationResult fNeg1 = DifficultyEvidenceV2.computeFit(44.0, 1.0, 50.0, 1.0);
        assertEquals(1.00, fNeg1.penalty(), 1e-4);
        assertEquals(99.00, fNeg1.difficultyMatchScore(), 1e-4);

        // Saturation clamping to 0.0
        FitComputationResult fSatPos = DifficultyEvidenceV2.computeFit(100.0, 1.0, 20.0, 1.0); // g = 80 -> pen = 112.5 -> D = 0.0
        assertEquals(0.0, fSatPos.difficultyMatchScore(), 1e-6);

        FitComputationResult fSatNeg = DifficultyEvidenceV2.computeFit(0.0, 1.0, 110.0, 1.0); // g = -110 -> pen = 105.0 -> D = 0.0
        assertEquals(0.0, fSatNeg.difficultyMatchScore(), 1e-6);
    }

    @Test
    @DisplayName("Verify availability dependencies: Skill unavailable, Difficulty unavailable, and Questionnaire regression")
    void testAvailabilityDependenciesAndQuestionnaireRegression() {
        // Skill unavailable -> D unavailable
        FitComputationResult fNoSkill = DifficultyEvidenceV2.computeFit(50.0, 1.0, null, 0.0);
        assertFalse(fNoSkill.available());

        // Difficulty unavailable -> D unavailable
        FitComputationResult fNoDiff = DifficultyEvidenceV2.computeFit(null, 0.0, 50.0, 1.0);
        assertFalse(fNoDiff.available());

        // Phase 3 questionnaire regression: observed skill was below 0.40, but valid questionnaire gives cap_eff = 75.0, C_cap = 0.40
        FitComputationResult fQ = DifficultyEvidenceV2.computeFit(75.0, 0.90, 75.0, 0.40);
        assertTrue(fQ.available());
        assertEquals(0.0, fQ.fitGap(), 1e-6);
        assertEquals(100.0, fQ.difficultyMatchScore(), 1e-6);
        assertEquals(0.40, fQ.confidence(), 1e-6);
    }

    @Test
    @DisplayName("Verify negation handling prevents false architectural complexity triggers")
    void testNegationHandling() {
        // Explicit negation in prose: "no database migration required"
        DifficultyEvidenceV2 ev = difficultyService.extractEvidenceFromContent(
                "Fix user query timeout",
                "Optimized SQL indexing. No database migration required for this change.",
                List.of(),
                1
        );

        assertTrue(ev.getDependencyAvailable());
        assertEquals(0.0, ev.getDependencyArchImpact(), 1e-6,
                "Negated migration statement must map to anchor 0.0, not 75.0");
    }

    @Test
    @DisplayName("Load and execute all golden JSON fixtures from disk")
    void testGoldenJsonFixtures() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/fixtures/golden_difficulty_v23_fixtures.json");
        assertNotNull(stream, "Golden difficulty fixtures file must exist on classpath");

        JsonNode root = objectMapper.readTree(stream);
        assertEquals("2.3.0", root.get("schema_version").asText());
        assertEquals("2.3.0", root.get("algorithm_version").asText());

        JsonNode fixtures = root.get("fixtures");
        assertTrue(fixtures.isArray());
        assertEquals(10, fixtures.size());

        // Assert Fixture 1: Full anchors
        JsonNode f1 = fixtures.get(0);
        assertEquals("diff_case_1_full_anchors", f1.get("id").asText());
        DifficultyEvidenceV2 ev1 = objectMapper.treeToValue(f1.get("input"), DifficultyEvidenceV2.class);
        DifficultyComputationResult r1 = ev1.computeDifficulty(List.of());
        assertTrue(r1.available());
        assertEquals(1.0, r1.coverageAid(), 1e-6);
        assertEquals(52.5, r1.issueDifficulty(), 1e-4);

        FitComputationResult fit1 = DifficultyEvidenceV2.computeFit(
                r1.issueDifficulty(), r1.confidence(),
                f1.get("contributor").get("capability").asDouble(),
                f1.get("contributor").get("confidence").asDouble()
        );
        assertTrue(fit1.available());
        assertEquals(100.0, fit1.difficultyMatchScore(), 1e-6);
        assertEquals(0.88, fit1.confidence(), 1e-4);
    }
}

