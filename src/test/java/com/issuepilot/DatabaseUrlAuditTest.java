package com.issuepilot;

import com.issuepilot.config.MongoConfig;
import com.issuepilot.service.LiveVerifier;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.issuepilot.config.EnvLoader;

import java.util.*;

@SpringBootTest
public class DatabaseUrlAuditTest {

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
    @DisplayName("Audit MongoDB Atlas Issue URLs and Source Identities")
    public void testAuditIssueUrlsAndSourceIdentities() throws Exception {
        if (!mongoConfig.isConnected() || mongoTemplate == null) {
            System.out.println("[AUDIT] MongoDB Atlas unavailable. Skipping audit.");
            return;
        }

        var collection = mongoTemplate.getCollection("issues");
        long totalIssues = collection.countDocuments();
        System.out.println("\n=======================================================");
        System.out.println("   PHASE 2 INITIAL AUDIT: ISSUE URLS & IDENTITIES");
        System.out.println("=======================================================");
        System.out.println("Total stored issue records: " + totalIssues);

        Map<String, Integer> countBySource = new LinkedHashMap<>();
        Map<String, Integer> countByState = new LinkedHashMap<>();
        Map<String, Integer> countByStaleStatus = new LinkedHashMap<>();

        Map<String, Integer> missingUrls = new LinkedHashMap<>();
        Map<String, Integer> apiUrlAsBrowserUrl = new LinkedHashMap<>();
        Map<String, Integer> malformedUrls = new LinkedHashMap<>();
        Map<String, Integer> mismatchedIdentities = new LinkedHashMap<>();
        Map<String, Integer> validUrlStructure = new LinkedHashMap<>();

        Map<String, List<Document>> bySourceDocs = new LinkedHashMap<>();

        for (Document doc : collection.find()) {
            String source = doc.getString("source");
            if (source == null) source = "unknown";
            source = source.toLowerCase().trim();

            countBySource.put(source, countBySource.getOrDefault(source, 0) + 1);

            String state = doc.getString("state");
            countByState.put(state != null ? state : "null", countByState.getOrDefault(state != null ? state : "null", 0) + 1);

            String stale = doc.getString("stale_status");
            countByStaleStatus.put(stale != null ? stale : "null", countByStaleStatus.getOrDefault(stale != null ? stale : "null", 0) + 1);

            String issueUrl = doc.getString("issue_url");
            String canonicalUrl = doc.getString("canonical_url");
            String targetUrl = (canonicalUrl != null && !canonicalUrl.isBlank()) ? canonicalUrl : issueUrl;

            bySourceDocs.computeIfAbsent(source, k -> new ArrayList<>()).add(doc);

            if (targetUrl == null || targetUrl.isBlank()) {
                missingUrls.put(source, missingUrls.getOrDefault(source, 0) + 1);
            } else {
                // Check if API endpoint was stored instead of browser URL
                boolean isApi = targetUrl.contains("api.github.com")
                        || targetUrl.contains("/api/v4/")
                        || targetUrl.contains("/rest/")
                        || targetUrl.contains("/api/v1/");

                if (isApi) {
                    apiUrlAsBrowserUrl.put(source, apiUrlAsBrowserUrl.getOrDefault(source, 0) + 1);
                }

                // Check scheme and basic URL structure
                if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                    malformedUrls.put(source, malformedUrls.getOrDefault(source, 0) + 1);
                } else {
                    validUrlStructure.put(source, validUrlStructure.getOrDefault(source, 0) + 1);
                }

                // Identity check: does url number match stored issue_number?
                Integer issueNumber = doc.getInteger("issue_number");
                if (issueNumber != null && issueNumber > 0) {
                    String numStr = String.valueOf(issueNumber);
                    if (!targetUrl.contains(numStr)) {
                        mismatchedIdentities.put(source, mismatchedIdentities.getOrDefault(source, 0) + 1);
                    }
                }
            }
        }

        System.out.println("\n1. Record Counts by Platform:");
        countBySource.forEach((k, v) -> System.out.println("   - " + k + ": " + v));

        System.out.println("\n2. Record Counts by State:");
        countByState.forEach((k, v) -> System.out.println("   - " + k + ": " + v));

