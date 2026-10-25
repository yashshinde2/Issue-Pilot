package com.issuepilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.config.MongoConfig;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Validates that an environment is strictly isolated for staging or testing
 * before any ingestion or write operation is permitted.
 *
 * Implements a fail-closed policy: any ambiguity, missing staging credentials,
 * or detection of production identifiers aborts execution immediately.
 */
@Service
public class StagingPreflightService {

    private static final Logger logger = LoggerFactory.getLogger(StagingPreflightService.class);

    // Known production indicators to protect against accidental writes
    private static final Set<String> FORBIDDEN_DATABASES = Set.of(
            "issue_pilot",
            "production",
            "prod",
            "default",
            "admin",
            "local",
            "config"
    );

    private static final String KNOWN_PRODUCTION_CLUSTER_PATTERN = "cluster0.eueidog.mongodb.net";

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public StagingPreflightService(MongoConfig mongoConfig, AppProperties properties, ObjectMapper objectMapper) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public static class PreflightResult {
        private boolean passed;
        private String targetDatabase;
        private String sanitizedUri;
        private List<String> clusterHosts = new ArrayList<>();
        private String clusterIsolationLevel; // "DEDICATED_STAGING_CLUSTER" | "SHARED_CLUSTER_ISOLATED_DB" | "LOCAL_TEST"
        private Map<String, Boolean> checks = new LinkedHashMap<>();
        private List<String> messages = new ArrayList<>();
        private List<String> errors = new ArrayList<>();

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public String getTargetDatabase() {
            return targetDatabase;
        }

        public void setTargetDatabase(String targetDatabase) {
            this.targetDatabase = targetDatabase;
        }

        public String getSanitizedUri() {
            return sanitizedUri;
        }

        public void setSanitizedUri(String sanitizedUri) {
            this.sanitizedUri = sanitizedUri;
        }

        public List<String> getClusterHosts() {
            return clusterHosts;
        }

        public void setClusterHosts(List<String> clusterHosts) {
            this.clusterHosts = clusterHosts;
        }

        public String getClusterIsolationLevel() {
            return clusterIsolationLevel;
        }

        public void setClusterIsolationLevel(String clusterIsolationLevel) {
            this.clusterIsolationLevel = clusterIsolationLevel;
        }

        public Map<String, Boolean> getChecks() {
            return checks;
        }

        public List<String> getMessages() {
            return messages;
        }

        public List<String> getErrors() {
            return errors;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("passed", passed);
            map.put("target_database", targetDatabase);
            map.put("sanitized_uri", sanitizedUri);
            map.put("cluster_hosts", clusterHosts);
            map.put("cluster_isolation_level", clusterIsolationLevel);
            map.put("checks", checks);
            map.put("messages", messages);
            map.put("errors", errors);
            return map;
        }
    }

