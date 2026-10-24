package com.issuepilot;

import com.issuepilot.config.AppProperties;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.controller.HealthController;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class Stage2FoundationTest {

    @BeforeAll
    public static void setUpEnv() {
        EnvLoader.loadEnv();
    }

    @Autowired
    private HealthController healthController;

    @Autowired
    private MongoConfig mongoConfig;

    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    @Autowired
    private AppProperties appProperties;

    @Test
    public void testHealthEndpointReturnsCorrectContract() {
        Map<String, Object> health = healthController.healthCheck();
        assertNotNull(health);
        assertTrue(health.containsKey("status"));
        assertTrue(health.containsKey("mongodb_atlas_connected"));
        assertTrue(health.containsKey("database_name"));
        assertTrue(health.containsKey("cpp_ranking_engine_available"));
        assertTrue(health.containsKey("algorithm_version"));
        assertTrue(health.containsKey("github_token_configured"));
        assertTrue(health.containsKey("ingestion_enabled"));
        assertTrue(health.containsKey("scheduler_active"));

        assertEquals(appProperties.getMongodbDatabase(), health.get("database_name"));
        System.out.println("[TEST] Health Endpoint Result: " + health);
    }

    @Test
    public void testAtlasReadAndDisposableWrite() {
        if (!mongoConfig.isConnected() || mongoTemplate == null) {
            System.out.println("[TEST] MongoDB Atlas not connected. Skipping disposable write.");
            return;
        }

        // 1. Read existing record count without modification
        long issuesCount = mongoTemplate.getCollection("issues").countDocuments();
        System.out.println("[TEST] Existing issues in database '" + appProperties.getMongodbDatabase() + "': " + issuesCount);
        assertTrue(issuesCount >= 0);

        // 2. Write a disposable test record
        String disposableId = "disposable_test_" + UUID.randomUUID();
        Document disposableDoc = new Document("id", disposableId)
                .append("source", "test_stage2")
                .append("repository_full_name", "issue-pilot/disposable-test")
                .append("issue_number", 999999)
                .append("title", "Disposable Stage 2 Test Record")
                .append("state", "open")
                .append("is_assigned", false)
                .append("issue_url", "https://example.com/test/999999");

        mongoTemplate.getCollection("issues").insertOne(disposableDoc);

        // 3. Verify it was read back
        Document found = mongoTemplate.getCollection("issues").find(new Document("id", disposableId)).first();
        assertNotNull(found, "Disposable record should be retrievable from MongoDB Atlas");
        assertEquals("Disposable Stage 2 Test Record", found.getString("title"));

        // 4. Safely remove ONLY the disposable test record
        mongoTemplate.getCollection("issues").deleteOne(new Document("id", disposableId));

        Document deleted = mongoTemplate.getCollection("issues").find(new Document("id", disposableId)).first();
        assertNull(deleted, "Disposable record should be cleanly removed");
        System.out.println("[TEST] Successfully verified non-destructive read and disposable write against Atlas!");
    }
}
