package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.FeedbackEvent;
import com.issuepilot.model.IssueVote;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.repository.VoteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stage 1 Security & Principal Authorization Test Suite.
 *
 * Covers:
 * 1. Unauthenticated access returning 401 Unauthorized
 * 2. Invalid Bearer tokens returning 401 Unauthorized
 * 3. Valid authenticated requests succeeding
 * 4. Client-supplied spoofed user IDs returning 403 Forbidden
 * 5. Cross-user read/write/delete attempts returning 403 Forbidden
 * 6. Server-side snapshot reconstruction (ignoring client feature snapshots)
 * 7. Missing catalog issue rejection with 400 Bad Request
 * 8. Protected demo-mode behavior (cannot bypass authentication)
 * 9. Public endpoints remain accessible without authentication
 */
@SpringBootTest
@AutoConfigureMockMvc
@SuppressWarnings("null")
public class SecurityAuthorizationTest {

    static {
        EnvLoader.loadEnv();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private UserRepository userRepository;

    private static final String ALICE = "alice_sec_test";
    private static final String BOB = "bob_sec_test";
    private static final String CATALOG_ISSUE_ID = "synth_sec_1";

    @BeforeEach
    public void setupFixtures() {
        cleanupFixtures();

        // 1. Register canonical test issue in MongoDB Atlas
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("id", "github_catalog_org_repo_" + CATALOG_ISSUE_ID);
        issue.put("source", "github");
        issue.put("source_issue_id", CATALOG_ISSUE_ID);
        issue.put("native_id", CATALOG_ISSUE_ID);
        issue.put("issue_number", 777);
        issue.put("repository_full_name", "catalog-org/repo");
        issue.put("repository_language", "Python");
        issue.put("title", "Canonical Security Catalog Issue");
        issue.put("detected_technologies", List.of("Python", "FastAPI"));
        issue.put("labels", List.of("security", "enhancement"));
        issue.put("discovery_category", "enhancement");
        issue.put("issue_url", "https://github.com/catalog-org/repo/issues/777");

        Map<String, Object> diff = Map.of(
                "scope_change_size", 60.0,
                "technical_complexity", 60.0,
                "historical_effort", 60.0,
                "dependency_arch_impact", 60.0,
                "coordination_complexity", 60.0
        );
        issue.put("difficulty_evidence", diff);

        issueRepository.upsertIssue(issue, false);

        // 2. Register Alice profile in MongoDB Atlas
        ContributorProfile aliceProfile = new ContributorProfile();
        aliceProfile.setUserId(ALICE);
        aliceProfile.setCapability(60.0);
        userRepository.upsertUser(aliceProfile);
    }

    @AfterEach
    public void cleanupFixtures() {
        try {
            voteRepository.removeVote(ALICE, "github", CATALOG_ISSUE_ID);
            voteRepository.removeVote(BOB, "github", CATALOG_ISSUE_ID);
            userRepository.deleteUser(ALICE);
            userRepository.deleteUser(BOB);
            issueRepository.deleteIssue("github_catalog_org_repo_" + CATALOG_ISSUE_ID);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // 1. UNAUTHENTICATED ACCESS (401 UNAUTHORIZED)
    // =========================================================================

    @Test
    @DisplayName("[Security] Unauthenticated access to protected routes returns 401")
    public void testUnauthenticatedAccessReturns401() throws Exception {
        // Vote endpoints
        mockMvc.perform(post("/api/votes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"github\",\"platform_issue_id\":\"1\",\"vote\":1}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/votes/" + ALICE))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/votes?user_id=" + ALICE + "&source=github&platform_issue_id=1"))
                .andExpect(status().isUnauthorized());

        // Feedback endpoints
        mockMvc.perform(post("/api/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issue_id\":\"1\",\"event_type\":\"accepted\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/feedback/" + ALICE))
                .andExpect(status().isUnauthorized());

        // User profile endpoints
        mockMvc.perform(get("/api/users/" + ALICE))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/users/" + ALICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_difficulty\": 50.0}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/users/" + ALICE))
                .andExpect(status().isUnauthorized());

        // Personalized recommendations
        mockMvc.perform(post("/api/recommendations/personalized")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + ALICE + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 2. INVALID BEARER TOKEN (401 UNAUTHORIZED)
    // =========================================================================

    @Test
    @DisplayName("[Security] Malformed or invalid token returns 401 Unauthorized")
    public void testInvalidBearerTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/votes/" + ALICE)
                        .header("Authorization", "Bearer invalid.malformed.signature"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/votes")
                        .header("Authorization", "Bearer not-a-real-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"github\",\"platform_issue_id\":\"1\",\"vote\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 3. VALID AUTHENTICATED REQUESTS (200 / 201 SUCCESS)
    // =========================================================================

    @Test
    @DisplayName("[Security] Valid authenticated principal succeeds on own resources")
    public void testValidAuthenticatedRequestSucceeds() throws Exception {
        // Alice records a vote on canonical catalog issue
        Map<String, Object> voteReq = new LinkedHashMap<>();
        voteReq.put("source", "github");
        voteReq.put("platform_issue_id", CATALOG_ISSUE_ID);
        voteReq.put("vote", 1);

        mockMvc.perform(post("/api/votes")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        // Alice retrieves her own votes
        mockMvc.perform(get("/api/votes/" + ALICE)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_votes").value(1));

        // Alice retrieves her vote summary
        mockMvc.perform(get("/api/votes/summary/" + ALICE)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.likes").value(1));

        // Alice records feedback
        FeedbackEvent fb = new FeedbackEvent();
        fb.setUserId(ALICE);
        fb.setIssueId(CATALOG_ISSUE_ID);
        fb.setEventType("accepted");
        mockMvc.perform(post("/api/feedback")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fb)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        // Alice views own profile
        mockMvc.perform(get("/api/users/" + ALICE)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(ALICE));

        // Alice removes her vote
        mockMvc.perform(delete("/api/votes")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .param("user_id", ALICE)
                        .param("source", "github")
                        .param("platform_issue_id", CATALOG_ISSUE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.removed").value(true));
    }

    // =========================================================================
    // 4. CLIENT-SUPPLIED SPOOFED USER IDS (403 FORBIDDEN)
    // =========================================================================

    @Test
    @DisplayName("[Security] Client-supplied user ID mismatching authenticated principal returns 403")
    public void testSpoofedUserIdRejectedWith403() throws Exception {
        // Authenticated as Alice, but request body specifies Bob
        Map<String, Object> voteReq = new LinkedHashMap<>();
        voteReq.put("user_id", BOB); // Spoofed!
        voteReq.put("source", "github");
        voteReq.put("platform_issue_id", CATALOG_ISSUE_ID);
        voteReq.put("vote", 1);

        mockMvc.perform(post("/api/votes")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteReq)))
                .andExpect(status().isForbidden());

        // Authenticated as Alice, feedback body specifies Bob
        FeedbackEvent spoofedFb = new FeedbackEvent();
        spoofedFb.setUserId(BOB);
        spoofedFb.setIssueId(CATALOG_ISSUE_ID);
        spoofedFb.setEventType("accepted");
        mockMvc.perform(post("/api/feedback")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(spoofedFb)))
                .andExpect(status().isForbidden());

        // Authenticated as Alice, profile creation specifies Bob
        ContributorProfile spoofedProfile = new ContributorProfile();
        spoofedProfile.setUserId(BOB);
        mockMvc.perform(post("/api/users")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(spoofedProfile)))
                .andExpect(status().isForbidden());

        // Authenticated as Alice, recommendation request specifies Bob
        mockMvc.perform(post("/api/recommendations/personalized")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"" + BOB + "\",\"demo_mode\":true}"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 5. CROSS-USER READ / WRITE / DELETE ATTEMPTS (403 FORBIDDEN)
    // =========================================================================

    @Test
    @DisplayName("[Security] Cross-user access attempts return 403 Forbidden")
    public void testCrossUserAccessReturns403() throws Exception {
        // Alice attempts to read Bob's votes
        mockMvc.perform(get("/api/votes/" + BOB)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isForbidden());

        // Alice attempts to read Bob's vote summary
        mockMvc.perform(get("/api/votes/summary/" + BOB)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isForbidden());

        // Alice attempts to delete Bob's vote
        mockMvc.perform(delete("/api/votes")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .param("user_id", BOB)
                        .param("source", "github")
                        .param("platform_issue_id", CATALOG_ISSUE_ID))
                .andExpect(status().isForbidden());

        // Alice attempts to read Bob's feedback
        mockMvc.perform(get("/api/feedback/" + BOB)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isForbidden());

        // Alice attempts to read Bob's user profile
        mockMvc.perform(get("/api/users/" + BOB)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isForbidden());

        // Alice attempts to update Bob's profile
        mockMvc.perform(put("/api/users/" + BOB)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target_difficulty\": 99.0}"))
                .andExpect(status().isForbidden());

        // Alice attempts to delete Bob's profile
        mockMvc.perform(delete("/api/users/" + BOB)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isForbidden());

        // Alice attempts to read Bob's recommendation snapshots
        mockMvc.perform(get("/api/recommendations/snapshots/" + BOB)
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE))))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 6. SERVER-SIDE SNAPSHOT RECONSTRUCTION (CLIENT SNAPSHOT IGNORED)
    // =========================================================================

    @Test
    @DisplayName("[Security] Client-supplied feature snapshot is ignored; reconstructed server-side from catalog")
    public void testServerSideSnapshotReconstruction() throws Exception {
        // Alice attempts to pass a spoofed feature snapshot with difficulty=99.9 and fake tech
        Map<String, Object> fakeSnapshot = Map.of(
                "difficulty", 99.9,
                "difficulty_available", true,
                "technologies", List.of("Haskell", "Fortran"),
                "issue_type", "spoofed_type"
        );

        Map<String, Object> voteReq = new LinkedHashMap<>();
        voteReq.put("source", "github");
        voteReq.put("platform_issue_id", CATALOG_ISSUE_ID);
        voteReq.put("vote", 1);
        voteReq.put("feature_snapshot", fakeSnapshot); // Spoofed!

        mockMvc.perform(post("/api/votes")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteReq)))
                .andExpect(status().isOk());

        // Verify the persisted record in MongoDB Atlas
        IssueVote saved = voteRepository.getVote(ALICE, "github", CATALOG_ISSUE_ID).orElseThrow();
        Map<String, Object> serverSnap = saved.getFeatureSnapshot();
        assertNotNull(serverSnap, "Server-side feature snapshot must be recorded");

        // The stored difficulty must be the catalog value (60.0), NOT the client's fake 99.9
        assertEquals(60.0, ((Number) serverSnap.get("difficulty")).doubleValue(), 0.001);
        assertEquals("enhancement", serverSnap.get("issue_type"));

        @SuppressWarnings("unchecked")
        List<String> techs = (List<String>) serverSnap.get("technologies");
        assertTrue(techs.contains("Python"), "Server-side snapshot must contain catalog technologies");
        assertFalse(techs.contains("Haskell"), "Client-supplied fake technologies must be discarded");
    }

    // =========================================================================
    // 7. MISSING CATALOG ISSUE REJECTION (400 BAD REQUEST)
    // =========================================================================

    @Test
    @DisplayName("[Security] Vote on non-catalog issue is rejected with 400 Bad Request")
    public void testNonCatalogIssueVoteRejectedWith400() throws Exception {
        Map<String, Object> voteReq = new LinkedHashMap<>();
        voteReq.put("source", "github");
        voteReq.put("platform_issue_id", "non_existent_issue_99999");
        voteReq.put("vote", 1);

        mockMvc.perform(post("/api/votes")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(voteReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Canonical issue record cannot be resolved")));
    }

    // =========================================================================
    // 8. PROTECTED DEMO-MODE BEHAVIOR
    // =========================================================================

    @Test
    @DisplayName("[Security] demo_mode=true does not bypass authentication on protected routes")
    public void testDemoModeDoesNotBypassAuthentication() throws Exception {
        // Unauthenticated demo_mode=true must still be rejected with 401
        mockMvc.perform(post("/api/recommendations/personalized")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"demo_mode\":true}"))
                .andExpect(status().isUnauthorized());

        // Authenticated demo_mode=true succeeds
        mockMvc.perform(post("/api/recommendations/personalized")
                        .with(jwt().jwt(j -> j.claim("user_id", ALICE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"demo_mode\":true,\"target_count\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(ALICE))
                .andExpect(jsonPath("$.data_source").value("sample_demonstration_candidates"));
    }

    // =========================================================================
    // 9. PUBLIC ENDPOINTS REMAIN ACCESSIBLE WITHOUT AUTH
    // =========================================================================

    @Test
    @DisplayName("[Security] Public endpoints remain accessible without credentials")
    public void testPublicEndpointsRemainAccessible() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("healthy"));

        mockMvc.perform(get("/api/issues"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/simulator/sample-data"))
                .andExpect(status().isOk());
    }
}