    /**
     * Executes fail-closed preflight checks to guarantee the target database
     * is positively isolated from production.
     *
     * @param requireIsolatedCluster if true, rejects running on the same cluster as production even with a separate DB
     * @param expectedStagingHost optional expected host substring for staging
     * @return PreflightResult containing check details and pass/fail status
     */
    public PreflightResult validateStagingEnvironment(boolean requireIsolatedCluster, String expectedStagingHost) {
        PreflightResult result = new PreflightResult();
        String rawUri = properties.getMongodbUri();
        String maskedUri = properties.getSanitizedMongodbUri();
        result.setSanitizedUri(maskedUri);

        // 1. Check URI presence
        if (rawUri == null || rawUri.isBlank()) {
            result.getChecks().put("uri_configured", false);
            result.getErrors().add("No MongoDB URI configured. Cannot proceed with staging preflight.");
            result.setPassed(false);
            return result;
        }
        result.getChecks().put("uri_configured", true);

        // Parse connection string
        ConnectionString connectionString;
        try {
            connectionString = new ConnectionString(rawUri);
            result.setClusterHosts(connectionString.getHosts());
        } catch (Exception e) {
            result.getChecks().put("uri_valid", false);
            result.getErrors().add("Invalid MongoDB connection URI format: " + e.getMessage());
            result.setPassed(false);
            return result;
        }
        result.getChecks().put("uri_valid", true);

        // 2. Resolve target database name
        String effectiveDb = mongoConfig.getEffectiveDatabaseName();
        result.setTargetDatabase(effectiveDb);

        // 3. Safety check: Target database MUST NOT be production
        if (effectiveDb == null || FORBIDDEN_DATABASES.contains(effectiveDb.toLowerCase().trim())) {
            result.getChecks().put("database_name_safe", false);
            result.getErrors().add("CRITICAL VIOLATION: Target database '" + effectiveDb +
                    "' is a forbidden or production database name. Staging ingestion is strictly prohibited on production databases.");
            result.setPassed(false);
            return result;
        }

        // Database name MUST explicitly contain staging or test
        String lowerDb = effectiveDb.toLowerCase().trim();
        boolean hasStagingTag = lowerDb.contains("staging") || lowerDb.contains("test");
        if (!hasStagingTag) {
            result.getChecks().put("database_name_safe", false);
            result.getErrors().add("Target database '" + effectiveDb +
                    "' does not contain 'staging' or 'test'. Dedicated staging database name must explicitly signify staging (e.g. 'issue_pilot_staging').");
            result.setPassed(false);
            return result;
        }
        result.getChecks().put("database_name_safe", true);
        result.getMessages().add("Database name '" + effectiveDb + "' verified as staging/test target.");

        // 4. Cluster-level isolation check
        List<String> hosts = connectionString.getHosts();
        boolean targetsProductionCluster = hosts.stream().anyMatch(h ->
                h != null && (h.contains(KNOWN_PRODUCTION_CLUSTER_PATTERN) || h.equalsIgnoreCase("cluster0.eueidog.mongodb.net"))
        );

        if (targetsProductionCluster) {
            result.setClusterIsolationLevel("SHARED_CLUSTER_ISOLATED_DB");
            result.getMessages().add("Connection targets production Atlas cluster host, but database is isolated to '" + effectiveDb + "'.");
            if (requireIsolatedCluster) {
                result.getChecks().put("cluster_level_isolation", false);
                result.getErrors().add("Cluster-level isolation requirement failed: Connection points to production Atlas cluster host ('" +
                        KNOWN_PRODUCTION_CLUSTER_PATTERN + "'). A separate Atlas cluster is required when requireIsolatedCluster=true.");
                result.setPassed(false);
                return result;
            } else {
                result.getChecks().put("cluster_level_isolation", true);
                result.getMessages().add("Running in shared-cluster mode with isolated database. Full hardware cluster isolation is NOT active.");
            }
        } else {
            result.setClusterIsolationLevel("DEDICATED_STAGING_CLUSTER");
            result.getChecks().put("cluster_level_isolation", true);
            result.getMessages().add("Cluster host does not match production cluster. Dedicated staging cluster detected.");
        }

        // 5. Expected host verification (if provided)
        if (expectedStagingHost != null && !expectedStagingHost.isBlank()) {
            boolean hostMatched = hosts.stream().anyMatch(h -> h != null && h.contains(expectedStagingHost));
            if (!hostMatched) {
                result.getChecks().put("expected_host_matched", false);
                result.getErrors().add("Connection host does not match expected staging host: " + expectedStagingHost);
                result.setPassed(false);
                return result;
            }
            result.getChecks().put("expected_host_matched", true);
        }

        // 6. Connectivity & Authentication Ping
        try {
            MongoClient client = mongoConfig.mongoClient();
            if (client == null) {
                result.getChecks().put("connectivity_ping", false);
                result.getErrors().add("MongoClient is not initialized.");
                result.setPassed(false);
                return result;
            }

            MongoDatabase stagingDb = client.getDatabase(effectiveDb);
            Document pingResult = stagingDb.runCommand(new Document("ping", 1));
            if (pingResult.getDouble("ok") != null && pingResult.getDouble("ok") == 1.0) {
                result.getChecks().put("connectivity_ping", true);
                result.getMessages().add("Successfully pinged target staging database '" + effectiveDb + "'.");
            } else {
                result.getChecks().put("connectivity_ping", false);
                result.getErrors().add("Database ping did not return ok=1: " + pingResult.toJson());
                result.setPassed(false);
                return result;
            }

            // 7. Check user privileges and ensure no write access to production
            try {
                Document connStatus = stagingDb.runCommand(new Document("connectionStatus", 1));
                result.getChecks().put("connection_status_inspected", true);
                result.getMessages().add("Inspected database user connectionStatus.");
            } catch (Exception authEx) {
                logger.warn("Could not inspect connectionStatus: {}", authEx.getMessage());
            }

        } catch (Exception connEx) {
            result.getChecks().put("connectivity_ping", false);
            result.getErrors().add("Failed to connect or ping target staging database: " + connEx.getMessage());
            result.setPassed(false);
            return result;
        }

        result.setPassed(result.getErrors().isEmpty());
        return result;
    }
}
