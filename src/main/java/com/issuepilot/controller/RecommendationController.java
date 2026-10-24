package com.issuepilot.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.RankingWeights;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.RecommendationRepository;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.service.CppRankingClient;
import com.issuepilot.service.LiveVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private static final Logger logger = LoggerFactory.getLogger(RecommendationController.class);

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final IssueRepository issueRepository;
    private final UserRepository userRepository;
    private final RecommendationRepository recommendationRepository;
    private final CppRankingClient cppRankingClient;
    private final LiveVerifier liveVerifier;
    private final SimulatorController simulatorController;
    private final ObjectMapper objectMapper;

    public RecommendationController(
            MongoConfig mongoConfig,
            AppProperties properties,
            IssueRepository issueRepository,
            UserRepository userRepository,
            RecommendationRepository recommendationRepository,
            CppRankingClient cppRankingClient,
            LiveVerifier liveVerifier,
            SimulatorController simulatorController,
            ObjectMapper objectMapper
    ) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.issueRepository = issueRepository;
        this.userRepository = userRepository;
        this.recommendationRepository = recommendationRepository;
        this.cppRankingClient = cppRankingClient;
        this.liveVerifier = liveVerifier;
        this.simulatorController = simulatorController;
        this.objectMapper = objectMapper;
    }

    public static class RecommendationRequest {
        public String user_id;
        public Map<String, Object> profile;
        public Map<String, Object> weights;
        public int target_count = 5;
        public Integer shortlist_size;
        public int verification_budget = 30;
        public boolean prefer_live_atlas = true;
        public boolean demo_mode = false;
        public String technology_filter;
        public String source_filter;
        public Double min_difficulty;
        public Double max_difficulty;
        public String run_id;
        public Integer timeout_seconds;
        public Integer max_retries;
    }

    @PostMapping("/personalized")
    public Map<String, Object> getPersonalizedRecommendations(@RequestBody RecommendationRequest req) {
        boolean isLive = !req.demo_mode && req.prefer_live_atlas;

        if (isLive && !mongoConfig.isConnected()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MongoDB Atlas is unavailable. Live recommendation requires an active database connection. Set demo_mode=true or prefer_live_atlas=false to evaluate bundled demonstration candidates."
            );
        }

        // 1. Resolve Contributor Profile
        Map<String, Object> profile = null;
        if (req.profile != null) {
            profile = req.profile;
        } else if (req.user_id != null && !req.user_id.isBlank()) {
            if (mongoConfig.isConnected()) {
                profile = userRepository.getUser(req.user_id);
            }
            if (profile == null) {
                if (isLive) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "User profile '" + req.user_id + "' not found in MongoDB Atlas. Create the profile first or specify demo_mode=true."
                    );
                }
                profile = simulatorController.getSampleProfiles().get(0);
            }
        } else {
            if (isLive) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Either 'profile' or 'user_id' must be provided for live recommendation."
                );
            }
            profile = simulatorController.getSampleProfiles().get(0);
        }

        // 2. Resolve Candidate Pool
        List<Map<String, Object>> candidates = new ArrayList<>();
        String dataSource = "atlas_candidates";

        if (req.demo_mode || !req.prefer_live_atlas) {
            dataSource = "sample_demonstration_candidates";
            candidates = new ArrayList<>(simulatorController.getSampleIssues());
        } else {
            try {
                candidates = issueRepository.queryIssues(
                        "open",
                        false,
                        req.technology_filter,
                        req.min_difficulty,
                        req.max_difficulty,
                        null,
                        req.source_filter,
                        100,
                        0
                );
            } catch (Exception e) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "MongoDB Atlas connection failure: " + e.getMessage()
                );
            }

            if (candidates.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No eligible open candidates found in MongoDB Atlas matching the specified criteria. Ingest candidates first or set demo_mode=true."
                );
            }
        }

        // 3. Authoritative C++ Engine Scoring & Ranking
        Map<String, Object> weightsMap = req.weights != null
                ? req.weights
                : objectMapper.convertValue(new RankingWeights(), new TypeReference<Map<String, Object>>() {});

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", profile);
        payload.put("weights", weightsMap);
        payload.put("candidates", candidates);

        Map<String, Object> engineResponse;
        try {
            engineResponse = cppRankingClient.executeRanking(payload);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "C++ ranking engine execution failed: " + e.getMessage()
            );
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rankedCandidates = (List<Map<String, Object>>) engineResponse.getOrDefault("results", Collections.emptyList());
        String modelVersion = (String) engineResponse.getOrDefault("algorithm_version", "1.0.0-phase1");

        // Preserve all candidate metadata from candidate pool
        Map<String, Map<String, Object>> lookup = new HashMap<>();
        for (Map<String, Object> c : candidates) {
            String cId = (String) c.get("id");
            if (cId != null) lookup.put(cId, c);
        }

        for (Map<String, Object> item : rankedCandidates) {
            String cId = (String) item.get("id");
            if (cId != null && lookup.containsKey(cId)) {
                Map<String, Object> orig = lookup.get(cId);
                for (Map.Entry<String, Object> e : orig.entrySet()) {
                    if (!item.containsKey(e.getKey())) {
                        item.put(e.getKey(), e.getValue());
                    }
                }
            }
            if (item.containsKey("scores") && item.get("scores") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sc = (Map<String, Object>) item.get("scores");
                if (sc.containsKey("final_score") && !item.containsKey("final_score")) {
                    item.put("final_score", sc.get("final_score"));
                }
            }
        }

        // 4. Live Multi-Platform Verification & Shortlist Backfill
        int effectiveBudget = req.shortlist_size != null
                ? req.shortlist_size
                : (req.verification_budget > 0 ? req.verification_budget : properties.getVerificationCandidateLimit());

        LiveVerifier.VerificationAndBackfillResult backfillResult = liveVerifier.verifyAndBackfill(
                rankedCandidates,
                req.target_count,
                effectiveBudget,
                req.demo_mode || !req.prefer_live_atlas,
                req.timeout_seconds,
                req.max_retries
        );

        List<Map<String, Object>> deliveredRecs = backfillResult.getDeliveredRecommendations();
        List<Map<String, Object>> auditedRecords = backfillResult.getAuditedRecords();
        Map<String, Object> metrics = backfillResult.getMetrics();

        // 5. Save Audit Snapshots to MongoDB Atlas for Idempotency and Reproducibility
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String runId = req.run_id != null && !req.run_id.isBlank()
                ? req.run_id
                : "run_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6);

        String userId = (String) profile.get("user_id");

        if (mongoConfig.isConnected() && !req.demo_mode) {
            try {
                recommendationRepository.saveRecommendations(
                        userId,
                        deliveredRecs,
                        modelVersion,
                        runId,
                        auditedRecords
                );
            } catch (Exception e) {
                logger.warn("Could not persist recommendation snapshot to Atlas: {}", e.getMessage());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("run_id", runId);
        response.put("data_source", dataSource);
        response.put("algorithm_version", modelVersion);
        response.put("user_id", userId);
        response.put("total_candidates_considered", candidates.size());
        response.put("total_ranked", rankedCandidates.size());
        response.put("shortlist_size", metrics.getOrDefault("shortlist_size", effectiveBudget));
        response.put("candidates_verified", metrics.getOrDefault("candidates_verified", 0));
        response.put("candidates_excluded", metrics.getOrDefault("candidates_excluded", 0));
        response.put("candidates_inconclusive", metrics.getOrDefault("candidates_inconclusive", 0));
        response.put("delivered_count", deliveredRecs.size());
        response.put("is_partial_result", metrics.getOrDefault("is_partial_result", false));
        response.put("partial_result_reason", metrics.get("partial_result_reason"));
        response.put("duration_ms", metrics.getOrDefault("duration_ms", 0L));
        response.put("recommendations", deliveredRecs);
        response.put("verified_recommendations", deliveredRecs); // backward compatibility
        response.put("generated_at", nowIso);

        return response;
    }

    @GetMapping("/snapshots/{userId}")
    public Map<String, Object> getUserSnapshots(
            @PathVariable String userId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        if (!mongoConfig.isConnected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MongoDB Atlas is unavailable.");
        }
        try {
            List<Map<String, Object>> snapshots = recommendationRepository.getUserRecommendations(userId, limit);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("snapshots", snapshots);
            res.put("count", snapshots.size());
            return res;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRunAudit(@PathVariable String runId) {
        if (!mongoConfig.isConnected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MongoDB Atlas is unavailable.");
        }
        try {
            List<Map<String, Object>> records = recommendationRepository.getRunRecommendations(runId);
            List<Map<String, Object>> delivered = new ArrayList<>();
            List<Map<String, Object>> rejected = new ArrayList<>();
            List<Map<String, Object>> skipped = new ArrayList<>();
            List<Map<String, Object>> inconclusive = new ArrayList<>();

            for (Map<String, Object> r : records) {
                String d = (String) r.get("decision");
                if ("included".equals(d)) delivered.add(r);
                else if ("rejected".equals(d)) rejected.add(r);
                else if ("skipped".equals(d)) skipped.add(r);
                else if ("inconclusive".equals(d)) inconclusive.add(r);
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("run_id", runId);
            res.put("total_records", records.size());
            res.put("delivered", delivered);
            res.put("rejected", rejected);
            res.put("skipped", skipped);
            res.put("inconclusive", inconclusive);
            res.put("audit_trail", records);
            return res;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
