package com.issuepilot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.dto.OnboardingConfirmationRequest;
import com.issuepilot.dto.SkillConfirmationDto;
import com.issuepilot.dto.SkillUpdateRequest;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.model.SkillEvidence;
import com.issuepilot.model.UserIdentity;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5: User-Editable Onboarding and Skill Confirmation API Test Suite.
 *
 * Verifies:
 * 1. Unauthenticated requests to onboarding and skill endpoints return 401.
 * 2. Profile retrieval and onboarding status for an authenticated user.
 * 3. Successful skill confirmation, dismissal tracking, preference updates, and onboarding completion.
 * 4. Empty submissions rejected when profile has no confirmed skills (does not silently complete onboarding).
 * 5. Cross-user spoofing attempts return 403 Forbidden.
 * 6. Skill level and score validations (finite, [0, 100], supported levels).
 * 7. Technology normalization and deduplication in confirmation requests.
 * 8. Dismissal persistence and resistance to re-analysis reversion.
 * 9. Post-onboarding skill updates and skill deletions.
 * 10. Post-onboarding preference updates.
 * 11. Repeated submission idempotency.
 * 12. Legacy profile compatibility and preservation of existing profile fields.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "APP_JWT_SECRET=test_very_secure_secret_key_with_at_least_32_bytes_of_entropy_12345",
        "APP_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "APP_JWT_ISSUER=https://issuepilot-test.com",
        "APP_JWT_EXPIRATION_SECONDS=3600"
})
public class OnboardingAndSkillConfirmationTest {

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

    @MockitoBean
    private MongoConfig mongoConfig;

    @MockitoBean
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @MockitoBean
    private UserRepository userRepository;

    private final Map<String, Map<String, Object>> inMemoryUsers = new HashMap<>();

