package com.issuepilot;

import com.issuepilot.config.EnvLoader;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.controller.FeedbackController;
import com.issuepilot.controller.IssueController;
import com.issuepilot.controller.SimulatorController;
import com.issuepilot.controller.UserController;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.model.FeedbackEvent;
import com.issuepilot.repository.RecommendationRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class Stage3DataAndApisTest {

    @BeforeAll
    public static void setUpEnv() {
        EnvLoader.loadEnv();
    }

    @Autowired
    private UserController userController;

    @Autowired
    private IssueController issueController;

    @Autowired
    private FeedbackController feedbackController;

    @Autowired
    private SimulatorController simulatorController;

    @Autowired
    private RecommendationRepository recommendationRepository;

    @Autowired
    private MongoConfig mongoConfig;

    @Test
    public void testSimulatorSampleDataAndRanking() {
        // 1. Sample data
        Map<String, Object> sampleData = simulatorController.getSampleData();
        assertNotNull(sampleData);
        assertTrue(sampleData.containsKey("profiles"));
        assertTrue(sampleData.containsKey("issues"));
        assertTrue(sampleData.containsKey("default_weights"));

        List<?> profiles = (List<?>) sampleData.get("profiles");
        assertEquals(3, profiles.size());

        List<?> issues = (List<?>) sampleData.get("issues");
        assertEquals(5, issues.size());

        // 2. Ranking via C++ engine
        Map<String, Object> rankReq = new HashMap<>();
        rankReq.put("user_id", "contributor-fullstack");
        Map<String, Object> rankResult = simulatorController.rankSimulator(rankReq);

        assertNotNull(rankResult);
        assertEquals("1.0.0-phase1", rankResult.get("algorithm_version"));
        List<?> results = (List<?>) rankResult.get("results");
        assertNotNull(results);
        assertEquals(5, results.size());

        // 3. What-If experiments
        Map<String, Object> expResult = simulatorController.getWhatIfExperiments();
        assertNotNull(expResult);
        List<?> experiments = (List<?>) expResult.get("experiments");
        assertNotNull(experiments);
        assertEquals(7, experiments.size());
        System.out.println("[TEST] Verified Simulator ranking & What-If experiments!");
    }

    @Test
    public void testUserProfileCrudAndAtlasPersistence() {
        if (!mongoConfig.isConnected()) {
            System.out.println("[TEST] MongoDB Atlas not connected. Skipping profile CRUD test.");
            return;
        }

        String testUserId = "test-user-" + UUID.randomUUID().toString().substring(0, 8);
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(testUserId);
        profile.setCapability(82.5);
        profile.getSkills().put("Java", new ContributorSkill("Java", 88.0, 0.95));
        profile.getSkills().put("Spring", new ContributorSkill("Spring", 85.0, 0.90));

        // Create
        var created = userController.createUserProfile(profile).getBody();
        assertNotNull(created);
        assertEquals(testUserId, created.get("user_id"));

        // Get
        var retrieved = userController.getUserProfile(testUserId);
        assertNotNull(retrieved);
        assertEquals(testUserId, retrieved.get("user_id"));

        // Update
        Map<String, Object> updates = new HashMap<>();
        updates.put("capability", 90.0);
        var updated = userController.updateUserProfile(testUserId, updates);
        assertNotNull(updated);
        assertEquals(90.0, ((Number) updated.get("capability")).doubleValue());

        // Upsert
        profile.setCapability(95.0);
        var upserted = userController.upsertUserProfile(profile);
        assertNotNull(upserted);
        assertEquals(95.0, ((Number) upserted.get("capability")).doubleValue());

        // Delete
        var delRes = userController.deleteUserProfile(testUserId);
        assertEquals("success", delRes.get("status"));
        System.out.println("[TEST] Contributor Profile CRUD verified successfully on MongoDB Atlas!");
    }

    @Test
    public void testIssueCandidateUpsertAndFilteredQuery() {
        if (!mongoConfig.isConnected()) {
            System.out.println("[TEST] MongoDB Atlas not connected. Skipping issue query test.");
            return;
        }

        String testIssueId = "test-issue-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> issueMap = new LinkedHashMap<>();
        issueMap.put("id", testIssueId);
        issueMap.put("source", "github");
        issueMap.put("repository_full_name", "issue-pilot/test-repo");
        issueMap.put("issue_number", 77777);
        issueMap.put("title", "Test Candidate Issue For Stage 3");
        issueMap.put("body", "Test candidate body");
        issueMap.put("issue_url", "https://github.com/issue-pilot/test-repo/issues/77777");
        issueMap.put("state", "open");
        issueMap.put("is_assigned", false);
        issueMap.put("detected_technologies", List.of("Java", "Spring"));

        // Upsert #1
        var saved1 = issueController.upsertIssueRecord(issueMap);
        assertNotNull(saved1);
        assertEquals(testIssueId, saved1.get("id"));

        // Idempotent upsert #2
        var saved2 = issueController.upsertIssueRecord(issueMap);
        assertNotNull(saved2);
        assertEquals(testIssueId, saved2.get("id"));

        // Query issues
        var queryRes = issueController.queryIssues("open", false, "Java", null, null, "test-repo", "github", 10, 0);
        assertNotNull(queryRes);
        List<?> issues = (List<?>) queryRes.get("issues");
        assertNotNull(issues);
        assertTrue(issues.size() >= 1);

        // Delete
        var delRes = issueController.deleteIssueById(testIssueId);
        assertEquals("success", delRes.get("status"));
        System.out.println("[TEST] Issue candidate upsert & filtering verified successfully on Atlas!");
    }

    @Test
    public void testRecommendationsAndFeedbackPersistence() {
        if (!mongoConfig.isConnected()) {
            System.out.println("[TEST] MongoDB Atlas not connected. Skipping recommendations/feedback test.");
            return;
        }

        String testUserId = "test-feedback-user-" + UUID.randomUUID().toString().substring(0, 8);
        String testIssueId = "test-feedback-issue-" + UUID.randomUUID().toString().substring(0, 8);
        String testRunId = "run_" + System.currentTimeMillis();

        // 1. Save recommendations snapshot
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", testIssueId);
        rec.put("native_id", "5555");
        rec.put("repository_full_name", "test/repo");
        rec.put("rank", 1);
        rec.put("final_score", 88.5);
        rec.put("decision", "included");
        rec.put("explanation", "High skill match for Java");

        recommendationRepository.saveRecommendations(testUserId, List.of(rec), "1.0.0-phase1", testRunId, null);

        var userRecs = recommendationRepository.getUserRecommendations(testUserId, 10);
        assertEquals(1, userRecs.size());
        assertEquals(testIssueId, userRecs.get(0).get("issue_id"));

        var runRecs = recommendationRepository.getRunRecommendations(testRunId);
        assertEquals(1, runRecs.size());
        assertEquals(testIssueId, runRecs.get(0).get("issue_id"));

        // 2. Feedback recording
        FeedbackEvent event = new FeedbackEvent();
        event.setUserId(testUserId);
        event.setIssueId(testIssueId);
        event.setEventType("accepted");

        var fbRes = feedbackController.recordFeedback(event);
        assertEquals("success", fbRes.get("status"));

        var userFb = feedbackController.getUserFeedback(testUserId, 10);
        assertNotNull(userFb);
        Map<?, ?> counts = (Map<?, ?>) userFb.get("counts");
        assertEquals(1, ((Number) counts.get("accepted")).intValue());

        System.out.println("[TEST] Recommendations audit snapshots and feedback persistence verified successfully!");
    }
}