        System.out.println("\n3. Record Counts by Stale Status:");
        countByStaleStatus.forEach((k, v) -> System.out.println("   - " + k + ": " + v));

        System.out.println("\n4. URL Integrity Analysis:");
        System.out.println("   - Valid URL Structure: " + validUrlStructure);
        System.out.println("   - Missing URLs: " + missingUrls);
        System.out.println("   - API URL stored as Browser URL: " + apiUrlAsBrowserUrl);
        System.out.println("   - Malformed URLs (no http/https): " + malformedUrls);
        System.out.println("   - Mismatched Issue Number in URL: " + mismatchedIdentities);

        // Print representative URL samples for each platform
        System.out.println("\n5. URL Format Samples by Platform:");
        for (String src : bySourceDocs.keySet()) {
            List<Document> docs = bySourceDocs.get(src);
            System.out.println("   [" + src.toUpperCase() + "] (Sample of up to 3):");
            for (int i = 0; i < Math.min(3, docs.size()); i++) {
                Document d = docs.get(i);
                System.out.println("     id: " + d.getString("id") +
                        " | repo: " + d.getString("repository_full_name") +
                        " | num: " + d.get("issue_number") +
                        " | url: " + d.getString("issue_url") +
                        (d.getString("canonical_url") != null ? " | canonical: " + d.getString("canonical_url") : ""));
            }
        }

        // Live API Verification on a representative sample of 20 issues per platform
        System.out.println("\n6. Live Source-Specific API Verification (Sampled Audit):");
        Map<String, Map<String, Integer>> verificationOutcomes = new LinkedHashMap<>();

        for (String src : List.of("github", "gitlab", "codeberg", "bugzilla")) {
            List<Document> docs = bySourceDocs.getOrDefault(src, Collections.emptyList());
            Map<String, Integer> outcomes = new LinkedHashMap<>();
            outcomes.put("open_and_verified", 0);
            outcomes.put("closed", 0);
            outcomes.put("moved_or_redirected", 0);
            outcomes.put("deleted_or_unavailable_404", 0);
            outcomes.put("inaccessible_or_private_403", 0);
            outcomes.put("rate_limited", 0);
            outcomes.put("transient_timeout", 0);
            outcomes.put("inconclusive", 0);

            int sampleCount = Math.min(10, docs.size());
            for (int i = 0; i < sampleCount; i++) {
                Document d = docs.get(i);
                Map<String, Object> candidate = new HashMap<>(d);
                try {
                    LiveVerifier.VerificationResult vRes = liveVerifier.verifyCandidate(candidate, 5, 2);
                    if (vRes.isValid()) {
                        outcomes.put("open_and_verified", outcomes.get("open_and_verified") + 1);
                    } else if (vRes.isInconclusive()) {
                        if (vRes.getResponseMetadata() != null && Boolean.TRUE.equals(vRes.getResponseMetadata().get("rate_limited"))) {
                            outcomes.put("rate_limited", outcomes.get("rate_limited") + 1);
                        } else {
                            outcomes.put("inconclusive", outcomes.get("inconclusive") + 1);
                        }
                    } else {
                        String r = vRes.getReason() != null ? vRes.getReason().toLowerCase() : "";
                        if (r.contains("closed") || r.contains("resolved")) {
                            outcomes.put("closed", outcomes.get("closed") + 1);
                        } else if (r.contains("404") || r.contains("no longer exists")) {
                            outcomes.put("deleted_or_unavailable_404", outcomes.get("deleted_or_unavailable_404") + 1);
                        } else if (r.contains("403") || r.contains("forbidden") || r.contains("restricted")) {
                            outcomes.put("inaccessible_or_private_403", outcomes.get("inaccessible_or_private_403") + 1);
                        } else {
                            outcomes.put("closed", outcomes.get("closed") + 1);
                        }
                    }
                } catch (Exception e) {
                    outcomes.put("inconclusive", outcomes.get("inconclusive") + 1);
                }
            }

            verificationOutcomes.put(src, outcomes);
            System.out.println("   [" + src.toUpperCase() + "] Audited " + sampleCount + " records: " + outcomes);
        }

        System.out.println("=======================================================\n");
    }
}
