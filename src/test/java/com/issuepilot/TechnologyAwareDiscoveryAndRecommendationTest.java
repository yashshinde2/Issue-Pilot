package com.issuepilot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.controller.RecommendationController.RecommendationRequest;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.model.SkillEvidence;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.TokenService;
import com.issuepilot.service.CppRankingClient;
import com.issuepilot.service.TechnologyMatchingService;
import com.issuepilot.service.TechnologyMatchingService.MatchPriority;
import com.issuepilot.service.TechnologyMatchingService.TechnologyMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = {
        "APP_JWT_SECRET=test_very_secure_secret_key_with_at_least_32_bytes_of_entropy_12345",
        "APP_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "APP_JWT_ISSUER=https://issuepilot-test.com",
        "APP_JWT_EXPIRATION_SECONDS=3600"
})
public class TechnologyAwareDiscoveryAndRecommendationTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
        EnvLoader.loadEnv();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private TechnologyMatchingService technologyMatchingService;

    @Autowired
    private CppRankingClient cppRankingClient;

    @MockitoBean
    private MongoConfig mongoConfig;

    @MockitoBean
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private IssueRepository issueRepository;

    private final Map<String, Map<String, Object>> inMemoryUsers = new HashMap<>();

    @BeforeEach
    public void setup() {
        inMemoryUsers.clear();
        when(mongoConfig.isConnected()).thenReturn(false);

        when(userRepository.getUser(anyString())).thenAnswer(inv -> inMemoryUsers.get(inv.getArgument(0)));
        when(userRepository.upsertUser(any(ContributorProfile.class))).thenAnswer(inv -> {
            ContributorProfile p = inv.getArgument(0);
            Map<String, Object> map = objectMapper.convertValue(p, new TypeReference<Map<String, Object>>() {});
            inMemoryUsers.put(p.getUserId(), map);
            return map;
        });
    }

    private String asJson(Object obj) throws Exception {
        return Objects.requireNonNull(objectMapper.writeValueAsString(obj));
    }

    private Map<String, Object> createTestIssue(String id, List<String> detectedTechs, String source, double complexity) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", id);
        issue.put("issue_number", 101);
        issue.put("title", "Fix issue in " + (detectedTechs.isEmpty() ? "core" : detectedTechs.get(0)));
        issue.put("repository_full_name", "test-org/test-repo");
        issue.put("issue_url", "https://github.com/test-org/test-repo/issues/101");
        issue.put("state", "open");
        issue.put("source", source != null ? source : "github");
        issue.put("detected_technologies", new ArrayList<>(detectedTechs));

        List<Map<String, Object>> techs = new ArrayList<>();
        for (String dt : detectedTechs) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("technology", dt);
            tm.put("raw_relevance", 2.0);
            tm.put("normalized_relevance", 1.0 / Math.max(1, detectedTechs.size()));
            techs.add(tm);
        }
        issue.put("technologies", techs);

        Map<String, Object> diffEvidence = new LinkedHashMap<>();
        diffEvidence.put("available", true);
        diffEvidence.put("technical_complexity", complexity);
        issue.put("difficulty_evidence", diffEvidence);

        return issue;
    }

    @Test
    @DisplayName("1. Confirmed-skill technology matching extracts and matches user-confirmed skills")
    public void testConfirmedSkillTechnologyMatching() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:60001");
        profile.confirmSkill("Python", 85.0, 1.0, "advanced");

        Map<String, Object> issue = createTestIssue("iss_py_1", List.of("Python"), "github", 50.0);

        List<TechnologyMatch> matches = technologyMatchingService.matchTechnologies(issue, profile);
        assertEquals(1, matches.size());
        TechnologyMatch m = matches.get(0);
        assertEquals("Python", m.getTechnology());
        assertEquals(MatchPriority.CONFIRMED_SKILL, m.getPriority());
        assertEquals(85.0, m.getProficiencyScore());
        assertEquals("advanced", m.getLevel());
        assertTrue(m.getExplanation().contains("Matches a confirmed skill: Python (advanced)"));
    }

    @Test
    @DisplayName("2. Technology alias normalization matches canonical names (e.g. py, reactjs, cpp)")
    public void testTechnologyAliasNormalization() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:60002");
        profile.confirmSkill("cpp", 90.0, 1.0, "expert"); // Alias for C++

        // Issue has C++ detected
        Map<String, Object> issue = createTestIssue("iss_cpp_1", List.of("C++"), "github", 60.0);

        List<TechnologyMatch> matches = technologyMatchingService.matchTechnologies(issue, profile);
        assertEquals(1, matches.size());
        assertEquals("C++", matches.get(0).getTechnology());
        assertEquals(MatchPriority.CONFIRMED_SKILL, matches.get(0).getPriority());

        // Alias in preferences: 'reactjs' normalized to 'React'
        profile.setPreferences(Map.of("preferred_technologies", List.of("reactjs")));
        Map<String, Object> reactIssue = createTestIssue("iss_react_1", List.of("React"), "github", 40.0);
        List<TechnologyMatch> reactMatches = technologyMatchingService.matchTechnologies(reactIssue, profile);
        assertEquals(1, reactMatches.size());
        assertEquals("React", reactMatches.get(0).getTechnology());
        assertEquals(MatchPriority.DISCOVERY_PREFERENCE, reactMatches.get(0).getPriority());
    }

    @Test
    @DisplayName("3. Inferred skills remain lower-confidence evidence and do not treat confidence as proficiency")
    public void testInferredSkillsRemainLowerConfidence() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:60003");

        InferredSkill inferred = new InferredSkill("React", "framework", 0.75);
        inferred.addEvidence(new SkillEvidence("manifest", "repo", "package.json", "react: 18.0.0"));
        profile.addInferredSkillSafely(inferred);

        Map<String, Object> issue = createTestIssue("iss_react_2", List.of("React"), "github", 45.0);

        List<TechnologyMatch> matches = technologyMatchingService.matchTechnologies(issue, profile);
        assertEquals(1, matches.size());
        TechnologyMatch m = matches.get(0);
        assertEquals("React", m.getTechnology());
        assertEquals(MatchPriority.INFERRED_SKILL, m.getPriority());
        // Inferred skill confidence must NOT be treated as user proficiency score
        assertNull(m.getProficiencyScore(), "Inferred skills must not assign confidence as user proficiency score");
        assertEquals(0.75, m.getConfidence(), 0.001);
        assertTrue(m.getExplanation().contains("Related to an inferred skill: React (75% confidence)"));
        assertFalse(m.getExplanation().contains("confirmed"), "Must never claim inferred skill is confirmed");
    }

    @Test
    @DisplayName("4. Dismissed skills are strictly excluded from confirmed and inferred skill matching")
    public void testDismissedSkillsExcluded() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:60004");
        profile.confirmSkill("Docker", 70.0, 1.0, "intermediate");
        profile.dismissSkill("Docker"); // User subsequently dismisses Docker

        Map<String, Object> issue = createTestIssue("iss_doc_1", List.of("Docker"), "github", 50.0);

        List<TechnologyMatch> matches = technologyMatchingService.matchTechnologies(issue, profile);
        assertTrue(matches.isEmpty(), "Dismissed skills must not match confirmed or inferred skills");

        // getNormalizedTechnologyPreferences must also exclude Docker
        assertFalse(profile.getNormalizedTechnologyPreferences().contains("Docker"));
    }

    @Test
    @DisplayName("5. Legacy profiles without onboardingCompleted default safely and remain compatible")
    public void testLegacyProfileCompatibility() {
        ContributorProfile legacyProfile = new ContributorProfile();
        legacyProfile.setUserId("legacy_user_1");
        // Legacy profile with no skills map and null onboardingCompleted
        legacyProfile.setOnboardingCompleted(false);

        Map<String, Object> issue = createTestIssue("iss_general_1", List.of("Go"), "github", 50.0);

        List<TechnologyMatch> matches = technologyMatchingService.matchTechnologies(issue, legacyProfile);
        assertTrue(matches.isEmpty());

        RecommendationRequest req = new RecommendationRequest();
        req.demo_mode = true;

        List<Map<String, Object>> prioritized = technologyMatchingService.prioritizeCandidates(
                List.of(issue), legacyProfile, req, 10
        );
        assertEquals(1, prioritized.size(), "Legacy profiles should safely receive general backfill candidates");
    }

    @Test
    @DisplayName("6. Users who have not completed onboarding preserve baseline candidate discovery behavior")
    public void testUncompletedOnboardingPreservesBaselineDiscovery() {
        ContributorProfile pendingUser = new ContributorProfile();
        pendingUser.setUserId("github:60006");
        pendingUser.setOnboardingCompleted(false);

        Map<String, Object> issueA = createTestIssue("iss_a", List.of("Python"), "github", 40.0);
        Map<String, Object> issueB = createTestIssue("iss_b", List.of("Java"), "gitlab", 60.0);

        RecommendationRequest req = new RecommendationRequest();
        req.demo_mode = true;

        List<Map<String, Object>> pool = technologyMatchingService.prioritizeCandidates(
                List.of(issueA, issueB), pendingUser, req, 10
        );
        assertEquals(2, pool.size());
        // Both issues included via general backfill without requiring completed onboarding
        assertEquals("iss_a", pool.get(0).get("id"));
        assertEquals("iss_b", pool.get(1).get("id"));
    }

    @Test
    @DisplayName("7. Missing issue technology metadata does not cause errors or discard issues")
    public void testMissingIssueTechnologyMetadata() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:60007");
        profile.confirmSkill("Python", 80.0, 1.0, "intermediate");

        // Issue with no technologies detected at all
        Map<String, Object> emptyTechIssue = createTestIssue("iss_notech_1", Collections.emptyList(), "github", 50.0);

        List<TechnologyMatch> matches = technologyMatchingService.matchTechnologies(emptyTechIssue, profile);
        assertTrue(matches.isEmpty());

        RecommendationRequest req = new RecommendationRequest();
        req.demo_mode = true;

        List<Map<String, Object>> candidates = technologyMatchingService.prioritizeCandidates(
                List.of(emptyTechIssue), profile, req, 10
        );
        assertEquals(1, candidates.size(), "Issues without technology metadata must not be discarded if no hard filter applies");

        // Enrichment should provide readable fallback explanation
        technologyMatchingService.enrichWithExplanations(emptyTechIssue, profile);
        assertNotNull(emptyTechIssue.get("match_reasons"));
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) emptyTechIssue.get("match_reasons");
        assertFalse(reasons.isEmpty());
        assertTrue(reasons.get(0).contains("General open issue"));
    }

    @Test
    @DisplayName("8. Hard-filter and soft-preference behavior: hard filters strictly exclude; soft preferences backfill")
    public void testHardFilterVsSoftPreferenceBehavior() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:60008");
        profile.confirmSkill("Python", 90.0, 1.0, "expert");

        Map<String, Object> pyIssue = createTestIssue("iss_py_hard", List.of("Python"), "github", 50.0);
        Map<String, Object> rustIssue = createTestIssue("iss_rust_hard", List.of("Rust"), "gitlab", 50.0);

        // Case A: Soft preference - both issues can be in candidate pool (Python first, Rust as backfill)
        RecommendationRequest softReq = new RecommendationRequest();
        softReq.demo_mode = true;
        List<Map<String, Object>> softPool = technologyMatchingService.prioritizeCandidates(
                List.of(rustIssue, pyIssue), profile, softReq, 10
        );
        assertEquals(2, softPool.size());
        assertEquals("iss_py_hard", softPool.get(0).get("id"), "Priority 1 confirmed skill issue prioritized first");
        assertEquals("iss_rust_hard", softPool.get(1).get("id"), "Non-matching issue included as backfill");

        // Case B: Hard technology filter via request - strictly excludes Rust
        RecommendationRequest hardReq = new RecommendationRequest();
        hardReq.technology_filter = "Python";
        hardReq.demo_mode = true;
        List<Map<String, Object>> hardPool = technologyMatchingService.prioritizeCandidates(
                List.of(rustIssue, pyIssue), profile, hardReq, 10
        );
        assertEquals(1, hardPool.size());
        assertEquals("iss_py_hard", hardPool.get(0).get("id"));

        // Case C: Hard source filter via request - strictly excludes GitLab
        RecommendationRequest hardSourceReq = new RecommendationRequest();
        hardSourceReq.source_filter = "github";
        hardSourceReq.demo_mode = true;
        List<Map<String, Object>> hardSourcePool = technologyMatchingService.prioritizeCandidates(
                List.of(rustIssue, pyIssue), profile, hardSourceReq, 10
        );
        assertEquals(1, hardSourcePool.size());
        assertEquals("iss_py_hard", hardSourcePool.get(0).get("id"));

        // Case D: Profile strict mode (hard_filters in preferences)
        profile.setPreferences(Map.of("hard_filters", List.of("technologies")));
        List<Map<String, Object>> strictProfilePool = technologyMatchingService.prioritizeCandidates(
                List.of(rustIssue, pyIssue), profile, softReq, 10
        );
        assertEquals(1, strictProfilePool.size());
        assertEquals("iss_py_hard", strictProfilePool.get(0).get("id"));
    }

    @Test
    @DisplayName("9. No duplicate candidates when an issue matches multiple confirmed or inferred skills")
    public void testNoDuplicateCandidatesFromMultipleMatchingSkills() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:60009");
        profile.confirmSkill("Python", 85.0, 1.0, "advanced");
        profile.confirmSkill("Django", 80.0, 1.0, "intermediate");

        // Issue has BOTH Python and Django detected
        Map<String, Object> multiTechIssue = createTestIssue("iss_multi_1", List.of("Python", "Django"), "github", 55.0);

        RecommendationRequest req = new RecommendationRequest();
        req.demo_mode = true;

        List<Map<String, Object>> pool = technologyMatchingService.prioritizeCandidates(
                List.of(multiTechIssue, multiTechIssue), profile, req, 10
        );
        assertEquals(1, pool.size(), "Issue matching multiple skills must not be duplicated");

        // Explanations should include both confirmed skills
        technologyMatchingService.enrichWithExplanations(pool.get(0), profile);
        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) pool.get(0).get("match_reasons");
        assertEquals(2, reasons.size());
        assertTrue(reasons.get(0).contains("Python"));
        assertTrue(reasons.get(1).contains("Django"));
    }

    @Test
    @DisplayName("10. Authoritative C++ V1 ranking formula weights and renormalization remain untouched")
    public void testV1RankingWeightsUntouched() {
        if (!cppRankingClient.isAvailable()) {
            return;
        }
        // Test C++ ranking invocation directly to assert V1 formula:
        // FinalScore = 0.35S + 0.25D + 0.15R + 0.10H + 0.10F + 0.05N
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("user_id", "demo-test");
        profile.put("capability", 70.0);
        profile.put("skills", Map.of("Python", Map.of("score", 80.0, "confidence", 1.0)));

        Map<String, Object> cand = createTestIssue("v1_eval_1", List.of("Python"), "github", 70.0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", profile);
        payload.put("candidates", List.of(cand));

        Map<String, Object> res = cppRankingClient.executeRanking(payload);
        assertNotNull(res);
        assertEquals("1.0.0-phase1", res.get("algorithm_version"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) res.get("results");
        assertFalse(results.isEmpty());
        Map<String, Object> top = results.get(0);
        assertNotNull(top.get("scores"));

        @SuppressWarnings("unchecked")
        Map<String, Object> scores = (Map<String, Object>) top.get("scores");
        // Skill match should be 80.0
        assertEquals(80.0, ((Number) scores.get("skill_match")).doubleValue(), 0.1);
        
        // Assert V1 weights in engine response metadata
        @SuppressWarnings("unchecked")
        Map<String, Object> baseWeights = (Map<String, Object>) res.get("weights");
        if (baseWeights != null) {
            assertEquals(0.35, ((Number) baseWeights.get("skill_weight")).doubleValue(), 0.01);
            assertEquals(0.25, ((Number) baseWeights.get("difficulty_weight")).doubleValue(), 0.01);
        }

        // When feedback is missing (cold start profile without feedback history),
        // effective skill weight renormalizes according to V1 formula: 0.35 / 0.90 = ~0.3889
        @SuppressWarnings("unchecked")
        Map<String, Object> effWeights = (Map<String, Object>) top.get("effective_weights");
        double expectedRenormalizedSkill = 0.35 / 0.90;
        assertEquals(expectedRenormalizedSkill, ((Number) effWeights.get("skill")).doubleValue(), 0.01);
    }

    @Test
    @DisplayName("11. Existing recommendation API response structure maintains complete backward compatibility")
    public void testRecommendationApiResponseCompatibility() throws Exception {
        String canonicalUserId = "github:60011";
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        profile.setOnboardingCompleted(true);
        profile.confirmSkill("Python", 85.0, 1.0, "advanced");
        userRepository.upsertUser(profile);

        String jwt = tokenService.issueToken(canonicalUserId, "alice", List.of("ROLE_USER"));

        RecommendationRequest req = new RecommendationRequest();
        req.demo_mode = true;
        req.target_count = 3;

        mockMvc.perform(post("/api/recommendations/personalized")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").exists())
                .andExpect(jsonPath("$.algorithm_version").value("1.0.0-phase1"))
                .andExpect(jsonPath("$.data_source").value("sample_demonstration_candidates"))
                .andExpect(jsonPath("$.delivered_count").isNumber())
                .andExpect(jsonPath("$.recommendations").isArray())
                .andExpect(jsonPath("$.verified_recommendations").isArray())
                // Verify new technology match explanation fields are present
                .andExpect(jsonPath("$.recommendations[0].match_reasons").isArray())
                .andExpect(jsonPath("$.recommendations[0].technology_match_reasons").isArray());
    }

    @Test
    @DisplayName("12. Authenticated user isolation rejects cross-user recommendation requests with 403")
    public void testAuthenticatedUserIsolation() throws Exception {
        String userAlice = "github:60012_alice";
        String userBob = "github:60012_bob";

        String jwtAlice = tokenService.issueToken(userAlice, "alice", List.of("ROLE_USER"));

        RecommendationRequest req = new RecommendationRequest();
        req.user_id = userBob; // Alice attempts to request Bob's recommendations
        req.demo_mode = true;

        mockMvc.perform(post("/api/recommendations/personalized")
                        .header("Authorization", "Bearer " + jwtAlice)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(Objects.requireNonNull(org.hamcrest.Matchers.containsString("Access denied"))));
    }
}