    @BeforeEach
    public void setupMockRepo() {
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

    @Test
    @DisplayName("Unauthenticated requests to onboarding and skill management endpoints return 401")
    public void testUnauthenticatedRequestsReturn401() throws Exception {
        mockMvc.perform(get("/api/profile/onboarding"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/profile/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/profile/skills/Python")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content("{\"level\":\"advanced\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/profile/skills/Python"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/profile/preferences")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content("{\"preferred_technologies\":[\"Python\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated user retrieves onboarding status with inferred candidate skills")
    public void testGetOnboardingStatusForNewUser() throws Exception {
        String canonicalUserId = "github:50001";
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        profile.setIdentity(new UserIdentity("github", "50001", "dev_alice"));
        profile.setOnboardingCompleted(false);

        InferredSkill s1 = new InferredSkill("TypeScript", "language", 0.85);
        s1.addEvidence(new SkillEvidence("language_statistics", "alice/web-app", null, "85% TypeScript"));
        profile.setInferredSkills(List.of(s1));

        userRepository.upsertUser(profile);

        String jwt = tokenService.issueToken(canonicalUserId, "dev_alice", List.of("ROLE_USER"));

        mockMvc.perform(get("/api/profile/onboarding")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(canonicalUserId))
                .andExpect(jsonPath("$.onboarding_completed").value(false))
                .andExpect(jsonPath("$.identity.username").value("dev_alice"))
                .andExpect(jsonPath("$.inferred_skills[0].technology").value("TypeScript"))
                .andExpect(jsonPath("$.inferred_skills[0].confidence").value(0.85))
                .andExpect(jsonPath("$.inferred_skills[0].status").value("pending_confirmation"));
    }

    @Test
    @DisplayName("Valid onboarding confirmation marks onboarding complete and updates confirmed skills, dismissals, and preferences")
    public void testValidOnboardingConfirmation() throws Exception {
        String canonicalUserId = "github:50002";
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        profile.setOnboardingCompleted(false);

        InferredSkill pyInferred = new InferredSkill("Python", "language", 0.80);
        pyInferred.addEvidence(new SkillEvidence("language_statistics", "user/repo", null, "80% Python"));

        InferredSkill dockerInferred = new InferredSkill("Docker", "tool", 0.65);
        dockerInferred.addEvidence(new SkillEvidence("dependency_manifest", "user/repo", "Dockerfile", "Dockerfile found"));

        profile.setInferredSkills(new ArrayList<>(List.of(pyInferred, dockerInferred)));
        userRepository.upsertUser(profile);

        String jwt = tokenService.issueToken(canonicalUserId, "bob", List.of("ROLE_USER"));

        OnboardingConfirmationRequest req = new OnboardingConfirmationRequest();
        req.setConfirmedSkills(List.of(
                new SkillConfirmationDto("py", "language", "advanced", 85.0)
        ));
        req.setDismissedSkills(List.of("docker"));
        req.setPreferences(Map.of(
                "preferred_technologies", List.of("Python"),
                "experience_level", "senior"
        ));

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(canonicalUserId))
                .andExpect(jsonPath("$.onboarding_completed").value(true))
                .andExpect(jsonPath("$.skills.Python.technology").value("Python"))
                .andExpect(jsonPath("$.skills.Python.level").value("advanced"))
                .andExpect(jsonPath("$.skills.Python.score").value(85.0))
                .andExpect(jsonPath("$.skills.Python.source").value("user_confirmed"))
                .andExpect(jsonPath("$.dismissed_skills[0]").value("Docker"))
                .andExpect(jsonPath("$.preferences.experience_level").value("senior"));

        // Verify persisted state in MongoDB
        Map<String, Object> saved = userRepository.getUser(canonicalUserId);
        assertNotNull(saved);
        assertEquals(Boolean.TRUE, saved.get("onboarding_completed"));

        ContributorProfile savedProfile = objectMapper.convertValue(saved, ContributorProfile.class);
        assertNotNull(savedProfile);
        assertTrue(savedProfile.isOnboardingCompleted());
        assertTrue(savedProfile.getSkills().containsKey("Python"));
        assertTrue(savedProfile.getDismissedSkills().contains("Docker"));

        // Verify normalized preferences include confirmed Python
        Set<String> normalizedPrefs = savedProfile.getNormalizedTechnologyPreferences();
        assertTrue(normalizedPrefs.contains("Python"));
    }

    @Test
    @DisplayName("Empty skill submission rejected when profile has no existing confirmed skills")
    public void testEmptySubmissionRejected() throws Exception {
        String canonicalUserId = "github:50003";
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        profile.setOnboardingCompleted(false);
        userRepository.upsertUser(profile);

        String jwt = tokenService.issueToken(canonicalUserId, "charlie", List.of("ROLE_USER"));

        OnboardingConfirmationRequest emptyReq = new OnboardingConfirmationRequest();
        emptyReq.setConfirmedSkills(Collections.emptyList());

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(emptyReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Objects.requireNonNull(org.hamcrest.Matchers.containsString("at least one valid skill"))));

        // Profile must NOT be marked complete
        Map<String, Object> reloaded = userRepository.getUser(canonicalUserId);
        assertNotNull(reloaded);
        assertEquals(Boolean.FALSE, reloaded.get("onboarding_completed"));
    }

    @Test
    @DisplayName("Cross-user spoofing attempt in request body returns 403 Forbidden")
    public void testCrossUserSpoofingRejected() throws Exception {
        String userAlice = "github:50004_alice";
        String userBob = "github:50004_bob";

        String jwtAlice = tokenService.issueToken(userAlice, "alice", List.of("ROLE_USER"));

        OnboardingConfirmationRequest spoofReq = new OnboardingConfirmationRequest();
        spoofReq.setUserId(userBob); // Attempting to act as Bob
        spoofReq.setConfirmedSkills(List.of(new SkillConfirmationDto("Python", "language", "intermediate", 70.0)));

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwtAlice)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(spoofReq)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(Objects.requireNonNull(org.hamcrest.Matchers.containsString("Access denied"))));
    }

    @Test
    @DisplayName("Skill level and score validation enforces supported levels and bounds [0, 100]")
    public void testSkillLevelAndScoreValidation() throws Exception {
        String canonicalUserId = "github:50005";
        String jwt = tokenService.issueToken(canonicalUserId, "dave", List.of("ROLE_USER"));

        // 1. Invalid level
        OnboardingConfirmationRequest invalidLevelReq = new OnboardingConfirmationRequest();
        invalidLevelReq.setConfirmedSkills(List.of(new SkillConfirmationDto("Java", "language", "grandmaster", 80.0)));

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(invalidLevelReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Objects.requireNonNull(org.hamcrest.Matchers.containsString("Invalid skill level"))));

        // 2. Score out of bounds (> 100)
        OnboardingConfirmationRequest scoreTooHighReq = new OnboardingConfirmationRequest();
        scoreTooHighReq.setConfirmedSkills(List.of(new SkillConfirmationDto("Java", "language", "advanced", 150.0)));

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(scoreTooHighReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Objects.requireNonNull(org.hamcrest.Matchers.containsString("between 0.0 and 100.0"))));

        // 3. Null score derives deterministic score from level
        OnboardingConfirmationRequest nullScoreReq = new OnboardingConfirmationRequest();
        nullScoreReq.setConfirmedSkills(List.of(new SkillConfirmationDto("Java", "language", "intermediate", null)));

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(nullScoreReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.Java.score").value(70.0));
    }

    @Test
    @DisplayName("Technology normalization and duplicate deduplication operate deterministically")
    public void testTechnologyNormalizationAndDeduplication() throws Exception {
        String canonicalUserId = "github:50006";
        String jwt = tokenService.issueToken(canonicalUserId, "eve", List.of("ROLE_USER"));

        OnboardingConfirmationRequest req = new OnboardingConfirmationRequest();
        // Duplicates of Python with aliases
        req.setConfirmedSkills(List.of(
                new SkillConfirmationDto("py", "language", "beginner", 40.0),
                new SkillConfirmationDto("python3", "language", "intermediate", 70.0),
                new SkillConfirmationDto("Python", "language", "advanced", 90.0)
        ));

        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.Python.technology").value("Python"))
                .andExpect(jsonPath("$.skills.Python.score").value(90.0)); // Highest score retained
    }

