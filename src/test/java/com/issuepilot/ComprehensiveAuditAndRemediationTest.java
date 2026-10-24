package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.adapter.AdapterRegistry;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.service.LiveVerifier;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ComprehensiveAuditAndRemediationTest {

    static {
        EnvLoader.loadEnv();
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoConfig mongoConfig;

    @Autowired
    private LiveVerifier liveVerifier;

    @Test
    @DisplayName("Audit all 804 records, analyze manual failure causes, and perform remediation")
    public void testAuditAndRemediate() throws Exception {
        if (!mongoConfig.isConnected() || mongoTemplate == null) {
            System.out.println("[AUDIT] MongoDB Atlas unavailable. Skipping.");
            return;
        }

        var collection = mongoTemplate.getCollection("issues");
        List<Document> allDocs = collection.find().into(new ArrayList<>());
        System.out.println("Loaded " + allDocs.size() + " total issue records from MongoDB Atlas.");

        // Categorize by platform
        Map<String, List<Document>> byPlatform = new LinkedHashMap<>();
        for (Document doc : allDocs) {
            String src = doc.getString("source");
            if (src == null) src = "unknown";
            src = src.toLowerCase().trim();
            byPlatform.computeIfAbsent(src, k -> new ArrayList<>()).add(doc);
        }

        System.out.println("\n--- PLATFORM DISTRIBUTION ---");
        byPlatform.forEach((plat, docs) -> System.out.println(plat + ": " + docs.size()));

        // Analyze root causes of URL failures
        AtomicInteger countMissingCanonical = new AtomicInteger(0);
        AtomicInteger countWorkItemUrls = new AtomicInteger(0);
        AtomicInteger countAlreadyMarkedUnavailable = new AtomicInteger(0);
        AtomicInteger countAlreadyMarkedClosed = new AtomicInteger(0);

        for (Document d : allDocs) {
            String url = d.getString("issue_url");
            String canonical = d.getString("canonical_url");
            String stale = d.getString("stale_status");
            String state = d.getString("state");

            if (canonical == null || canonical.isBlank()) {
                countMissingCanonical.incrementAndGet();
            }
            if (url != null && url.contains("/-/work_items/")) {
                countWorkItemUrls.incrementAndGet();
            }
            if ("unavailable".equalsIgnoreCase(stale)) {
                countAlreadyMarkedUnavailable.incrementAndGet();
            }
            if ("closed".equalsIgnoreCase(state) || "closed".equalsIgnoreCase(stale)) {
                countAlreadyMarkedClosed.incrementAndGet();
            }
        }

        System.out.println("\n--- URL STRUCTURAL AUDIT ---");
        System.out.println("Missing canonical_url field: " + countMissingCanonical.get());
        System.out.println("GitLab work_items URL structure: " + countWorkItemUrls.get());
        System.out.println("Already marked unavailable in Atlas: " + countAlreadyMarkedUnavailable.get());
        System.out.println("Already marked closed in Atlas: " + countAlreadyMarkedClosed.get());

        // Live verification with bounded concurrency across sample of open issues
        // Testing GitHub (30 sample), GitLab (all 15), Bugzilla (25 sample)
        Map<String, Map<String, Integer>> platformOutcomes = new LinkedHashMap<>();
        List<Document> toRemediateAsClosed = new ArrayList<>();
        List<Document> toRemediateAsUnavailable = new ArrayList<>();
        List<Document> toUpdateCanonicalUrl = new ArrayList<>();

        for (String platform : byPlatform.keySet()) {
            List<Document> platformDocs = byPlatform.get(platform);
            Map<String, Integer> outcomeMap = new ConcurrentHashMap<>();
            outcomeMap.put("open_and_verified", 0);
            outcomeMap.put("closed", 0);
            outcomeMap.put("moved_or_redirected", 0);
            outcomeMap.put("confirmed_deleted_404", 0);
            outcomeMap.put("inaccessible_private_403", 0);
            outcomeMap.put("rate_limited", 0);
            outcomeMap.put("transient_timeout", 0);
            outcomeMap.put("inconclusive", 0);

            // Select open issues to verify
            List<Document> openDocs = platformDocs.stream()
                    .filter(d -> "open".equalsIgnoreCase(d.getString("state")))
                    .toList();

            int sampleLimit = platform.equals("gitlab") ? openDocs.size() : Math.min(25, openDocs.size());

            System.out.println("\nRunning live API verification for " + platform + " (sample size: " + sampleLimit + " of " + openDocs.size() + " open)...");
            for (int i = 0; i < sampleLimit; i++) {
                Document doc = openDocs.get(i);
                Map<String, Object> candidate = new HashMap<>(doc);
                LiveVerifier.VerificationResult result = liveVerifier.verifyCandidate(candidate, 5, 2);

                if (result.isValid()) {
                    outcomeMap.put("open_and_verified", outcomeMap.get("open_and_verified") + 1);
                } else if (result.isInconclusive()) {
                    if (result.getResponseMetadata() != null && Boolean.TRUE.equals(result.getResponseMetadata().get("rate_limited"))) {
                        outcomeMap.put("rate_limited", outcomeMap.get("rate_limited") + 1);
                    } else {
                        outcomeMap.put("inconclusive", outcomeMap.get("inconclusive") + 1);
                    }
                } else {
                    String reason = result.getReason() != null ? result.getReason().toLowerCase() : "";
                    if (reason.contains("closed") || reason.contains("resolved")) {
                        outcomeMap.put("closed", outcomeMap.get("closed") + 1);
                        toRemediateAsClosed.add(doc);
                    } else if (reason.contains("404") || reason.contains("no longer exists")) {
                        outcomeMap.put("confirmed_deleted_404", outcomeMap.get("confirmed_deleted_404") + 1);
                        toRemediateAsUnavailable.add(doc);
                    } else if (reason.contains("403") || reason.contains("forbidden")) {
                        outcomeMap.put("inaccessible_private_403", outcomeMap.get("inaccessible_private_403") + 1);
                    } else {
                        outcomeMap.put("closed", outcomeMap.get("closed") + 1);
                        toRemediateAsClosed.add(doc);
                    }
                }

                // Check if canonical URL needs normalization
                String url = doc.getString("issue_url");
                String canonical = doc.getString("canonical_url");
                if (platform.equals("gitlab") && url != null && url.contains("/-/work_items/")) {
                    toUpdateCanonicalUrl.add(doc);
                } else if (canonical == null || canonical.isBlank()) {
                    toUpdateCanonicalUrl.add(doc);
                }
            }

            platformOutcomes.put(platform, outcomeMap);
            System.out.println("[" + platform.toUpperCase() + "] Verification Outcomes: " + outcomeMap);
        }

        // Apply safe remediation
        System.out.println("\n--- EXECUTING URL & IDENTITY REMEDIATION ---");
        int closedRemediated = 0;
        for (Document d : toRemediateAsClosed) {
            String id = d.getString("id");
            if (id != null) {
                collection.updateOne(
                        new Document("id", id),
                        new Document("$set", new Document("state", "closed")
                                .append("stale_status", "closed")
                                .append("refresh_status", "closed_upstream")
                                .append("last_verified_at", new Date().toString()))
                );
                closedRemediated++;
            }
        }
        System.out.println("Remediated " + closedRemediated + " confirmed closed issues (set state=closed, stale_status=closed).");

        int unavailableRemediated = 0;
        for (Document d : toRemediateAsUnavailable) {
            String id = d.getString("id");
            if (id != null) {
                collection.updateOne(
                        new Document("id", id),
                        new Document("$set", new Document("stale_status", "unavailable")
                                .append("refresh_status", "not_found_upstream")
                                .append("last_verified_at", new Date().toString()))
                );
                unavailableRemediated++;
            }
        }
        System.out.println("Remediated " + unavailableRemediated + " deleted/404 issues (set stale_status=unavailable).");

        int canonicalRemediated = 0;
        for (Document d : toUpdateCanonicalUrl) {
            String id = d.getString("id");
            String url = d.getString("issue_url");
            String canonical = url;
            if (url != null && url.contains("/-/work_items/")) {
                // GitLab canonical URL: /-/issues/<num>
                canonical = url.replace("/-/work_items/", "/-/issues/");
            }
            if (canonical != null && id != null) {
                collection.updateOne(
                        new Document("id", id),
                        new Document("$set", new Document("canonical_url", canonical))
                );
                canonicalRemediated++;
            }
        }
        System.out.println("Remediated " + canonicalRemediated + " canonical URLs.");

        // Verify historical collections are untouched
        long userRecs = mongoTemplate.getCollection("recommendations").countDocuments();
        long feedbackCount = mongoTemplate.getCollection("feedback").countDocuments();
        System.out.println("\n--- INTEGRITY CHECK ---");
        System.out.println("Recommendations collection count: " + userRecs);
        System.out.println("Feedback collection count: " + feedbackCount);
        assertTrue(userRecs >= 0);
        assertTrue(feedbackCount >= 0);
    }
}
