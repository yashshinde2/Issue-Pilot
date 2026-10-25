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

    @Autowired
    private com.issuepilot.repository.IssueRepository issueRepository;

    private static final String TEST_USER_1 = "synthetic_user_alpha";
    private static final String TEST_USER_2 = "synthetic_user_beta";

    @BeforeEach
    public void setupTestCatalogIssues() {
        cleanupTestVotes();

        // Register synthetic catalog issues in MongoDB Atlas for server-side snapshot reconstruction
        Map<String, Object> synth101 = new LinkedHashMap<>();
        synth101.put("id", "github_test-org_test-repo_101");
        synth101.put("source", "github");
        synth101.put("source_issue_id", "synth_101");
        synth101.put("native_id", "synth_101");
        synth101.put("issue_number", 101);
        synth101.put("repository_full_name", "web-frameworks/django");
        synth101.put("repository_language", "Python");
        synth101.put("title", "Synthetic Django Web Framework Issue");
        synth101.put("detected_technologies", List.of("Python", "Django", "FastAPI"));
        synth101.put("labels", List.of("api", "backend", "enhancement"));
        synth101.put("discovery_category", "feature");
        synth101.put("issue_url", "https://github.com/web-frameworks/django/issues/101");
        Map<String, Object> diff = Map.of(
                "scope_change_size", 60.0,
                "technical_complexity", 60.0,
                "historical_effort", 60.0,
                "dependency_arch_impact", 60.0,
                "coordination_complexity", 60.0
        );
        synth101.put("difficulty_evidence", diff);
        issueRepository.upsertIssue(synth101, false);

        Map<String, Object> synthConcurrent = new LinkedHashMap<>();
        synthConcurrent.put("id", "github_test-org_test-repo_102");
        synthConcurrent.put("source", "github");
        synthConcurrent.put("source_issue_id", "synth_concurrent_101");
        synthConcurrent.put("native_id", "synth_concurrent_101");
        synthConcurrent.put("issue_number", 102);
        synthConcurrent.put("repository_full_name", "web-frameworks/django");
        synthConcurrent.put("repository_language", "Python");
        synthConcurrent.put("title", "Synthetic Concurrent Update Issue");
        synthConcurrent.put("detected_technologies", List.of("Python", "FastAPI"));
        synthConcurrent.put("labels", List.of("backend", "concurrency"));
        synthConcurrent.put("discovery_category", "bug");
        synthConcurrent.put("issue_url", "https://github.com/web-frameworks/django/issues/102");
        synthConcurrent.put("difficulty_evidence", diff);
        issueRepository.upsertIssue(synthConcurrent, false);

        Map<String, Object> synthDense = new LinkedHashMap<>();
        synthDense.put("id", "github_test-org_test-repo_103");
        synthDense.put("source", "github");
        synthDense.put("source_issue_id", "synth_dense_1");
        synthDense.put("native_id", "synth_dense_1");
        synthDense.put("issue_number", 103);
        synthDense.put("repository_full_name", "web-frameworks/django");
        synthDense.put("repository_language", "Python");
        synthDense.put("title", "Synthetic Dense Feature Issue");
        synthDense.put("detected_technologies", List.of("Python", "Django"));
        synthDense.put("labels", List.of("backend", "orm"));
        synthDense.put("discovery_category", "enhancement");
        synthDense.put("issue_url", "https://github.com/web-frameworks/django/issues/103");
        Map<String, Object> diff75 = Map.of(
                "scope_change_size", 75.0,
                "technical_complexity", 75.0,
                "historical_effort", 75.0,
                "dependency_arch_impact", 75.0,
                "coordination_complexity", 75.0
        );
        synthDense.put("difficulty_evidence", diff75);
        issueRepository.upsertIssue(synthDense, false);
    }

    @AfterEach
    public void cleanupTestCatalogIssues() {
        cleanupTestVotes();
        try {
            issueRepository.deleteIssue("github_test-org_test-repo_101");
            issueRepository.deleteIssue("github_test-org_test-repo_102");
            issueRepository.deleteIssue("github_test-org_test-repo_103");
        } catch (Exception ignored) {}
    }

    public void cleanupTestVotes() {
        // Clean up only our synthetic test users to prevent polluting database
        try {
            voteRepository.removeVote(TEST_USER_1, "github", "synth_101");
            voteRepository.removeVote(TEST_USER_1, "github", "synth_102");
            voteRepository.removeVote(TEST_USER_2, "github", "synth_101");
            voteRepository.removeVote(TEST_USER_1, "bugzilla", "synth_bz_1");
            voteRepository.removeVote(TEST_USER_1, "github", "synth_concurrent_101");
            voteRepository.removeVote(TEST_USER_1, "github", "synth_dense_1");
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
                "Python-candidate affinity = " + scorePy + ", C++-candidate affinity = " + scoreCpp);
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

    @Test
    @DisplayName("[Audit] Cross-User Authorization Bypass Prevention on All Endpoints")
    public void testCrossUserAuthorizationBypassPrevention() {
        VoteController.VoteRequest req = new VoteController.VoteRequest();
        req.user_id = TEST_USER_1;
        req.source = "github";
        req.platform_issue_id = "synth_auth_1";
        req.vote = 1;

        // 1. Cross-user POST /api/votes must be rejected with 403 Forbidden
        ResponseStatusException postEx = assertThrows(ResponseStatusException.class, () -> {
            voteController.recordVote(req, "unauthorized_attacker");
        });
        assertEquals(403, postEx.getStatusCode().value());

        // 2. Cross-user GET /api/votes/summary/{userId} must be rejected with 403 Forbidden
        ResponseStatusException summaryEx = assertThrows(ResponseStatusException.class, () -> {
            voteController.getVoteSummary(TEST_USER_1, "unauthorized_attacker");
        });
        assertEquals(403, summaryEx.getStatusCode().value());

        // 3. Cross-user POST /api/votes/affinity must be rejected with 403 Forbidden
        VoteController.AffinityEvaluationRequest evalReq = new VoteController.AffinityEvaluationRequest();
        evalReq.user_id = TEST_USER_1;
        evalReq.candidates = List.of(Map.of("id", "c1", "source", "github", "platform_issue_id", "1"));

        ResponseStatusException evalEx = assertThrows(ResponseStatusException.class, () -> {
            voteController.evaluateAffinity(evalReq, "unauthorized_attacker");
        });
        assertEquals(403, evalEx.getStatusCode().value());

        System.out.println("[TEST] Cross-user authorization bypass prevention verified across all endpoints.");
    }

    @Test
    @DisplayName("[Audit] Concurrent Vote Updates Preserve Single Record Idempotently Without Exception")
    public void testConcurrentVoteUpdates() throws Exception {
        final int numThreads = 10;
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
        final java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(numThreads);
        final java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);

        try {
            for (int i = 0; i < numThreads; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        VoteController.VoteRequest req = new VoteController.VoteRequest();
                        req.user_id = TEST_USER_1;
                        req.source = "github";
                        req.platform_issue_id = "synth_concurrent_101";
                        // Alternate likes and dislikes
                        req.vote = (idx % 2 == 0) ? 1 : -1;
                        req.feature_snapshot = Map.of(
                                "technologies", List.of("Python", "FastAPI"),
                                "difficulty", 50.0 + idx,
                                "difficulty_available", true
                        );
                        voteController.recordVote(req, null);
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release all threads simultaneously
            startLatch.countDown();
            boolean finished = doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(finished, "Concurrent vote recording tasks should finish within 10 seconds");
            assertEquals(0, errorCount.get(), "No thread should fail with MongoWriteException or DuplicateKeyException");

            // Verify that exactly 1 current vote record exists in the repository
            Optional<IssueVote> voteOpt = voteRepository.getVote(TEST_USER_1, "github", "synth_concurrent_101");
            assertTrue(voteOpt.isPresent(), "Current vote record must be present");
            assertTrue(voteOpt.get().getVote() == 1 || voteOpt.get().getVote() == -1, "Vote value must be valid");

            List<IssueVote> userVotes = voteRepository.getUserVotes(TEST_USER_1);
            long matchCount = userVotes.stream()
                    .filter(v -> "synth_concurrent_101".equals(v.getPlatformIssueId()))
                    .count();
            assertEquals(1, matchCount, "Concurrent updates must never produce duplicate vote records");

            System.out.println("[TEST] Concurrent atomic upsert verified successfully without race condition.");
        } finally {
            executor.shutdown();
            voteRepository.removeVote(TEST_USER_1, "github", "synth_concurrent_101");
        }
    }

    @Test
    @DisplayName("[Audit] Missing Feature Semantics One-Sided Renormalization")
    public void testMissingFeatureSemanticsOneSided() {
        // Candidate has technologies but empty labels and unavailable difficulty
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("id", "cand_sparse_features");
        candidate.put("source", "github");
        candidate.put("platform_issue_id", "synth_sparse_1");
        candidate.put("profile", Map.of(
                "technologies", List.of("Python", "Django"),
                "labels", Collections.emptyList(),
                "difficulty", -1.0,
                "difficulty_available", false
        ));

        // Liked issue has technologies, labels, and difficulty
        VoteController.VoteRequest likeReq = new VoteController.VoteRequest();
        likeReq.user_id = TEST_USER_1;
        likeReq.source = "github";
        likeReq.platform_issue_id = "synth_dense_1";
        likeReq.vote = 1;
        likeReq.feature_snapshot = Map.of(
                "technologies", List.of("Python", "Django"),
                "labels", List.of("backend", "orm"),
                "issue_type", "enhancement",
                "difficulty", 75.0,
                "difficulty_available", true
        );
        voteController.recordVote(likeReq, null);

        VoteController.AffinityEvaluationRequest evalReq = new VoteController.AffinityEvaluationRequest();
        evalReq.user_id = TEST_USER_1;
        evalReq.candidates = List.of(candidate);

        Map<String, Object> evalResp = voteController.evaluateAffinity(evalReq, null);
        assertNotNull(evalResp);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) evalResp.get("results");
        assertEquals(1, results.size());
        Map<String, Object> res = results.get(0);

        double evidence = ((Number) res.get("positive_evidence")).doubleValue();
        double affinity = ((Number) res.get("affinity_score")).doubleValue();

        // Technology matches 1.0. All missing features on candidate are omitted without penalty.
        // Similarity is 1.0, Positive evidence = 1.0, Affinity = 100 * 1 / (1 + 1) = 50.0
        assertEquals(1.0, evidence, 0.01, "One-sided missing features must renormalize without penalty");
        assertEquals(50.0, affinity, 0.01, "Affinity must be exactly 50.0 for unit evidence");

        System.out.println("[TEST] One-sided missing feature renormalization verified successfully.");
        voteRepository.removeVote(TEST_USER_1, "github", "synth_dense_1");
    }
}
