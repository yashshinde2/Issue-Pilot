package com.issuepilot.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.RankingWeights;
import com.issuepilot.service.CppRankingClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final CppRankingClient cppRankingClient;
    private final ObjectMapper objectMapper;
    private List<Map<String, Object>> sampleProfiles;
    private List<Map<String, Object>> sampleIssues;

    public SimulatorController(CppRankingClient cppRankingClient, ObjectMapper objectMapper) {
        this.cppRankingClient = cppRankingClient;
        this.objectMapper = objectMapper;
        initSampleData();
    }

    private void initSampleData() {
        // Read sample profiles and issues from bundled dataset or define exact fixtures matching simulator.py
        this.sampleProfiles = buildSampleProfiles();
        this.sampleIssues = buildSampleIssues();
    }

    public List<Map<String, Object>> getSampleProfiles() {
        return sampleProfiles;
    }

    public List<Map<String, Object>> getSampleIssues() {
        return sampleIssues;
    }

    @GetMapping("/sample-data")
    public Map<String, Object> getSampleData() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("profiles", sampleProfiles);
        map.put("issues", sampleIssues);
        map.put("default_weights", objectMapper.convertValue(new RankingWeights(), new TypeReference<Map<String, Object>>() {}));
        return map;
    }

    @PostMapping("/rank")
    public Map<String, Object> rankSimulator(@RequestBody Map<String, Object> requestPayload) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();

            // 1. Resolve profile
            Object profileObj = requestPayload.get("profile");
            if (profileObj == null) {
                // Check if user_id or profileId provided
                String userId = (String) requestPayload.get("user_id");
                if (userId == null) {
                    userId = (String) requestPayload.get("profileId");
                }
                if (userId != null) {
                    for (Map<String, Object> p : sampleProfiles) {
                        if (userId.equals(p.get("user_id"))) {
                            profileObj = p;
                            break;
                        }
                    }
                }
                if (profileObj == null) {
                    profileObj = sampleProfiles.get(0);
                }
            }
            payload.put("profile", profileObj);

            // 2. Resolve weights
            Object weightsObj = requestPayload.get("weights");
            if (weightsObj == null) {
                weightsObj = objectMapper.convertValue(new RankingWeights(), new TypeReference<Map<String, Object>>() {});
            }
            payload.put("weights", weightsObj);

            // 3. Resolve candidates
            Object candidatesObj = requestPayload.get("candidates");
            if (candidatesObj == null || (candidatesObj instanceof List && ((List<?>) candidatesObj).isEmpty())) {
                candidatesObj = sampleIssues;
            }
            payload.put("candidates", candidatesObj);

            return cppRankingClient.executeRanking(payload);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Ranking engine error: " + e.getMessage());
        }
    }

    @GetMapping("/experiments")
    public Map<String, Object> getWhatIfExperiments() {
        try {
            return cppRankingClient.executeExperiments();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Experiments evaluation failed: " + e.getMessage());
        }
    }

    private static List<Map<String, Object>> buildSampleProfiles() {
        List<Map<String, Object>> profiles = new ArrayList<>();

        // Profile 1: contributor-fullstack
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("user_id", "contributor-fullstack");
        p1.put("capability", 76.0);
        p1.put("accepted_count", 12);
        p1.put("rejected_count", 8);
        p1.put("completed_count", 9);
        p1.put("similarity_to_history", 90.0);
        p1.put("has_feedback_history", true);
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("Python", Map.of("technology", "Python", "score", 90.0, "confidence", 0.95));
        s1.put("FastAPI", Map.of("technology", "FastAPI", "score", 80.0, "confidence", 0.90));
        s1.put("MongoDB", Map.of("technology", "MongoDB", "score", 70.0, "confidence", 0.85));
        s1.put("React", Map.of("technology", "React", "score", 60.0, "confidence", 0.80));
        s1.put("TypeScript", Map.of("technology", "TypeScript", "score", 65.0, "confidence", 0.80));
        p1.put("skills", s1);
        profiles.add(p1);

        // Profile 2: contributor-beginner-coldstart
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("user_id", "contributor-beginner-coldstart");
        p2.put("capability", 45.0);
        p2.put("accepted_count", 0);
        p2.put("rejected_count", 0);
        p2.put("completed_count", 0);
        p2.put("similarity_to_history", 0.0);
        p2.put("has_feedback_history", false);
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("Python", Map.of("technology", "Python", "score", 60.0, "confidence", 0.70));
        s2.put("Documentation", Map.of("technology", "Documentation", "score", 75.0, "confidence", 0.80));
        p2.put("skills", s2);
        profiles.add(p2);

        // Profile 3: contributor-systems-cpp
        Map<String, Object> p3 = new LinkedHashMap<>();
        p3.put("user_id", "contributor-systems-cpp");
        p3.put("capability", 88.0);
        p3.put("accepted_count", 15);
        p3.put("rejected_count", 3);
        p3.put("completed_count", 14);
        p3.put("similarity_to_history", 85.0);
        p3.put("has_feedback_history", true);
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("C++", Map.of("technology", "C++", "score", 95.0, "confidence", 0.95));
        s3.put("CMake", Map.of("technology", "CMake", "score", 85.0, "confidence", 0.90));
        s3.put("Rust", Map.of("technology", "Rust", "score", 70.0, "confidence", 0.80));
        p3.put("skills", s3);
        profiles.add(p3);

        return profiles;
    }

    private static List<Map<String, Object>> buildSampleIssues() {
        List<Map<String, Object>> issues = new ArrayList<>();

        // Issue 1: tiangolo/fastapi #4821
        Map<String, Object> i1 = new LinkedHashMap<>();
        i1.put("id", "sample-1");
        i1.put("native_id", "1001");
        i1.put("repository_full_name", "tiangolo/fastapi");
        i1.put("issue_number", 4821);
        i1.put("title", "Fix MongoDB async connection pooling and reconnect timeouts in FastAPI backend");
        i1.put("body", "When high concurrency requests hit the async database handler, MongoDB connection pool limits can cause socket disconnects without automatic reconnect.");
        i1.put("issue_url", "https://github.com/tiangolo/fastapi/issues/4821");
        i1.put("labels", Arrays.asList("bug", "async", "database"));
        i1.put("state", "open");
        i1.put("is_assigned", false);
        i1.put("technologies", Arrays.asList(
                Map.of("technology", "FastAPI", "raw_relevance", 5.0),
                Map.of("technology", "Python", "raw_relevance", 3.0),
                Map.of("technology", "MongoDB", "raw_relevance", 2.0)
        ));
        i1.put("difficulty_evidence", Map.of(
                "scope_change_size", 70.0, "technical_complexity", 75.0, "historical_effort", 65.0,
                "dependency_arch_impact", 80.0, "coordination_complexity", 50.0
        ));
        i1.put("responsiveness_evidence", Map.of(
                "available", true, "response_time_score", 90.0, "response_rate_score", 90.0,
                "pr_review_score", 85.0, "recent_maintainer_act", 90.0
        ));
        i1.put("health_evidence", Map.of(
                "available", true, "recent_commits", 90.0, "pr_activity", 80.0,
                "issue_activity", 85.0, "active_contributors", 75.0, "recent_releases", 80.0
        ));
        i1.put("freshness_evidence", Map.of("available", true, "age_score", 80.0, "activity_score", 100.0));
        issues.add(i1);

        // Issue 2: facebook/react #29810
        Map<String, Object> i2 = new LinkedHashMap<>();
        i2.put("id", "sample-2");
        i2.put("native_id", "1002");
        i2.put("repository_full_name", "facebook/react");
        i2.put("issue_number", 29810);
        i2.put("title", "TypeScript type definition error for useEffect cleanup return signature");
        i2.put("body", "Type definition for useEffect returns undefined instead of Destructor function under strict null check flags.");
        i2.put("issue_url", "https://github.com/facebook/react/issues/29810");
        i2.put("labels", Arrays.asList("typescript", "typing", "good-first-issue"));
        i2.put("state", "open");
        i2.put("is_assigned", false);
        i2.put("technologies", Arrays.asList(
                Map.of("technology", "TypeScript", "raw_relevance", 5.0),
                Map.of("technology", "React", "raw_relevance", 4.0)
        ));
        i2.put("difficulty_evidence", Map.of(
                "scope_change_size", 35.0, "technical_complexity", 45.0, "historical_effort", 40.0,
                "dependency_arch_impact", 50.0, "coordination_complexity", 30.0
        ));
        i2.put("responsiveness_evidence", Map.of(
                "available", true, "response_time_score", 85.0, "response_rate_score", 88.0,
                "pr_review_score", 90.0, "recent_maintainer_act", 88.0
        ));
        i2.put("health_evidence", Map.of(
                "available", true, "recent_commits", 95.0, "pr_activity", 90.0,
                "issue_activity", 92.0, "active_contributors", 95.0, "recent_releases", 85.0
        ));
        i2.put("freshness_evidence", Map.of("available", true, "age_score", 90.0, "activity_score", 90.0));
        issues.add(i2);

        // Issue 3: pallets/flask #5120
        Map<String, Object> i3 = new LinkedHashMap<>();
        i3.put("id", "sample-3");
        i3.put("native_id", "1003");
        i3.put("repository_full_name", "pallets/flask");
        i3.put("issue_number", 5120);
        i3.put("title", "Refactor blueprint routing dispatcher to support regex URL parameters");
        i3.put("body", "Allow custom converters or regex patterns inside url_prefix to support dynamic subdomain multi-tenant routing.");
        i3.put("issue_url", "https://github.com/pallets/flask/issues/5120");
        i3.put("labels", Arrays.asList("enhancement", "routing", "core"));
        i3.put("state", "open");
        i3.put("is_assigned", false);
        i3.put("technologies", Arrays.asList(
                Map.of("technology", "Python", "raw_relevance", 5.0)
        ));
        i3.put("difficulty_evidence", Map.of(
                "scope_change_size", 75.0, "technical_complexity", 80.0, "historical_effort", 70.0,
                "dependency_arch_impact", 85.0, "coordination_complexity", 60.0
        ));
        i3.put("responsiveness_evidence", Map.of(
                "available", true, "response_time_score", 75.0, "response_rate_score", 80.0,
                "pr_review_score", 75.0, "recent_maintainer_act", 80.0
        ));
        i3.put("health_evidence", Map.of(
                "available", true, "recent_commits", 80.0, "pr_activity", 75.0,
                "issue_activity", 78.0, "active_contributors", 70.0, "recent_releases", 75.0
        ));
        i3.put("freshness_evidence", Map.of("available", true, "age_score", 65.0, "activity_score", 60.0));
        issues.add(i3);

        // Issue 4: llvm/llvm-project #84129
        Map<String, Object> i4 = new LinkedHashMap<>();
        i4.put("id", "sample-4");
        i4.put("native_id", "1004");
        i4.put("repository_full_name", "llvm/llvm-project");
        i4.put("issue_number", 84129);
        i4.put("title", "Add CMake module support for MinGW GCC 6.3 C++14 abi compatibility");
        i4.put("body", "Fix linking issues with std::string copy-on-write ABI under older mingw runtime configurations.");
        i4.put("issue_url", "https://github.com/llvm/llvm-project/issues/84129");
        i4.put("labels", Arrays.asList("cmake", "build", "mingw"));
        i4.put("state", "open");
        i4.put("is_assigned", false);
        i4.put("technologies", Arrays.asList(
                Map.of("technology", "C++", "raw_relevance", 5.0),
                Map.of("technology", "CMake", "raw_relevance", 4.0)
        ));
        i4.put("difficulty_evidence", Map.of(
                "scope_change_size", 80.0, "technical_complexity", 85.0, "historical_effort", 80.0,
                "dependency_arch_impact", 90.0, "coordination_complexity", 75.0
        ));
        i4.put("responsiveness_evidence", Map.of(
                "available", true, "response_time_score", 80.0, "response_rate_score", 85.0,
                "pr_review_score", 80.0, "recent_maintainer_act", 85.0
        ));
        i4.put("health_evidence", Map.of(
                "available", true, "recent_commits", 98.0, "pr_activity", 95.0,
                "issue_activity", 90.0, "active_contributors", 95.0, "recent_releases", 90.0
        ));
        i4.put("freshness_evidence", Map.of("available", true, "age_score", 90.0, "activity_score", 90.0));
        issues.add(i4);

        // Issue 5: psf/requests #6390
        Map<String, Object> i5 = new LinkedHashMap<>();
        i5.put("id", "sample-5");
        i5.put("native_id", "1005");
        i5.put("repository_full_name", "psf/requests");
        i5.put("issue_number", 6390);
        i5.put("title", "Clarify proxy authentication header documentation and SSL adapter examples");
        i5.put("body", "The tutorial documentation lacks examples showing how to pass custom SSLContext to HTTPAdapter when authenticating through corporate proxy.");
        i5.put("issue_url", "https://github.com/psf/requests/issues/6390");
        i5.put("labels", Arrays.asList("docs", "good first issue"));
        i5.put("state", "open");
        i5.put("is_assigned", false);
        i5.put("technologies", Arrays.asList(
                Map.of("technology", "Python", "raw_relevance", 4.0),
                Map.of("technology", "Documentation", "raw_relevance", 5.0)
        ));
        i5.put("difficulty_evidence", Map.of(
                "scope_change_size", 25.0, "technical_complexity", 30.0, "historical_effort", 35.0,
                "dependency_arch_impact", 20.0, "coordination_complexity", 25.0
        ));
        i5.put("responsiveness_evidence", Map.of(
                "available", true, "response_time_score", 85.0, "response_rate_score", 88.0,
                "pr_review_score", 85.0, "recent_maintainer_act", 80.0
        ));
        i5.put("health_evidence", Map.of(
                "available", true, "recent_commits", 75.0, "pr_activity", 70.0,
                "issue_activity", 80.0, "active_contributors", 70.0, "recent_releases", 75.0
        ));
        i5.put("freshness_evidence", Map.of("available", true, "age_score", 50.0, "activity_score", 40.0));
        issues.add(i5);

        return issues;
    }
}
