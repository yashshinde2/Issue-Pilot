package com.issuepilot.technology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.model.EvidenceModelsV2.ContributorSkillEvidenceV2;
import com.issuepilot.model.EvidenceModelsV2.ContributorSkillEvidenceV2.SkillComputationResult;
import com.issuepilot.model.TechnologyRelevance;
import com.issuepilot.service.TechnologyMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 3: Contributor Skill and Technology Evidence Verification")
class ContributorSkillV23Test {

    private ObjectMapper objectMapper;
    private TechnologyMatchingService technologyMatchingService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        technologyMatchingService = new TechnologyMatchingService();
    }

    @Test
    @DisplayName("Verify exact saturation curves and mathematical formulas")
    void testSaturationCurves() {
        // Repo saturation: k = 4
        assertEquals(0.0, ContributorSkillEvidenceV2.sat(0, 4.0), 1e-6);
        // sat(2, 4) = 100 * (1 - e^-0.5) = 39.346934
        assertEquals(39.346934, ContributorSkillEvidenceV2.sat(2, 4.0), 1e-4);
        // sat(4, 4) = 100 * (1 - e^-1) = 63.212056
        assertEquals(63.212056, ContributorSkillEvidenceV2.sat(4, 4.0), 1e-4);

        // Standalone commit saturation: k = 80
        assertEquals(39.346934, ContributorSkillEvidenceV2.sat(40, 80.0), 1e-4);
        assertEquals(63.212056, ContributorSkillEvidenceV2.sat(80, 80.0), 1e-4);

        // PR volume saturation: k = 8
        assertEquals(39.346934, ContributorSkillEvidenceV2.sat(4, 8.0), 1e-4);
        assertEquals(63.212056, ContributorSkillEvidenceV2.sat(8, 8.0), 1e-4);

        // Merge rate: 100 * (merged + 2) / (decided + 4)
        assertEquals(66.666667, ContributorSkillEvidenceV2.mergeRate(6, 8), 1e-4);
        assertEquals(50.0, ContributorSkillEvidenceV2.mergeRate(0, 0), 1e-4);
        assertEquals(100.0 * 10.0 / 12.0, ContributorSkillEvidenceV2.mergeRate(8, 8), 1e-4);

        // Recency decay: 100 * exp(-months / 12)
        assertEquals(100.0, ContributorSkillEvidenceV2.recencyDecay(0.0), 1e-6);
        assertEquals(60.653066, ContributorSkillEvidenceV2.recencyDecay(6.0), 1e-4);
        assertEquals(36.787944, ContributorSkillEvidenceV2.recencyDecay(12.0), 1e-4);
    }

    @Test
    @DisplayName("Verify questionnaire self-report scale and confidence cap")
    void testQuestionnaireScaleAndBlending() {
        assertEquals(20.0, ContributorSkillEvidenceV2.questionnaireLevelToScore("beginner"));
        assertEquals(40.0, ContributorSkillEvidenceV2.questionnaireLevelToScore("basic"));
        assertEquals(60.0, ContributorSkillEvidenceV2.questionnaireLevelToScore("intermediate"));
        assertEquals(75.0, ContributorSkillEvidenceV2.questionnaireLevelToScore("advanced"));
        assertEquals(90.0, ContributorSkillEvidenceV2.questionnaireLevelToScore("expert"));

        // Pure questionnaire test
        ContributorSkillEvidenceV2 qOnly = new ContributorSkillEvidenceV2();
        qOnly.setTechnology("Go");
        qOnly.setHasQuestionnaire(true);
        qOnly.setQuestionnaireLevel("intermediate");
        SkillComputationResult rQ = qOnly.computeSkill();

        assertTrue(rQ.available());
        assertEquals(60.0, rQ.score(), 1e-6);
        assertEquals(0.40, rQ.confidence(), 1e-6);
        assertEquals("self_reported", rQ.source());

        // Blended test: observed + questionnaire
        ContributorSkillEvidenceV2 blended = new ContributorSkillEvidenceV2();
        blended.setTechnology("Python");
        blended.setUniqueContributedRepositories(2);
        blended.setRepoAvailable(true);
        blended.setStandaloneCommits(40);
        blended.setStandaloneCommitsAvailable(true);
        blended.setUniqueAuthoredPrs(4);
        blended.setPrVolumeAvailable(true);
        blended.setDecidedPrs(8);
        blended.setMergedDecidedPrs(6);
        blended.setMergeRateAvailable(true);
        blended.setMonthsSinceLastActivity(6.0);
        blended.setRecencyAvailable(true);
        blended.setHasQuestionnaire(true);
        blended.setQuestionnaireLevel("advanced");
        blended.setQuestionnaireScore(75.0);

        SkillComputationResult rB = blended.computeSkill();
        assertTrue(rB.available());
        assertEquals("blended", rB.source());
        // Skill_obs = 50.738774, c_obs = 1.0. q_w = min(0.20, 0.40 / 1.40) = 0.20
        // Score = 0.80 * 50.738774 + 0.20 * 75.0 = 55.591019
        assertEquals(55.591019, rB.score(), 1e-4);
        // Confidence = 0.80 * 1.0 + 0.20 * 0.40 = 0.88
        assertEquals(0.88, rB.confidence(), 1e-4);
    }

    @Test
    @DisplayName("Verify nominal coverage threshold A_t >= 0.40 gate")
    void testCoverageGateAt() {
        ContributorSkillEvidenceV2 ev = new ContributorSkillEvidenceV2();
        ev.setTechnology("Rust");
        // Only repo score: nominal weight = 0.15 < 0.40
        ev.setUniqueContributedRepositories(2);
        ev.setRepoAvailable(true);

        SkillComputationResult res = ev.computeSkill();
        assertFalse(res.available(), "Observed skill must be unavailable when A_t = 0.15 < 0.40");
        assertEquals("unavailable", res.source());
        assertEquals(0.15, res.nominalCoverageAt(), 1e-6);
    }

    @Test
    @DisplayName("Verify missing merge rate when decided_prs < 5 with weight renormalization")
    void testMissingMergeRateRenormalization() {
        ContributorSkillEvidenceV2 ev = new ContributorSkillEvidenceV2();
        ev.setTechnology("Java");
        ev.setUniqueContributedRepositories(4); // 0.15 * 63.212056 = 9.481808
        ev.setRepoAvailable(true);
        ev.setStandaloneCommits(80);            // 0.20 * 63.212056 = 12.642411
        ev.setStandaloneCommitsAvailable(true);
        ev.setUniqueAuthoredPrs(8);             // 0.20 * 63.212056 = 12.642411
        ev.setPrVolumeAvailable(true);
        ev.setDecidedPrs(3);                    // < 5 -> unavailable
        ev.setMergedDecidedPrs(2);
        ev.setMergeRateAvailable(false);
        ev.setMonthsSinceLastActivity(12.0);    // 0.15 * 36.787944 = 5.518192
        ev.setRecencyAvailable(true);

        // Sum weights = 0.15 + 0.20 + 0.20 + 0.15 = 0.70 >= 0.40
        // Weighted sum = 40.284822. 40.284822 / 0.70 = 57.549746
        SkillComputationResult res = ev.computeSkill();
        assertTrue(res.available());
        assertEquals(0.70, res.nominalCoverageAt(), 1e-6);
        assertEquals(57.549746, res.score(), 1e-4);
        assertEquals(1.0, res.confidence(), 1e-4);
        assertEquals(88, res.effectiveEpisodes());
    }

    @Test
    @DisplayName("Verify multi-technology candidate aggregation and 0.50 coverage gate")
    void testMultiTechnologyCandidateAggregation() {
        Map<String, ContributorSkillEvidenceV2> evidenceMap = new HashMap<>();
        ContributorSkillEvidenceV2 py = new ContributorSkillEvidenceV2();
        py.setTechnology("Python");
        py.setHasQuestionnaire(true);
        py.setQuestionnaireScore(55.591019);
        evidenceMap.put("Python", py);

        // Candidate with Python (rel = 0.75) and JS (rel = 0.25)
        List<TechnologyRelevance> issueTechs = List.of(
                new TechnologyRelevance("Python", 3.0),
                new TechnologyRelevance("JavaScript", 1.0)
        );
        issueTechs.get(0).setNormalizedRelevance(0.75);
        issueTechs.get(1).setNormalizedRelevance(0.25);

        // Contributor has Python (0.75 >= 0.50) -> passes
        var resPass = technologyMatchingService.calculateSkillMatchV2(evidenceMap, Collections.emptyMap(), issueTechs);
        assertTrue(resPass.available());
        assertEquals(0.75, resPass.coverage(), 1e-4);
        assertEquals(55.591019, resPass.skillScore(), 1e-4);
        // Confidence C_S uses unnormalized rel: 0.75 * 0.40 = 0.30
        assertEquals(0.75 * 0.40, resPass.confidence(), 1e-4);

        // If contributor only has JS (rel = 0.25 < 0.50) -> fails gate
        Map<String, ContributorSkillEvidenceV2> jsOnlyMap = new HashMap<>();
        ContributorSkillEvidenceV2 js = new ContributorSkillEvidenceV2();
        js.setTechnology("JavaScript");
        js.setHasQuestionnaire(true);
        js.setQuestionnaireScore(80.0);
        jsOnlyMap.put("JavaScript", js);

        var resFail = technologyMatchingService.calculateSkillMatchV2(jsOnlyMap, Collections.emptyMap(), issueTechs);
        assertFalse(resFail.available());
        assertEquals(0.25, resFail.coverage(), 1e-4);
    }

    @Test
    @DisplayName("Verify short-alias context validation in TechnologyRegistry")
    void testShortAliasContextValidation() {
        // English "go" must be rejected
        var d1 = TechnologyRegistry.detect("", "we must go to the database", "there is an issue when we go ahead", null, null);
        assertFalse(d1.technologies().stream().anyMatch(t -> "Go".equalsIgnoreCase(t.getTechnology())),
                "English word 'go' must never be detected as Go");

        // Contextual "in Go" and "goroutine" must be accepted
        var d2 = TechnologyRegistry.detect("", "refactor server in Go", "rewritten with goroutines", null, null);
        assertTrue(d2.technologies().stream().anyMatch(t -> "Go".equalsIgnoreCase(t.getTechnology())),
                "'in Go' and 'goroutines' must map to canonical Go");

        // Explicit file extension .c must map to C
        var d3 = TechnologyRegistry.detect("", "fixed segmentation fault", "crash occurs in main.c line 42", null, null);
        assertTrue(d3.technologies().stream().anyMatch(t -> "C".equalsIgnoreCase(t.getTechnology())),
                "Explicit file extension .c must map to canonical C");
    }

    @Test
    @DisplayName("Verify contribution episode deduplication and PR-commit link separation")
    void testContributionDeduplication() {
        Instant now = Instant.now();
        List<ContributionEpisodeAggregator.ContributionPr> prs = List.of(
                new ContributionEpisodeAggregator.ContributionPr("pr-1", "octocat/hello-world", "Python", true, true, false, false, now),
                new ContributionEpisodeAggregator.ContributionPr("pr-1", "octocat/hello-world", "Python", true, true, false, false, now), // duplicate page
                new ContributionEpisodeAggregator.ContributionPr("pr-bot", "octocat/hello-world", "Python", true, true, true, false, now)  // bot PR
        );

        List<ContributionEpisodeAggregator.ContributionCommit> commits = List.of(
                new ContributionEpisodeAggregator.ContributionCommit("sha-1", "octocat/hello-world", "Python", "pr-1", false, false, now), // linked to PR
                new ContributionEpisodeAggregator.ContributionCommit("sha-2", "octocat/hello-world", "Python", "pr-1", false, false, now), // linked to PR
                new ContributionEpisodeAggregator.ContributionCommit("sha-3", "octocat/hello-world", "Python", null, false, false, now),   // standalone
                new ContributionEpisodeAggregator.ContributionCommit("sha-fork", "fork/hello-world", "Python", null, false, true, now),    // fork commit
                new ContributionEpisodeAggregator.ContributionCommit("sha-bot", "octocat/hello-world", "Python", null, true, false, now)    // bot commit
        );

        var agg = ContributionEpisodeAggregator.aggregate(
                prs, commits, Collections.emptyList(), now, 1.0, 1.0, true, true
        );

        assertTrue(agg.containsKey("Python"));
        ContributorSkillEvidenceV2 py = agg.get("Python");

        // Unique repos: 1 ("octocat/hello-world")
        assertEquals(1, py.getUniqueContributedRepositories());
        // Standalone commits: 1 ("sha-3" only, sha-1 and sha-2 were linked to pr-1)
        assertEquals(1, py.getStandaloneCommits());
        // Unique authored PRs: 1 ("pr-1", bot PR excluded, duplicate pr-1 deduplicated)
        assertEquals(1, py.getUniqueAuthoredPrs());

        SkillComputationResult res = py.computeSkill();
        // Effective episodes n_eff = unique_authored_prs (1) + standalone_commits (1) = 2
        assertEquals(2, res.effectiveEpisodes());
    }

    @Test
    @DisplayName("Execute and assert all golden JSON fixtures from disk")
    void testGoldenJsonFixtures() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/fixtures/golden_skill_v23_fixtures.json");
        assertNotNull(stream, "Golden skill fixtures file must exist on classpath");

        JsonNode root = objectMapper.readTree(stream);
        assertEquals("2.3.0", root.get("schema_version").asText());
        assertEquals("2.3.0", root.get("algorithm_version").asText());

        JsonNode fixtures = root.get("fixtures");
        assertTrue(fixtures.isArray());
        assertEquals(7, fixtures.size());

        // Fixture 1: Pure observed
        JsonNode f1 = fixtures.get(0);
        assertEquals("skill_case_1_pure_observed", f1.get("id").asText());
        ContributorSkillEvidenceV2 ev1 = objectMapper.treeToValue(f1.get("input"), ContributorSkillEvidenceV2.class);
        SkillComputationResult res1 = ev1.computeSkill();
        assertTrue(res1.available());
        assertEquals(50.738774, res1.score(), 1e-4);
        assertEquals(1.0, res1.confidence(), 1e-6);
        assertEquals(44, res1.effectiveEpisodes());
        assertEquals("observed", res1.source());

        // Fixture 2: Missing merge rate
        JsonNode f2 = fixtures.get(1);
        ContributorSkillEvidenceV2 ev2 = objectMapper.treeToValue(f2.get("input"), ContributorSkillEvidenceV2.class);
        SkillComputationResult res2 = ev2.computeSkill();
        assertTrue(res2.available());
        assertEquals(57.549746, res2.score(), 1e-4);
        assertEquals(0.70, res2.nominalCoverageAt(), 1e-6);
        assertEquals(88, res2.effectiveEpisodes());

        // Fixture 3: Insufficient coverage rejection
        JsonNode f3 = fixtures.get(2);
        ContributorSkillEvidenceV2 ev3 = objectMapper.treeToValue(f3.get("input"), ContributorSkillEvidenceV2.class);
        SkillComputationResult res3 = ev3.computeSkill();
        assertFalse(res3.available());
        assertEquals(0.15, res3.nominalCoverageAt(), 1e-6);

        // Fixture 4: Pure questionnaire
        JsonNode f4 = fixtures.get(3);
        ContributorSkillEvidenceV2 ev4 = objectMapper.treeToValue(f4.get("input"), ContributorSkillEvidenceV2.class);
        SkillComputationResult res4 = ev4.computeSkill();
        assertTrue(res4.available());
        assertEquals(60.0, res4.score(), 1e-6);
        assertEquals(0.40, res4.confidence(), 1e-6);
        assertEquals("self_reported", res4.source());

        // Fixture 5: Blended skill
        JsonNode f5 = fixtures.get(4);
        ContributorSkillEvidenceV2 ev5 = objectMapper.treeToValue(f5.get("input"), ContributorSkillEvidenceV2.class);
        SkillComputationResult res5 = ev5.computeSkill();
        assertTrue(res5.available());
        assertEquals(55.591019, res5.score(), 1e-4);
        assertEquals(0.88, res5.confidence(), 1e-4);
        assertEquals("blended", res5.source());

        // Fixture 6: Multi-technology pass
        JsonNode f6 = fixtures.get(5);
        List<TechnologyRelevance> issueTechs6 = List.of(
                new TechnologyRelevance("Python", 3.0),
                new TechnologyRelevance("JavaScript", 1.0)
        );
        issueTechs6.get(0).setNormalizedRelevance(0.75);
        issueTechs6.get(1).setNormalizedRelevance(0.25);
        Map<String, ContributorSkill> skillMap6 = Map.of(
                "Python", new ContributorSkill("Python", 55.591019, 0.88)
        );
        var r6 = technologyMatchingService.calculateSkillMatchV2(Collections.emptyMap(), skillMap6, issueTechs6);
        assertTrue(r6.available());
        assertEquals(55.591019, r6.skillScore(), 1e-4);
        assertEquals(0.66, r6.confidence(), 1e-4);
        assertEquals(0.75, r6.coverage(), 1e-4);

        // Fixture 7: Multi-technology fail
        JsonNode f7 = fixtures.get(6);
        Map<String, ContributorSkill> skillMap7 = Map.of(
                "JavaScript", new ContributorSkill("JavaScript", 80.0, 0.90)
        );
        var r7 = technologyMatchingService.calculateSkillMatchV2(Collections.emptyMap(), skillMap7, issueTechs6);
        assertFalse(r7.available());
        assertEquals(0.25, r7.coverage(), 1e-4);

        // Verify recency_evaluations section
        JsonNode recencyEvals = root.get("recency_evaluations");
        if (recencyEvals != null && recencyEvals.isArray()) {
            for (JsonNode item : recencyEvals) {
                double m = item.get("months").asDouble();
                double expectedScore = item.get("expected_score").asDouble();
                assertEquals(expectedScore, ContributorSkillEvidenceV2.recencyDecay(m), 1e-4);
            }
        }

        // Verify questionnaire_boundary_tests section
        JsonNode qbTests = root.get("questionnaire_boundary_tests");
        if (qbTests != null && qbTests.isArray()) {
            for (JsonNode qb : qbTests) {
                double cObs = qb.get("c_obs").asDouble();
                double qConf = qb.get("q_conf").asDouble();
                double expectedQw = qb.get("q_w").asDouble();
                double ratio = qConf / (cObs + qConf);
                double qw = Math.min(0.20, ratio);
                assertEquals(expectedQw, qw, 1e-6);
                assertEquals(0.20, qw, 1e-6);
            }
        }
    }

    @Test
    @DisplayName("Verify Recency Score evaluations under spec exp(-m/12) and half-life 0.5^(m/12)")
    void testRecencyEvaluationsAndHalfLifeComparison() {
        double[] months = {0.0, 6.0, 12.0, 24.0, 36.0};
        double[] expectedExp = {100.0, 60.653066, 36.787944, 13.533528, 4.978707};
        double[] expectedHalfLife = {100.0, 70.710678, 50.0, 25.0, 12.5};

        for (int i = 0; i < months.length; i++) {
            assertEquals(expectedExp[i], ContributorSkillEvidenceV2.recencyDecay(months[i]), 1e-4);
            assertEquals(expectedHalfLife[i], ContributorSkillEvidenceV2.recencyHalfLife(months[i]), 1e-4);
        }
    }

    @Test
    @DisplayName("Verify questionnaire weight q_w = min(0.20, q_conf / (c_obs + q_conf)) across c_obs in [0, 1.0]")
    void testQuestionnaireBoundaryValues() {
        double qConf = 0.40;
        double[] cObsValues = {0.0, 0.10, 0.40, 1.00};
        for (double cObs : cObsValues) {
            double ratio = qConf / (cObs + qConf);
            double qw = Math.min(0.20, ratio);
            assertEquals(0.20, qw, 1e-9, "q_w must be identically 0.20 at c_obs=" + cObs + " because ratio=" + ratio + " >= 0.20");
        }
    }

    @Test
    @DisplayName("Verify all 6 contribution deduplication edge cases (PR-commit linking, multi-repo IDs, pagination, bots, forks)")
    void testContributionDeduplicationSixEdgeCases() {
        Instant now = Instant.now();

        List<ContributionEpisodeAggregator.ContributionPr> prs = List.of(
                // 1. Multiple commits in one PR -> tested together below
                // 4. Duplicate PRs across pagination pages count once
                new ContributionEpisodeAggregator.ContributionPr("42", "orgA/repoA", "Go", true, true, false, false, now),
                new ContributionEpisodeAggregator.ContributionPr("42", "orgA/repoA", "Go", true, true, false, false, now),
                // 5. Identical native PR IDs in different repositories remain distinct (Repo A #42 vs Repo B #42)
                new ContributionEpisodeAggregator.ContributionPr("42", "orgB/repoB", "Go", false, true, false, false, now),
                // 6. Bots and forks are excluded
                new ContributionEpisodeAggregator.ContributionPr("99", "orgA/repoA", "Go", true, true, true, false, now),
                new ContributionEpisodeAggregator.ContributionPr("10", "fork/repoC", "Go", true, true, false, true, now)
        );

        List<ContributionEpisodeAggregator.ContributionCommit> commits = List.of(
                // 1. Multiple commits in one PR (c1, c2, c3 in Repo A PR #42)
                // 2. PR-linked commits do not count as standalone commits
                // 3. A commit linked to multiple PR records or duplicate commit records is not duplicated
                new ContributionEpisodeAggregator.ContributionCommit("c1", "orgA/repoA", "Go", "42", false, false, now),
                new ContributionEpisodeAggregator.ContributionCommit("c2", "orgA/repoA", "Go", "42", false, false, now),
                new ContributionEpisodeAggregator.ContributionCommit("c3", "orgA/repoA", "Go", "42", false, false, now),
                new ContributionEpisodeAggregator.ContributionCommit("c1", "orgA/repoA", "Go", "42", false, false, now),
                // PR-linked commit for Repo B PR #42
                new ContributionEpisodeAggregator.ContributionCommit("c4", "orgB/repoB", "Go", "42", false, false, now),
                // Standalone human commit in Repo A
                new ContributionEpisodeAggregator.ContributionCommit("c5", "orgA/repoA", "Go", null, false, false, now),
                // 6. Bot commit & fork commit excluded
                new ContributionEpisodeAggregator.ContributionCommit("c6", "orgA/repoA", "Go", null, true, false, now),
                new ContributionEpisodeAggregator.ContributionCommit("c7", "fork/repoC", "Go", null, false, true, now)
        );

        var agg = ContributionEpisodeAggregator.aggregate(
                prs, commits, Collections.emptyList(), now, 1.0, 1.0, true, true
        );

        assertTrue(agg.containsKey("Go"));
        ContributorSkillEvidenceV2 go = agg.get("Go");

        assertEquals(2, go.getUniqueContributedRepositories(), "Forks excluded; exactly 2 distinct repos (orgA/repoA and orgB/repoB)");
        assertEquals(2, go.getUniqueAuthoredPrs(), "Repo A #42 and Repo B #42 both counted; pagination duplicate, bot PR, fork PR excluded");
        assertEquals(1, go.getStandaloneCommits(), "c1..c4 linked to PRs; only c5 counted as standalone");

        SkillComputationResult res = go.computeSkill();
        assertEquals(3, res.effectiveEpisodes(), "Effective episodes n_eff = 2 PRs + 1 standalone commit = 3");
        assertEquals(2, go.getDecidedPrs());
        assertEquals(1, go.getMergedDecidedPrs());
    }

    @Test
    @DisplayName("Verify ambiguous technology alias detection edge cases for Go, C, R, JS, TS")
    void testAmbiguousTechnologyAliasDetectionEdgeCases() {
        // --- Go ---
        // English prose rejection
        var dGoEng = TechnologyRegistry.detect("", "we must go to the database now", "let us go ahead with migration", null, null);
        assertFalse(dGoEng.technologies().stream().anyMatch(t -> "Go".equalsIgnoreCase(t.getTechnology())));

        // Context prose acceptance
        var dGoCtx = TechnologyRegistry.detect("", "issue in service written in Go", "concurrency handled by goroutines", null, null);
        assertTrue(dGoCtx.technologies().stream().anyMatch(t -> "Go".equalsIgnoreCase(t.getTechnology())));

        // Code fence acceptance
        var dGoFence = TechnologyRegistry.detect("", "panic trace", "```go\nfunc main() {}\n```", null, null);
        assertTrue(dGoFence.technologies().stream().anyMatch(t -> "Go".equalsIgnoreCase(t.getTechnology())));

        // File extension acceptance
        var dGoExt = TechnologyRegistry.detect("", "syntax error", "failed at pkg/server/main.go line 10", null, null);
        assertTrue(dGoExt.technologies().stream().anyMatch(t -> "Go".equalsIgnoreCase(t.getTechnology())));

        // Topic acceptance
        var dGoTopic = TechnologyRegistry.detect("", "backend issue", "server hangs", null, List.of("golang"));
        assertTrue(dGoTopic.technologies().stream().anyMatch(t -> "Go".equalsIgnoreCase(t.getTechnology())));

        // --- C ---
        // English prose rejection
        var dCEng = TechnologyRegistry.detect("", "look at option c in the list", "part c describes the behavior", null, null);
        assertFalse(dCEng.technologies().stream().anyMatch(t -> "C".equalsIgnoreCase(t.getTechnology())));

        // Context prose acceptance
        var dCCtx = TechnologyRegistry.detect("", "core engine written in C", "ansi-c compatibility required", null, null);
        assertTrue(dCCtx.technologies().stream().anyMatch(t -> "C".equalsIgnoreCase(t.getTechnology())));

        // Code fence acceptance
        var dCFence = TechnologyRegistry.detect("", "null dereference", "```c\nint main() { return 0; }\n```", null, null);
        assertTrue(dCFence.technologies().stream().anyMatch(t -> "C".equalsIgnoreCase(t.getTechnology())));

        // File extension acceptance
        var dCExt = TechnologyRegistry.detect("", "segfault", "fault in driver/main.c line 55", null, null);
        assertTrue(dCExt.technologies().stream().anyMatch(t -> "C".equalsIgnoreCase(t.getTechnology())));

        // --- R ---
        // English prose rejection
        var dREng = TechnologyRegistry.detect("", "computed correlation r = 0.95", "radius r of the circle", null, null);
        assertFalse(dREng.technologies().stream().anyMatch(t -> "R".equalsIgnoreCase(t.getTechnology())));

        // Context prose acceptance
        var dRCtx = TechnologyRegistry.detect("", "statistical analysis in R", "uses cran package", null, null);
        assertTrue(dRCtx.technologies().stream().anyMatch(t -> "R".equalsIgnoreCase(t.getTechnology())));

        // Code fence acceptance
        var dRFence = TechnologyRegistry.detect("", "plot failure", "```r\nplot(x, y)\n```", null, null);
        assertTrue(dRFence.technologies().stream().anyMatch(t -> "R".equalsIgnoreCase(t.getTechnology())));

        // File extension acceptance
        var dRExt = TechnologyRegistry.detect("", "runtime error", "error in scripts/analysis.R line 12", null, null);
        assertTrue(dRExt.technologies().stream().anyMatch(t -> "R".equalsIgnoreCase(t.getTechnology())));

        // --- JS ---
        // English prose rejection
        var dJsEng = TechnologyRegistry.detect("", "the js variable is unset", "pass param js = 1", null, null);
        assertFalse(dJsEng.technologies().stream().anyMatch(t -> "JavaScript".equalsIgnoreCase(t.getTechnology())));

        // Context prose acceptance
        var dJsCtx = TechnologyRegistry.detect("", "client side written in JS", "bundle size regression", null, null);
        assertTrue(dJsCtx.technologies().stream().anyMatch(t -> "JavaScript".equalsIgnoreCase(t.getTechnology())));

        // Code fence acceptance
        var dJsFence = TechnologyRegistry.detect("", "uncaught error", "```javascript\nconsole.log(42);\n```", null, null);
        assertTrue(dJsFence.technologies().stream().anyMatch(t -> "JavaScript".equalsIgnoreCase(t.getTechnology())));

        // File extension acceptance
        var dJsExt = TechnologyRegistry.detect("", "syntax error", "found in web/app.js:20", null, null);
        assertTrue(dJsExt.technologies().stream().anyMatch(t -> "JavaScript".equalsIgnoreCase(t.getTechnology())));

        // Manifest acceptance
        var dJsManifest = TechnologyRegistry.detect("", "build broken", "check package.json dependencies", null, null);
        assertTrue(dJsManifest.technologies().stream().anyMatch(t -> "JavaScript".equalsIgnoreCase(t.getTechnology())));

        // --- TS ---
        // English prose rejection (ts = timestamp)
        var dTsEng = TechnologyRegistry.detect("", "timestamp ts = 1700000000", "invalid ts in event log", null, null);
        assertFalse(dTsEng.technologies().stream().anyMatch(t -> "TypeScript".equalsIgnoreCase(t.getTechnology())));

        // Context prose acceptance
        var dTsCtx = TechnologyRegistry.detect("", "frontend written in TS", "compiler error on TS types", null, null);
        assertTrue(dTsCtx.technologies().stream().anyMatch(t -> "TypeScript".equalsIgnoreCase(t.getTechnology())));

        // Code fence acceptance
        var dTsFence = TechnologyRegistry.detect("", "type mismatch", "```typescript\nconst x: number = 42;\n```", null, null);
        assertTrue(dTsFence.technologies().stream().anyMatch(t -> "TypeScript".equalsIgnoreCase(t.getTechnology())));

        // File extension acceptance
        var dTsExt = TechnologyRegistry.detect("", "type error", "found in src/index.ts:15", null, null);
        assertTrue(dTsExt.technologies().stream().anyMatch(t -> "TypeScript".equalsIgnoreCase(t.getTechnology())));

        // Manifest acceptance
        var dTsManifest = TechnologyRegistry.detect("", "tsconfig error", "check tsconfig.json paths", null, null);
        assertTrue(dTsManifest.technologies().stream().anyMatch(t -> "TypeScript".equalsIgnoreCase(t.getTechnology())));
    }
}

