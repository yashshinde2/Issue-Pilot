package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.cli.IssuePilotCli;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.service.DiscoveryService;
import com.issuepilot.service.RefreshService;
import com.issuepilot.service.StagingPreflightService;
import com.issuepilot.service.StagingPreflightService.PreflightResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class StagingIsolationPreflightTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("1. Production Database Rejection: Rejects production database 'issue_pilot' and forbidden names")
    public void testRejectsProductionDatabase() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://test_user:secret_password@staging-cluster.example.net/issue_pilot");
        setField(props, "mongodbDatabase", "issue_pilot");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "staging-cluster.example.net");

        assertFalse(result.isPassed(), "Preflight must fail when target database is production 'issue_pilot'");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("CRITICAL VIOLATION") && e.contains("forbidden or production")),
                "Must report critical violation for production database");
        assertFalse(result.getSanitizedUri().contains("secret_password"), "Password must never be in sanitized URI");
    }

    @Test
    @DisplayName("2. Production Cluster Rejection: Rejects production cluster even when database is named 'issue_pilot_staging'")
    public void testRejectsProductionClusterEvenWithStagingDatabaseName() throws Exception {
        AppProperties props = new AppProperties();
        // Target points to production Atlas cluster host with an isolated staging DB name
        setField(props, "mongodbUri", "mongodb+srv://staging_user:staging_pass@cluster0.eueidog.mongodb.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(false, "staging-cluster.example.net");

        assertFalse(result.isPassed(), "Must reject production cluster even with staging DB name");
        assertFalse(result.getChecks().get("cluster_level_isolation"), "cluster_level_isolation must be false");
        assertEquals("REJECTED_PRODUCTION_CLUSTER", result.getClusterIsolationLevel());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Connection points to production Atlas cluster host")),
                "Error must indicate connection points to production Atlas cluster");
        assertFalse(result.getSanitizedUri().contains("staging_pass"), "Password must never be in sanitized URI");
    }

    @Test
    @DisplayName("3. Missing Staging URI: Fails closed when staging URI is blank or unconfigured")
    public void testRejectsMissingStagingUri() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "");
        setField(props, "stagingMongodbUri", "");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "staging-cluster.example.net");

        assertFalse(result.isPassed(), "Preflight must fail when URI is empty");
        assertFalse(result.getChecks().get("uri_configured"));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Missing staging MongoDB URI")));
    }

    @Test
    @DisplayName("4. Missing Expected Staging Cluster Identity: Fails closed when expected staging host is not specified")
    public void testRejectsMissingExpectedStagingClusterIdentity() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://staging_user:staging_pass@staging-cluster.example.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");
        setField(props, "expectedStagingHost", "");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, null);

        assertFalse(result.isPassed(), "Preflight must fail when expected staging host is missing");
        assertFalse(result.getChecks().get("staging_cluster_identity_verified"));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Missing expected staging cluster identity")));
    }

    @Test
    @DisplayName("5. URI Database vs Configured Database Conflict: Rejects inconsistent database names")
    public void testRejectsConflictingUriAndConfiguredDatabase() throws Exception {
        AppProperties props = new AppProperties();
        // URI specifies staging_alpha, but properties specify staging_beta
        setField(props, "mongodbUri", "mongodb+srv://staging_user:staging_pass@staging-cluster.example.net/issue_pilot_staging_alpha");
        setField(props, "mongodbDatabase", "issue_pilot_staging_beta");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "staging-cluster.example.net");

        assertFalse(result.isPassed(), "Preflight must fail when URI database and configured database conflict");
        assertFalse(result.getChecks().get("database_name_consistent"));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Database name conflict")));
    }

    @Test
    @DisplayName("6. Production Override Flag Rejection: Staging commands strictly reject --allow-production-write")
    public void testRejectsProductionOverrideFlagInStaging() {
        AppProperties props = new AppProperties();
        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService preflightService = new StagingPreflightService(mongoConfig, props, objectMapper);

        IssuePilotCli cli = new IssuePilotCli(null, null, null, null, null, null, preflightService, mongoConfig, objectMapper);

        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        try {
            System.setErr(new PrintStream(errStream));

            // Test 1: In daily-ingestion with target-env=staging
            cli.run("--daily-ingestion", "--target-env=staging", "--allow-production-write=true");
            assertEquals(1, cli.getExitCode(), "CLI must return exit code 1 when --allow-production-write is passed in staging");
            assertTrue(errStream.toString().contains("Production override flag '--allow-production-write' is strictly rejected"));

            // Test 2: In preflight-staging audit
            errStream.reset();
            cli.run("--preflight-staging", "--allow-production-write=true");
            assertEquals(1, cli.getExitCode(), "CLI must return exit code 1 when --allow-production-write is passed in preflight");
            assertTrue(errStream.toString().contains("Flag '--allow-production-write' is strictly rejected"));
        } finally {
            System.setErr(origErr);
        }
    }

    @Test
    @DisplayName("7. Non-zero Exit Status on Preflight Failure: CLI exits with code 1 when preflight audit fails")
    public void testNonZeroExitStatusOnPreflightFailure() throws Exception {
        AppProperties props = new AppProperties();
        // Point to production DB
        setField(props, "mongodbUri", "mongodb+srv://user:pass@staging-cluster.net/issue_pilot");
        setField(props, "mongodbDatabase", "issue_pilot");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService preflightService = new StagingPreflightService(mongoConfig, props, objectMapper);

        IssuePilotCli cli = new IssuePilotCli(null, null, null, null, null, null, preflightService, mongoConfig, objectMapper);
        cli.run("--preflight-staging", "--expected-host=staging-cluster.net");

        assertEquals(1, cli.getExitCode(), "CLI must exit with status 1 on preflight failure");
    }

    @Test
    @DisplayName("8. No Ingestion or Writes After Failed Preflight: Guarantees zero writes when preflight fails")
    public void testNoIngestionOrWritesAfterFailedPreflight() throws Exception {
        AtomicInteger discoveryCalls = new AtomicInteger(0);
        AtomicInteger refreshCalls = new AtomicInteger(0);

        DiscoveryService dummyDiscovery = new DiscoveryService(null, null, null, null, null, null) {
            @Override
            public Map<String, Object> runDiscovery(Integer maxIssuesToDiscover, Integer maxPagesPerQuery, Integer perPage, boolean resume, boolean force, String sourceFilter, Integer creationAgeDays) {
                discoveryCalls.incrementAndGet();
                return Map.of("status", "completed");
            }
        };

        RefreshService dummyRefresh = new RefreshService(null, null, null, null, null) {
            @Override
            public Map<String, Object> runRefresh(int batchSize, Integer maxAgeDays, boolean force) {
                refreshCalls.incrementAndGet();
                return Map.of("status", "completed");
            }
        };

        AppProperties props = new AppProperties();
        // Point to production DB so preflight fails closed
        setField(props, "mongodbUri", "mongodb+srv://user:pass@staging-cluster.net/issue_pilot");
        setField(props, "mongodbDatabase", "issue_pilot");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService preflightService = new StagingPreflightService(mongoConfig, props, objectMapper);

        IssuePilotCli cli = new IssuePilotCli(null, null, null, dummyDiscovery, dummyRefresh, null, preflightService, mongoConfig, objectMapper);
        cli.run("--daily-ingestion", "--target-env=staging", "--expected-host=staging-cluster.net");

        assertEquals(1, cli.getExitCode(), "CLI must exit with code 1 when preflight fails");
        assertEquals(0, discoveryCalls.get(), "DiscoveryService must NEVER be called after failed preflight");
        assertEquals(0, refreshCalls.get(), "RefreshService must NEVER be called after failed preflight");
    }

    @Test
    @DisplayName("9. Dedicated Staging Cluster: Passes cluster check when pointing to dedicated staging host")
    public void testAcceptsDedicatedStagingClusterHost() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://staging_user:staging_pass@staging-cluster0.staging.mongodb.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.mongodb.net");

        // Cluster isolation and safety checks pass; connectivity ping fails as this is a dummy host in unit test
        assertTrue(result.getChecks().get("cluster_level_isolation"));
        assertTrue(result.getChecks().get("database_name_safe"));
        assertTrue(result.getChecks().get("database_name_consistent"));
        assertTrue(result.getChecks().get("staging_cluster_identity_verified"));
        assertEquals("DEDICATED_STAGING_CLUSTER", result.getClusterIsolationLevel());
        assertEquals("issue_pilot_staging", result.getTargetDatabase());
        assertFalse(result.getSanitizedUri().contains("staging_pass"));
    }

    @Test
    @DisplayName("10. Rejects Expected Staging Host Matching Production Pattern")
    public void testRejectsExpectedStagingHostMatchingProduction() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://staging_user:staging_pass@staging-cluster0.example.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "cluster0.eueidog.mongodb.net");

        assertFalse(result.isPassed());
        assertFalse(result.getChecks().get("staging_cluster_identity_verified"));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Expected staging cluster identity") && e.contains("points to production cluster")));
    }
}
