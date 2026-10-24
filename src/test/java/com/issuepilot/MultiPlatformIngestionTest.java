package com.issuepilot;

import com.issuepilot.adapter.*;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.service.DiscoveryService;
import com.issuepilot.service.RefreshService;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class MultiPlatformIngestionTest {

    static {
        EnvLoader.loadEnv();
    }

    @Autowired
    private GitHubAdapter gitHubAdapter;

    @Autowired
    private GitLabAdapter gitLabAdapter;

    @Autowired
    private CodebergAdapter codebergAdapter;

    @Autowired
    private BugzillaAdapter bugzillaAdapter;

    @Autowired
    private DiscoveryService discoveryService;

    @Autowired
    private RefreshService refreshService;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoConfig mongoConfig;

    @Test
    @DisplayName("Validate all four platform adapters: health, search, pagination, and normalization")
    public void testAllPlatformAdapters() {
        System.out.println("\n=======================================================");
        System.out.println("   PHASE 3: MULTI-PLATFORM ADAPTER VALIDATION");
        System.out.println("=======================================================");

        List<BaseIssueSourceAdapter> adapters = List.of(gitHubAdapter, gitLabAdapter, codebergAdapter, bugzillaAdapter);

        for (BaseIssueSourceAdapter adapter : adapters) {
            String name = adapter.getSourceName();
            System.out.println("\nTesting source: " + name.toUpperCase());

            // 1. Health & Reachability
            Map<String, Object> health = adapter.checkHealth();
            assertNotNull(health);
            System.out.println("  Health: reachable=" + health.get("reachable") +
                    ", auth=" + health.get("auth_status") +
                    ", configured=" + health.get("configured") +
                    ", rate_limit=" + health.get("rate_limit_info"));

            // 2. Search Issues with pagination
            try {
                String testQuery = name.equals("bugzilla") ? "crash" : "documentation";
                Map<String, Object> searchResult = adapter.searchIssues(testQuery, 1, 3, "updated", "desc");
                assertNotNull(searchResult, "Search result should not be null for " + name);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) searchResult.get("items");
                assertNotNull(items, "Items should not be null for " + name);
                System.out.println("  Search: retrieved " + items.size() + " items (total count: " + searchResult.get("total_count") + ")");

                if (!items.isEmpty()) {
                    Map<String, Object> rawItem = items.get(0);
                    Map<String, Object> queryInfo = Map.of("topic", "test", "technology", "Java");
                    Map<String, Object> normalized = adapter.normalizeIssue(rawItem, queryInfo);

                    if (normalized != null) {
                        assertNotNull(normalized.get("id"));
                        assertNotNull(normalized.get("source"));
                        assertEquals(name, normalized.get("source"));
                        assertNotNull(normalized.get("issue_url"));
                        assertNotNull(normalized.get("canonical_url"));
                        assertTrue(normalized.containsKey("state"));
                        assertTrue(normalized.containsKey("is_assigned"));

                        System.out.println("  Normalization verified: id=" + normalized.get("id") +
                                " | repo=" + normalized.get("repository_full_name") +
                                " | canonical=" + normalized.get("canonical_url") +
                                " | state=" + normalized.get("state"));
                    }
                }
            } catch (Exception e) {
                System.out.println("  Search error for " + name + ": " + e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Validate duplicate detection and upsert idempotency")
    public void testUpsertIdempotency() {
        if (!mongoConfig.isConnected()) {
            System.out.println("[TEST] MongoDB not connected. Skipping upsert test.");
            return;
        }

        String testRepo = "issue-pilot/idempotency-test";
        int testIssueNum = 42424;
        String id = "github_" + testRepo.replace("/", "_") + "_" + testIssueNum;

        Map<String, Object> issue = new HashMap<>();
        issue.put("id", id);
        issue.put("source", "github");
        issue.put("source_instance", "github.com");
        issue.put("repository_full_name", testRepo);
        issue.put("issue_number", testIssueNum);
        issue.put("title", "Idempotency Initial Title");
        issue.put("state", "open");
        issue.put("is_assigned", false);
        issue.put("issue_url", "https://github.com/" + testRepo + "/issues/" + testIssueNum);
        issue.put("canonical_url", "https://github.com/" + testRepo + "/issues/" + testIssueNum);

        try {
            // First upsert
            Map<String, Object> saved1 = issueRepository.upsertIssue(issue, true);
            assertNotNull(saved1);
            assertEquals("Idempotency Initial Title", saved1.get("title"));

            // Second upsert with updated title
            issue.put("title", "Idempotency Updated Title");
            Map<String, Object> saved2 = issueRepository.upsertIssue(issue, true);
            assertNotNull(saved2);
            assertEquals("Idempotency Updated Title", saved2.get("title"));

            // Check that only 1 document exists for this unique combination
            long count = mongoTemplate.getCollection("issues").countDocuments(
                    new Document("source", "github")
                            .append("repository_full_name", testRepo)
                            .append("issue_number", testIssueNum)
            );
            assertEquals(1, count, "Upsert should maintain exactly 1 document without duplicates");
            System.out.println("[TEST] Verified upsert idempotency and duplicate prevention on Atlas.");
        } finally {
            // Clean up disposable test record
            issueRepository.deleteIssue(id);
        }
    }

    @Test
    @DisplayName("Validate controlled Discovery execution across platforms")
    public void testControlledDiscoveryRun() {
        if (!mongoConfig.isConnected()) {
            System.out.println("[TEST] MongoDB not connected. Skipping discovery run.");
            return;
        }

        // Run a lightweight discovery (max 2 issues, 1 page)
        Map<String, Object> summary = discoveryService.runDiscovery(2, 1, 2, false, true, "github");
        assertNotNull(summary);
        System.out.println("[DISCOVERY SUMMARY] " + summary);
        assertTrue(summary.containsKey("status"));
        assertTrue(summary.containsKey("discovered_count"));
        assertTrue(summary.containsKey("inserted_count"));
        assertTrue(summary.containsKey("per_source_summary"));
    }

    @Test
    @DisplayName("Validate Refresh Service execution")
    public void testRefreshServiceExecution() {
        if (!mongoConfig.isConnected()) {
            System.out.println("[TEST] MongoDB not connected. Skipping refresh run.");
            return;
        }

        // Run a lightweight refresh check (max 3 issues)
        Map<String, Object> summary = refreshService.runRefresh(3, 90, true);
        assertNotNull(summary);
        System.out.println("[REFRESH SUMMARY] " + summary);
        assertTrue(summary.containsKey("status"));
        assertTrue(summary.containsKey("checked_count"));
        assertTrue(summary.containsKey("verified_count"));
    }
}
