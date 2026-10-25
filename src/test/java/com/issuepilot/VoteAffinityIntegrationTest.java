package com.issuepilot;

import com.issuepilot.config.EnvLoader;
import com.issuepilot.controller.VoteController;
import com.issuepilot.model.IssueVote;
import com.issuepilot.repository.VoteRepository;
import com.issuepilot.service.VoteAffinityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java integration tests for Vote persistence, uniqueness, updates, removal,
 * user authorization isolation, C++ engine communication, and error handling.
 *
 * NOTE: These tests use labeled synthetic fixtures to test system mechanisms deterministically.
 */
@SpringBootTest
public class VoteAffinityIntegrationTest {

    static {
        EnvLoader.loadEnv();
    }

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private VoteAffinityService voteAffinityService;

    @Autowired
    private VoteController voteController;

    private static final String TEST_USER_1 = "synthetic_user_alpha";
    private static final String TEST_USER_2 = "synthetic_user_beta";

    @BeforeEach
    @AfterEach
    public void cleanupTestVotes() {
        // Clean up only our synthetic test users to prevent polluting database
        try {
            voteRepository.removeVote(TEST_USER_1, "github", "synth_101");
            voteRepository.removeVote(TEST_USER_1, "github", "synth_102");
            voteRepository.removeVote(TEST_USER_2, "github", "synth_101");
            voteRepository.removeVote(TEST_USER_1, "bugzilla", "synth_bz_1");
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("[Synthetic] Vote Persistence and Idempotent Uniqueness")
    public void testVotePersistenceAndUniqueness() {
        VoteController.VoteRequest req = new VoteController.VoteRequest();
        req.user_id = TEST_USER_1;
        req.source = "github";
        req.platform_issue_id = "synth_101";
        req.vote = 1; // Like
        req.feature_snapshot = Map.of(
                "technologies", List.of("Python", "FastAPI"),
                "labels", List.of("enhancement"),
                "issue_type", "feature",
                "difficulty", 55.0,
                "difficulty_available", true
        );

        // 1. Initial Vote Recording
        Map<String, Object> resp = voteController.recordVote(req);
        assertNotNull(resp);
        assertEquals("success", resp.get("status"));

        List<IssueVote> votesAfterFirst = voteRepository.getUserVotes(TEST_USER_1);
        assertEquals(1, votesAfterFirst.size());
        assertEquals(1, votesAfterFirst.get(0).getVote());

        // 2. Idempotent Repeated Vote
        Map<String, Object> resp2 = voteController.recordVote(req);
        assertNotNull(resp2);
        assertEquals("success", resp2.get("status"));

        List<IssueVote> votesAfterDup = voteRepository.getUserVotes(TEST_USER_1);
        // Compound unique index enforces exactly 1 record per user and issue
        assertEquals(1, votesAfterDup.size(), "Repeating identical vote must be idempotent and not create duplicate records");
        assertEquals(1, votesAfterDup.get(0).getVote());

        System.out.println("[TEST] Vote persistence and compound uniqueness verified successfully.");
    }

    @Test
    @DisplayName("[Synthetic] Changing Vote Sign Updates Signal Rather Than Duplicating")
    public void testVoteUpdateChangesSignNotDuplication() {
        // Step 1: User likes an issue (+1)
        VoteController.VoteRequest likeReq = new VoteController.VoteRequest();
        likeReq.user_id = TEST_USER_1;
        likeReq.source = "github";
        likeReq.platform_issue_id = "synth_101";
        likeReq.vote = 1;
        voteController.recordVote(likeReq);

        IssueVote initialVote = voteRepository.getVote(TEST_USER_1, "github", "synth_101").orElseThrow();
        assertEquals(1, initialVote.getVote());

        // Step 2: User changes opinion to dislike (-1)
        VoteController.VoteRequest dislikeReq = new VoteController.VoteRequest();
        dislikeReq.user_id = TEST_USER_1;
        dislikeReq.source = "github";
        dislikeReq.platform_issue_id = "synth_101";
        dislikeReq.vote = -1;
        voteController.recordVote(dislikeReq);

        // Step 3: Verify single updated record with vote = -1
        List<IssueVote> votes = voteRepository.getUserVotes(TEST_USER_1);
        assertEquals(1, votes.size(), "Changing a vote must update the existing record rather than accumulating");
        assertEquals(-1, votes.get(0).getVote(), "Current preference signal must become negative");

        Map<String, Integer> counts = voteRepository.countUserVotes(TEST_USER_1);
        assertEquals(0, counts.get("likes"));
        assertEquals(1, counts.get("dislikes"));
        assertEquals(1, counts.get("total"));

        System.out.println("[TEST] Changing vote from like to dislike properly flips preference signal.");
    }

    @Test
    @DisplayName("[Synthetic] Vote Removal Completely Removes Preference Influence")
    public void testVoteRemoval() {
        VoteController.VoteRequest req = new VoteController.VoteRequest();
        req.user_id = TEST_USER_1;
        req.source = "github";
        req.platform_issue_id = "synth_101";
        req.vote = 1;
        voteController.recordVote(req);

        assertTrue(voteRepository.getVote(TEST_USER_1, "github", "synth_101").isPresent());

        // Remove the vote
        Map<String, Object> delResp = voteController.removeVote(TEST_USER_1, "github", "synth_101", null);
        assertNotNull(delResp);
        assertEquals("success", delResp.get("status"));
        assertEquals(true, delResp.get("removed"));

        // Verify vote is gone
        assertTrue(voteRepository.getVote(TEST_USER_1, "github", "synth_101").isEmpty());
        assertEquals(0, voteRepository.getUserVotes(TEST_USER_1).size());

        System.out.println("[TEST] Vote removal completely cleans up vote record.");
    }

    @Test
    @DisplayName("[Synthetic] Enforce User Isolation and Authorization")
    public void testUserIsolationAndAuthorization() {
        // User Alpha votes +1 on issue 101
        VoteController.VoteRequest reqA = new VoteController.VoteRequest();
        reqA.user_id = TEST_USER_1;
        reqA.source = "github";
        reqA.platform_issue_id = "synth_101";
        reqA.vote = 1;
        voteController.recordVote(reqA);

        // User Beta votes -1 on same issue 101
        VoteController.VoteRequest reqB = new VoteController.VoteRequest();
        reqB.user_id = TEST_USER_2;
        reqB.source = "github";
        reqB.platform_issue_id = "synth_101";
        reqB.vote = -1;
        voteController.recordVote(reqB);

        // Query Alpha's votes
        List<IssueVote> alphaVotes = voteRepository.getUserVotes(TEST_USER_1);
        assertEquals(1, alphaVotes.size());
        assertEquals(1, alphaVotes.get(0).getVote());

        // Query Beta's votes
        List<IssueVote> betaVotes = voteRepository.getUserVotes(TEST_USER_2);
        assertEquals(1, betaVotes.size());
        assertEquals(-1, betaVotes.get(0).getVote());

        // Cross-user access attempt with mismatching header must throw 403 Forbidden
        assertThrows(ResponseStatusException.class, () -> {
            voteController.getUserVotes(TEST_USER_2, TEST_USER_1);
        });

        assertThrows(ResponseStatusException.class, () -> {
            voteController.removeVote(TEST_USER_2, "github", "synth_101", TEST_USER_1);
        });

        System.out.println("[TEST] Strict user isolation and authorization enforced.");
    }

    @Test
    @DisplayName("[Synthetic] C++ Engine Communication, Structured Affinity & Explanations")
    public void testCppAffinityCommunicationAndGroundedExplanations() {
        // Setup liked vote for synthetic user
        VoteController.VoteRequest likeReq = new VoteController.VoteRequest();
        likeReq.user_id = TEST_USER_1;
        likeReq.source = "github";
        likeReq.platform_issue_id = "synth_101";
        likeReq.vote = 1;
        likeReq.feature_snapshot = Map.of(
                "technologies", List.of("Python", "Django"),
                "labels", List.of("api", "backend"),
                "issue_type", "feature",
                "difficulty", 60.0,
                "difficulty_available", true,
                "repository_full_name", "web-frameworks/django"
        );
        voteController.recordVote(likeReq);

        // Prepare synthetic candidates
        Map<String, Object> candidatePython = new LinkedHashMap<>();
        candidatePython.put("id", "cand_py");
        candidatePython.put("source", "github");
        candidatePython.put("platform_issue_id", "201");
        candidatePython.put("profile", Map.of(
                "technologies", List.of("Python", "Django"),
                "labels", List.of("api"),
                "issue_type", "feature",
                "difficulty", 65.0,
                "difficulty_available", true,
                "repository_full_name", "web-frameworks/django"
        ));

        Map<String, Object> candidateCpp = new LinkedHashMap<>();
        candidateCpp.put("id", "cand_cpp");
        candidateCpp.put("source", "github");
        candidateCpp.put("platform_issue_id", "202");
        candidateCpp.put("profile", Map.of(
                "technologies", List.of("C++", "CMake"),
                "labels", List.of("compiler"),
                "issue_type", "bug",
                "difficulty", 90.0,
                "difficulty_available", true,
                "repository_full_name", "llvm/llvm-project"
        ));

        VoteController.AffinityEvaluationRequest evalReq = new VoteController.AffinityEvaluationRequest();
        evalReq.user_id = TEST_USER_1;
        evalReq.candidates = List.of(candidatePython, candidateCpp);

        Map<String, Object> evalResp = voteController.evaluateAffinity(evalReq);
        assertNotNull(evalResp);
        assertEquals("2.0.0-affinity", evalResp.get("algorithm_version"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) evalResp.get("results");
        assertEquals(2, results.size());

        // Candidate Python should have strong positive affinity
        Map<String, Object> resPy = results.get(0);
        assertEquals("cand_py", resPy.get("id"));
        double scorePy = ((Number) resPy.get("affinity_score")).doubleValue();
        double confPy = ((Number) resPy.get("confidence")).doubleValue();
        assertTrue(scorePy > 30.0, "Matching Python candidate should have positive affinity");
        assertTrue(confPy > 0.0, "Matching Python candidate should have non-zero confidence");

        @SuppressWarnings("unchecked")
        List<String> explanations = (List<String>) resPy.get("explanations");
        assertFalse(explanations.isEmpty(), "Must provide grounded explanations");
        assertTrue(explanations.stream().anyMatch(e -> e.contains("technology stack")),
                "Explanation must mention matched technology stack");

        // Candidate C++ should have near-zero affinity
        Map<String, Object> resCpp = results.get(1);
        assertEquals("cand_cpp", resCpp.get("id"));
        double scoreCpp = ((Number) resCpp.get("affinity_score")).doubleValue();
        assertTrue(scoreCpp < 10.0, "Unrelated C++ candidate should have minimal affinity");

        System.out.println("[TEST] C++ affinity evaluation and grounded explanations verified successfully: " +
                "Python affinity = " + scorePy + ", C++ affinity = " + scoreCpp);
    }

    @Test
    @DisplayName("[Synthetic] Malformed Input & Empty Vote History Safe Handling")
    public void testMalformedInputAndEmptyHistoryHandling() {
        // User with no vote history
        String emptyUser = "user_with_no_votes_12345";
        Map<String, Object> cand = Map.of(
                "id", "cand_test",
                "source", "github",
                "platform_issue_id", "999"
        );

        VoteController.AffinityEvaluationRequest req = new VoteController.AffinityEvaluationRequest();
        req.user_id = emptyUser;
        req.candidates = List.of(cand);

        Map<String, Object> resp = voteController.evaluateAffinity(req);
        assertNotNull(resp);
        assertEquals(0, resp.get("total_votes_considered"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        assertEquals(1, results.size());
        assertEquals(0.0, ((Number) results.get(0).get("affinity_score")).doubleValue());
        assertEquals(0.0, ((Number) results.get(0).get("confidence")).doubleValue());

        // Bad request validation
        assertThrows(ResponseStatusException.class, () -> {
            voteController.recordVote(null);
        });

        VoteController.VoteRequest invalidVoteVal = new VoteController.VoteRequest();
        invalidVoteVal.user_id = "test";
        invalidVoteVal.source = "github";
        invalidVoteVal.platform_issue_id = "1";
        invalidVoteVal.vote = 5; // Invalid! Must be +1 or -1
        assertThrows(ResponseStatusException.class, () -> {
            voteController.recordVote(invalidVoteVal);
        });

        System.out.println("[TEST] Empty history and input validation error handling verified successfully.");
    }

    @Test
    @DisplayName("[Synthetic] Feature Snapshot Extraction Integrity")
    public void testFeatureSnapshotExtraction() {
        Map<String, Object> issueMap = new LinkedHashMap<>();
        issueMap.put("id", "github_org_repo_55");
        issueMap.put("source", "github");
        issueMap.put("source_issue_id", "55");
        issueMap.put("issue_number", 55);
        issueMap.put("repository_full_name", "org/repo");
        issueMap.put("labels", List.of("bug", "critical"));
        issueMap.put("discovery_category", "bug");
        issueMap.put("repository_language", "Java");
        issueMap.put("difficulty_evidence", Map.of(
                "scope_change_size", 60.0,
                "technical_complexity", 60.0,
                "historical_effort", 60.0,
                "dependency_arch_impact", 60.0,
                "coordination_complexity", 60.0
        ));

        Map<String, Object> snapshot = voteAffinityService.extractFeatureSnapshot(issueMap);
        assertNotNull(snapshot);
        assertEquals("github", snapshot.get("source"));
        assertEquals("55", snapshot.get("platform_issue_id"));
        assertEquals("org/repo", snapshot.get("repository_full_name"));
        assertEquals("bug", snapshot.get("issue_type"));
        assertEquals(60.0, ((Number) snapshot.get("difficulty")).doubleValue());
        assertTrue(Boolean.TRUE.equals(snapshot.get("difficulty_available")));

        @SuppressWarnings("unchecked")
        List<String> meta = (List<String>) snapshot.get("structured_metadata");
        assertTrue(meta.contains("Java"));
        assertTrue(meta.contains("bug"));

        System.out.println("[TEST] Feature snapshot extraction integrity verified.");
    }
}