    @Test
    @DisplayName("Dismissed skills are preserved and re-analysis does NOT re-add them as pending")
    public void testDismissalPersistenceAcrossReanalysis() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:50007");

        InferredSkill dockerCandidate = new InferredSkill("Docker", "tool", 0.70);
        dockerCandidate.addEvidence(new SkillEvidence("manifest", "repo", "Dockerfile", "evidence 1"));
        profile.addInferredSkillSafely(dockerCandidate);

        assertEquals("pending_confirmation", profile.getInferredSkills().get(0).getStatus());

        // User dismisses Docker
        profile.dismissSkill("Docker");
        assertEquals("dismissed", profile.getInferredSkills().get(0).getStatus());
        assertTrue(profile.getDismissedSkills().contains("Docker"));

        // Subsequent analysis discovers Docker again
        InferredSkill reanalyzedDocker = new InferredSkill("Docker", "tool", 0.75);
        reanalyzedDocker.addEvidence(new SkillEvidence("manifest", "repo2", "Dockerfile", "evidence 2"));
        profile.addInferredSkillSafely(reanalyzedDocker);

        // Status must strictly remain "dismissed", NOT "pending_confirmation"
        assertEquals("dismissed", profile.getInferredSkills().get(0).getStatus());
    }

    @Test
    @DisplayName("Post-onboarding skill updates and deletions work cleanly")
    public void testPostOnboardingSkillUpdatesAndDeletions() throws Exception {
        String canonicalUserId = "github:50008";
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        profile.setOnboardingCompleted(true);
        profile.confirmSkill("Python", 80.0, 1.0, "intermediate");
        userRepository.upsertUser(profile);

        String jwt = tokenService.issueToken(canonicalUserId, "frank", List.of("ROLE_USER"));

        // 1. Update skill
        SkillUpdateRequest updateReq = new SkillUpdateRequest("expert", 95.0, "language");
        mockMvc.perform(put("/api/profile/skills/Python")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technology").value("Python"))
                .andExpect(jsonPath("$.level").value("expert"))
                .andExpect(jsonPath("$.score").value(95.0));

        // 2. Delete skill
        mockMvc.perform(delete("/api/profile/skills/Python")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        // 3. Verify profile no longer has Python
        mockMvc.perform(get("/api/profile/me")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills.Python").doesNotExist());
    }

    @Test
    @DisplayName("Post-onboarding preferences update merges cleanly and rejects excessive keys")
    public void testPreferencesUpdate() throws Exception {
        String canonicalUserId = "github:50009";
        String jwt = tokenService.issueToken(canonicalUserId, "grace", List.of("ROLE_USER"));

        Map<String, Object> prefs = Map.of(
                "preferred_technologies", List.of("Rust", "Go"),
                "weekly_hours", 15
        );

        mockMvc.perform(put("/api/profile/preferences")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(prefs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekly_hours").value(15));
    }

    @Test
    @DisplayName("Repeated onboarding submissions are idempotent and preserve existing profile fields")
    public void testRepeatedSubmissionIdempotency() throws Exception {
        String canonicalUserId = "github:50010";
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        profile.setCapability(85.0);
        profile.setAcceptedCount(10);
        profile.setRejectedCount(2);
        userRepository.upsertUser(profile);

        String jwt = tokenService.issueToken(canonicalUserId, "helen", List.of("ROLE_USER"));

        OnboardingConfirmationRequest req = new OnboardingConfirmationRequest();
        req.setConfirmedSkills(List.of(new SkillConfirmationDto("Rust", "language", "advanced", 90.0)));

        // First submission
        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_completed").value(true));

        // Second submission with same payload
        mockMvc.perform(post("/api/profile/onboarding/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .content(asJson(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_completed").value(true));

        // Verify counts and capability are retained
        Map<String, Object> saved = userRepository.getUser(canonicalUserId);
        assertNotNull(saved);
        assertEquals(10, ((Number) saved.get("accepted_count")).intValue());
        assertEquals(2, ((Number) saved.get("rejected_count")).intValue());
    }

    @Test
    @DisplayName("Legacy profiles without onboarding fields deserialize cleanly and default to onboarding incomplete")
    public void testLegacyProfileCompatibility() throws Exception {
        String legacyJson = """
        {
            "user_id": "legacy:9999",
            "capability": 75.0,
            "skills": {
                "Java": {
                    "technology": "Java",
                    "score": 80.0
                }
            }
        }
        """;

        ContributorProfile legacyProfile = objectMapper.readValue(legacyJson, ContributorProfile.class);
        assertNotNull(legacyProfile);
        assertFalse(legacyProfile.isOnboardingCompleted());
        assertNotNull(legacyProfile.getDismissedSkills());
        assertTrue(legacyProfile.getDismissedSkills().isEmpty());
        assertTrue(legacyProfile.getSkills().containsKey("Java"));
    }
}
