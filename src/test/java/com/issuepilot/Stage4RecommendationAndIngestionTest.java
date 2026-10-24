package com.issuepilot;

import com.issuepilot.controller.IngestionController;
import com.issuepilot.controller.RecommendationController;
import com.issuepilot.service.AuditService;
import com.issuepilot.service.BenchmarkService;
import com.issuepilot.service.EvaluationService;
import com.issuepilot.service.LiveVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class Stage4RecommendationAndIngestionTest {

    @Autowired
    private RecommendationController recommendationController;

    @Autowired
    private IngestionController ingestionController;

    @Autowired
    private LiveVerifier liveVerifier;

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private BenchmarkService benchmarkService;

    @Autowired
    private AuditService auditService;

    @Test
    @DisplayName("Verify LiveVerifier Backfill with simulated exclusions")
    public void testLiveVerifierBackfill() {
        liveVerifier.clearCache();

        List<Map<String, Object>> rankedCandidates = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            Map<String, Object> c = new HashMap<>();
            c.put("id", "test_issue_" + i);
            c.put("issue_number", 100 + i);
            c.put("repository_full_name", "test-org/repo-" + i);
            c.put("title", "Test Candidate Issue #" + i);
            c.put("source", "github");
            c.put("final_score", 90.0 - (i * 2.0));
            rankedCandidates.add(c);
        }

        // Test demo mode backfill (5 items target)
        LiveVerifier.VerificationAndBackfillResult res = liveVerifier.verifyAndBackfill(
                rankedCandidates,
                5,
                10,
                true,
                5,
                1
        );

        assertNotNull(res);
        assertEquals(5, res.getDeliveredRecommendations().size());
        assertEquals(8, res.getAuditedRecords().size());

        // Check delivered ranks 1 to 5
        for (int i = 0; i < 5; i++) {
            Map<String, Object> d = res.getDeliveredRecommendations().get(i);
            assertEquals(i + 1, d.get("delivered_rank"));
            assertEquals("included", d.get("decision"));
        }

        // Check audited records beyond target count are marked "skipped"
        assertEquals("skipped", res.getAuditedRecords().get(5).get("decision"));
        System.out.println("[TEST] LiveVerifier backfill and rank preservation verified!");
    }

    @Test
    @DisplayName("Verify Recommendation-Quality Evaluation Report (NDCG@5, Precision@5, Lift)")
    public void testEvaluationReport() {
        Map<String, Object> report = evaluationService.runEvaluation();
        assertNotNull(report);
        assertTrue(report.containsKey("macro_ndcg_lift"));
        assertTrue(report.containsKey("issue_pilot_metrics"));
        assertTrue(report.containsKey("baseline_metrics"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ipMetrics = (Map<String, Object>) report.get("issue_pilot_metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> baseMetrics = (Map<String, Object>) report.get("baseline_metrics");

        double ipNdcg = ((Number) ipMetrics.get("macro_ndcg@5")).doubleValue();
        double baseNdcg = ((Number) baseMetrics.get("macro_ndcg@5")).doubleValue();
        double lift = ((Number) report.get("macro_ndcg_lift")).doubleValue();

        assertTrue(ipNdcg > 0.5, "Authoritative C++ ranking NDCG should be strong (> 0.5)");
        assertTrue(lift >= 0.0, "Authoritative C++ ranking should equal or outperform naive baseline");

        System.out.println("[TEST] Evaluation Report verified: Issue Pilot NDCG@5 = " + ipNdcg +
                ", Baseline NDCG@5 = " + baseNdcg + ", Lift = +" + lift);
    }

    @Test
    @DisplayName("Verify Personalized Recommendations API Endpoint in Demo Mode")
    public void testPersonalizedRecommendationsDemoMode() {
        RecommendationController.RecommendationRequest req = new RecommendationController.RecommendationRequest();
        req.demo_mode = true;
        req.target_count = 5;

        Map<String, Object> resp = recommendationController.getPersonalizedRecommendations(req);
        assertNotNull(resp);
        assertEquals("sample_demonstration_candidates", resp.get("data_source"));
        assertEquals(5, resp.get("delivered_count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs = (List<Map<String, Object>>) resp.get("recommendations");
        assertEquals(5, recs.size());

        for (Map<String, Object> r : recs) {
            assertTrue(r.containsKey("final_score"));
            assertTrue(r.containsKey("delivered_rank"));
            assertTrue(r.containsKey("title"));
        }
        System.out.println("[TEST] RecommendationController demo mode verified successfully!");
    }

    @Test
    @DisplayName("Verify Ingestion Endpoints: Platforms, Health, Status, Rate-Limits")
    public void testIngestionObservability() {
        Map<String, Object> platforms = ingestionController.getPlatformDetails();
        assertNotNull(platforms);
        assertTrue(platforms.containsKey("platforms"));

        Map<String, Object> health = ingestionController.getPlatformsHealth();
        assertNotNull(health);
        assertTrue(health.containsKey("overall_status"));

        Map<String, Object> status = ingestionController.getPoolStatus();
        assertNotNull(status);
        assertTrue(status.containsKey("scheduler"));

        Map<String, Object> rateLimit = ingestionController.getRateLimits("all");
        assertNotNull(rateLimit);
        assertTrue(rateLimit.containsKey("github"));
        assertTrue(rateLimit.containsKey("gitlab"));
        assertTrue(rateLimit.containsKey("codeberg"));
        assertTrue(rateLimit.containsKey("bugzilla"));

        System.out.println("[TEST] Ingestion multi-platform observability endpoints verified!");
    }

    @Test
    @DisplayName("Verify Database Integrity Audit and Benchmark Services")
    public void testAuditAndBenchmark() {
        Map<String, Object> audit = auditService.auditDatabase();
        assertNotNull(audit);
        assertTrue(audit.containsKey("connected"));

        Map<String, Object> benchmark = benchmarkService.runFullBenchmark();
        assertNotNull(benchmark);
        assertTrue(benchmark.containsKey("environment"));
        assertTrue(benchmark.containsKey("cpp_engine_benchmark"));

        System.out.println("[TEST] AuditService and BenchmarkService verified successfully!");
    }
}
