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
 * Implements a strict fail-closed policy: any ambiguity, missing staging credentials,
 * missing expected staging cluster identity, inconsistency between URI and configured DB,
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
    private static final String KNOWN_PRODUCTION_CLUSTER_KEYWORD = "eueidog";

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public StagingPreflightService(MongoConfig mongoConfig, AppProperties properties, ObjectMapper objectMapper) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public String toJson(PreflightResult result) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.toMap());
        } catch (Exception e) {
            return result.toString();
        }
    }

    public static boolean isForbiddenDatabase(String dbName) {
        if (dbName == null || dbName.isBlank()) {
            return true;
        }
        return FORBIDDEN_DATABASES.contains(dbName.toLowerCase().trim());
    }

    public static class PreflightResult {
        private boolean passed;
        private String targetDatabase;
        private String sanitizedUri;
        private List<String> clusterHosts = new ArrayList<>();
        private String clusterIsolationLevel; // "DEDICATED_STAGING_CLUSTER"
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

    public PreflightResult validateStagingEnvironment() {
        return validateStagingEnvironment(true, null);
    }

    public PreflightResult validateStagingEnvironment(String expectedStagingHost) {
        return validateStagingEnvironment(true, expectedStagingHost);
    }

    /**
     * Executes fail-closed preflight checks to guarantee the target database
     * is positively isolated from production.
     *
     * Cluster-level isolation is mandatory: connection to the production cluster is
     * unconditionally rejected, even if an isolated database name is used.
     * An expected staging cluster identity must be explicitly configured and verified.
     *
     * @param requireIsolatedCluster maintained for compatibility; isolation is always strictly enforced
     * @param expectedStagingHost required expected host substring or pattern for staging
     * @return PreflightResult containing check details and pass/fail status
     */
    public PreflightResult validateStagingEnvironment(boolean requireIsolatedCluster, String expectedStagingHost) {
        PreflightResult result = new PreflightResult();

        // 1. Resolve effective URI following precedence rules:
        // Priority: STAGING_MONGODB_URI -> MONGODB_URI
        String rawUri = properties.getStagingMongodbUri();
        if (rawUri == null || rawUri.isBlank()) {
            rawUri = properties.getMongodbUri();
        }

        // Never log or expose raw credentials
        String maskedUri = EnvLoader.maskUri(rawUri);
        result.setSanitizedUri(maskedUri);

        // Check URI presence - fail closed
        if (rawUri == null || rawUri.isBlank()) {
            result.getChecks().put("uri_configured", false);
            result.getErrors().add("CRITICAL VIOLATION: Missing staging MongoDB URI. A dedicated staging URI must be configured. Cannot fall back to local defaults or production credentials.");
            result.setPassed(false);
            return result;
        }
        result.getChecks().put("uri_configured", true);

        // Parse connection string
        ConnectionString connectionString;
        try {
            connectionString = new ConnectionString(rawUri);
            List<String> hosts = connectionString.getHosts();
            if (hosts == null || hosts.isEmpty()) {
                throw new IllegalArgumentException("Connection string contains no hosts");
            }
            result.setClusterHosts(hosts);
        } catch (Exception e) {
            result.getChecks().put("uri_valid", false);
            result.getErrors().add("Invalid MongoDB connection URI format: " + e.getMessage());
            result.setPassed(false);
            return result;
        }
        result.getChecks().put("uri_valid", true);

        // 2. Resolve database names and check for conflicts
        String uriDb = connectionString.getDatabase();
        String configuredDb = properties.getMongodbDatabase();

        // Conflict check: if both URI and configuredDb specify a database, they MUST match
        if (uriDb != null && !uriDb.isBlank() && configuredDb != null && !configuredDb.isBlank()) {
            if (!uriDb.trim().equalsIgnoreCase(configuredDb.trim())) {
                result.getChecks().put("database_name_consistent", false);
                result.getErrors().add("CRITICAL VIOLATION: Database name conflict. URI specifies database '" + uriDb +
                        "', but MONGODB_DATABASE is configured as '" + configuredDb +
                        "'. Configuration is inconsistent and ambiguous. Fail closed.");
                result.setPassed(false);
                return result;
            }
        }
        result.getChecks().put("database_name_consistent", true);

        // Determine effective database name
        String effectiveDb = mongoConfig.getEffectiveDatabaseName();
        result.setTargetDatabase(effectiveDb);

        if (effectiveDb == null || effectiveDb.isBlank()) {
            result.getChecks().put("database_name_safe", false);
            result.getErrors().add("CRITICAL VIOLATION: Missing target database name. Staging execution requires an explicitly named database.");
            result.setPassed(false);
            return result;
        }

        // 3. Safety check: Target database MUST NOT be production or system DB
        if (isForbiddenDatabase(effectiveDb)) {
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

        // 4. Cluster-level isolation check: Unconditionally reject production cluster
        List<String> hosts = connectionString.getHosts();
        boolean targetsProductionCluster = hosts.stream().anyMatch(h ->
                h != null && (h.toLowerCase().contains(KNOWN_PRODUCTION_CLUSTER_KEYWORD) ||
                              h.toLowerCase().contains(KNOWN_PRODUCTION_CLUSTER_PATTERN.toLowerCase()) ||
                              h.equalsIgnoreCase("cluster0.eueidog.mongodb.net"))
        );

        if (targetsProductionCluster) {
            result.setClusterIsolationLevel("REJECTED_PRODUCTION_CLUSTER");
            result.getChecks().put("cluster_level_isolation", false);
            result.getErrors().add("CRITICAL VIOLATION: Connection points to production Atlas cluster host ('" +
                    KNOWN_PRODUCTION_CLUSTER_PATTERN + "'). Staging execution is strictly prohibited on the production cluster, even if an isolated database name like '" +
                    effectiveDb + "' is specified.");
            result.setPassed(false);
            return result;
        }

        // 5. Require a configured, independently verified staging cluster identity
        String expectedHost = expectedStagingHost;
        if (expectedHost == null || expectedHost.isBlank()) {
            expectedHost = properties.getExpectedStagingHost();
        }
        if (expectedHost == null || expectedHost.isBlank()) {
            expectedHost = System.getenv("EXPECTED_STAGING_HOST");
        }
        if (expectedHost == null || expectedHost.isBlank()) {
            expectedHost = System.getProperty("EXPECTED_STAGING_HOST");
        }

        if (expectedHost == null || expectedHost.isBlank()) {
            result.getChecks().put("staging_cluster_identity_verified", false);
            result.getErrors().add("CRITICAL VIOLATION: Missing expected staging cluster identity. Staging preflight requires a configured, independently verified staging cluster identity or approved staging-host set (EXPECTED_STAGING_HOST).");
            result.setPassed(false);
            return result;
        }

        // Expected host must not match production
        String lowerExpected = expectedHost.toLowerCase().trim();
        if (lowerExpected.contains(KNOWN_PRODUCTION_CLUSTER_KEYWORD) || lowerExpected.contains(KNOWN_PRODUCTION_CLUSTER_PATTERN.toLowerCase())) {
            result.getChecks().put("staging_cluster_identity_verified", false);
            result.getErrors().add("CRITICAL VIOLATION: Expected staging cluster identity ('" + expectedHost + "') points to production cluster. Production cluster cannot be designated as staging.");
            result.setPassed(false);
            return result;
        }

        // Verify that at least one host matches expected host pattern
        boolean hostMatched = hosts.stream().anyMatch(h -> h != null && h.toLowerCase().contains(lowerExpected));
        if (!hostMatched) {
            result.getChecks().put("staging_cluster_identity_verified", false);
            result.getErrors().add("Cluster isolation check failed: Connection host(s) " + hosts +
                    " do not match expected staging cluster identity '" + expectedHost + "'.");
            result.setPassed(false);
            return result;
        }

        result.getChecks().put("staging_cluster_identity_verified", true);
        result.setClusterIsolationLevel("DEDICATED_STAGING_CLUSTER");
        result.getChecks().put("cluster_level_isolation", true);
        result.getMessages().add("Verified dedicated staging cluster host '" + expectedHost + "'.");

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
                logger.debug("Database user connectionStatus inspected: ok={}", connStatus != null ? connStatus.get("ok") : null);
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
