package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.cli.IssuePilotCli;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.service.DiscoveryService;
import com.issuepilot.service.RefreshService;
import com.issuepilot.service.StagingPreflightService;
import com.issuepilot.service.StagingPreflightService.PreflightResult;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.List;
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
        setField(props, "mongodbUri", "mongodb+srv://synthetic_test_user:synthetic_mask_token@staging-cluster.example.net/issue_pilot");
        setField(props, "mongodbDatabase", "issue_pilot");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "staging-cluster.example.net");

        assertFalse(result.isPassed(), "Preflight must fail when target database is production 'issue_pilot'");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("CRITICAL VIOLATION") && e.contains("forbidden or production")),
                "Must report critical violation for production database");
        assertFalse(result.getSanitizedUri().contains("synthetic_mask_token"), "Password must never be in sanitized URI");
        assertTrue(result.getSanitizedUri().contains("synthetic_test_user:****@"), "Sanitized URI must mask credentials");
    }

    @Test
    @DisplayName("2. Production Cluster Rejection: Rejects production cluster even when database is named 'issue_pilot_staging'")
    public void testRejectsProductionClusterEvenWithStagingDatabaseName() throws Exception {
        AppProperties props = new AppProperties();
        // Target points to production Atlas cluster host without credentials (pure cluster-level isolation test)
        setField(props, "mongodbUri", "mongodb+srv://cluster0.eueidog.mongodb.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(false, "staging-cluster.example.net");

        assertFalse(result.isPassed(), "Must reject production cluster even with staging DB name");
        assertFalse(result.getChecks().get("cluster_level_isolation"), "cluster_level_isolation must be false");
        assertEquals("REJECTED_PRODUCTION_CLUSTER", result.getClusterIsolationLevel());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Connection points to production Atlas cluster host")),
                "Error must indicate connection points to production Atlas cluster");
        assertEquals("mongodb+srv://cluster0.eueidog.mongodb.net/issue_pilot_staging", result.getSanitizedUri());
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
        setField(props, "mongodbUri", "mongodb+srv://staging-cluster.example.net/issue_pilot_staging");
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
        setField(props, "mongodbUri", "mongodb+srv://staging-cluster.example.net/issue_pilot_staging_alpha");
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
        setField(props, "mongodbUri", "mongodb+srv://staging-cluster.example.net/issue_pilot");
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
        setField(props, "mongodbUri", "mongodb+srv://staging-cluster.example.net/issue_pilot");
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
        setField(props, "mongodbUri", "mongodb+srv://synthetic_staging_user:synthetic_staging_token@staging-cluster0.staging.example.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        // Cluster isolation and safety checks pass; connectivity ping fails as this is a dummy host in unit test
        assertTrue(result.getChecks().get("cluster_level_isolation"));
        assertTrue(result.getChecks().get("database_name_safe"));
        assertTrue(result.getChecks().get("database_name_consistent"));
        assertTrue(result.getChecks().get("staging_cluster_identity_verified"));
        assertEquals("DEDICATED_STAGING_CLUSTER", result.getClusterIsolationLevel());
        assertEquals("issue_pilot_staging", result.getTargetDatabase());
        assertFalse(result.getSanitizedUri().contains("synthetic_staging_token"));
        assertTrue(result.getSanitizedUri().contains("synthetic_staging_user:****@"));
    }

    @Test
    @DisplayName("10. Rejects Expected Staging Host Matching Production Pattern")
    public void testRejectsExpectedStagingHostMatchingProduction() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://staging-cluster0.example.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "cluster0.eueidog.mongodb.net");

        assertFalse(result.isPassed());
        assertFalse(result.getChecks().get("staging_cluster_identity_verified"));
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Expected staging cluster identity") && e.contains("points to production cluster")));
    }

    @Test
    @DisplayName("11. Regression: Safe Numeric ok validation handles Integer(1), Double(1.0), and Long(1)")
    public void testNumericOkValidationHandlesVariousTypes() {
        // Integer(1)
        Document intDoc = new Document("ok", Integer.valueOf(1));
        assertTrue(StagingPreflightService.isCommandOk(intDoc), "Integer(1) must be recognized as ok=1");
        assertTrue(StagingPreflightService.isPingOk(intDoc), "isPingOk must accept Integer(1)");

        // Double(1.0)
        Document dblDoc = new Document("ok", Double.valueOf(1.0));
        assertTrue(StagingPreflightService.isCommandOk(dblDoc), "Double(1.0) must be recognized as ok=1");
        assertTrue(StagingPreflightService.isPingOk(dblDoc), "isPingOk must accept Double(1.0)");

        // Long(1)
        Document lngDoc = new Document("ok", Long.valueOf(1L));
        assertTrue(StagingPreflightService.isCommandOk(lngDoc), "Long(1L) must be recognized as ok=1");
        assertTrue(StagingPreflightService.isPingOk(lngDoc), "isPingOk must accept Long(1L)");

        // Float(1.0f)
        Document fltDoc = new Document("ok", Float.valueOf(1.0f));
        assertTrue(StagingPreflightService.isCommandOk(fltDoc), "Float(1.0f) must be recognized as ok=1");

        // Missing ok field
        Document missingDoc = new Document();
        assertFalse(StagingPreflightService.isCommandOk(missingDoc), "Missing ok field must evaluate to false");
        assertFalse(StagingPreflightService.isPingOk(missingDoc), "Missing ok field must evaluate to false");

        // Null ok value
        Document nullDoc = new Document("ok", null);
        assertFalse(StagingPreflightService.isCommandOk(nullDoc), "Null ok value must evaluate to false");

        // Null document
        assertFalse(StagingPreflightService.isCommandOk(null), "Null document must evaluate to false");

        // Non-numeric ok values (strings, booleans, objects)
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", "1")), "String '1' must not be recognized as numeric ok=1");
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", "true")), "String 'true' must evaluate to false");
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", Boolean.TRUE)), "Boolean TRUE must evaluate to false");
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", List.of(1))), "List must evaluate to false");

        // Unsuccessful numeric values (0, 0.0, 2, negative)
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", Integer.valueOf(0))), "Integer(0) must evaluate to false");
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", Double.valueOf(0.0))), "Double(0.0) must evaluate to false");
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", Long.valueOf(0L))), "Long(0L) must evaluate to false");
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", Integer.valueOf(2))), "Integer(2) must evaluate to false");
        assertFalse(StagingPreflightService.isCommandOk(new Document("ok", Integer.valueOf(-1))), "Integer(-1) must evaluate to false");
    }

    @SuppressWarnings("unchecked")
    private <T> T createProxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    private StagingPreflightService createMockedPreflightService(Document pingResponse, boolean throwOnPing) throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://synthetic_user:synthetic_pwd@staging-cluster0.staging.example.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");
        setField(props, "expectedStagingHost", "staging.example.net");

        MongoConfig mongoConfig = new MongoConfig(props);

        MongoDatabase mockDb = createProxy(MongoDatabase.class, (proxy, method, args) -> {
            if ("runCommand".equals(method.getName())) {
                if (throwOnPing) {
                    throw new RuntimeException("Simulated connection timeout to staging host");
                }
                Document cmd = (Document) args[0];
                if (cmd.containsKey("ping")) {
                    return pingResponse;
                }
                if (cmd.containsKey("connectionStatus")) {
                    return new Document("ok", 1);
                }
            }
            return null;
        });

        MongoClient mockClient = createProxy(MongoClient.class, (proxy, method, args) -> {
            if ("getDatabase".equals(method.getName())) {
                return mockDb;
            }
            return null;
        });

        setField(mongoConfig, "mongoClient", mockClient);

        return new StagingPreflightService(mongoConfig, props, objectMapper);
    }

    @Test
    @DisplayName("12. Regression: Staging Preflight succeeds when ping returns Integer(1)")
    public void testStagingPreflightSucceedsWithIntegerPingOk() throws Exception {
        // Simulates the exact GitHub Actions case: MongoDB driver returns Integer(1)
        StagingPreflightService service = createMockedPreflightService(new Document("ok", Integer.valueOf(1)), false);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        assertTrue(result.isPassed(), "Preflight must pass when ping returns Integer(1)");
        assertTrue(result.getChecks().get("connectivity_ping"), "connectivity_ping check must pass for Integer(1)");
        assertTrue(result.getChecks().get("staging_cluster_identity_verified"));
        assertTrue(result.getChecks().get("cluster_level_isolation"));
        assertEquals("DEDICATED_STAGING_CLUSTER", result.getClusterIsolationLevel());
        assertTrue(result.getMessages().stream().anyMatch(m -> m.contains("Successfully pinged target staging database")));
    }

    @Test
    @DisplayName("13. Regression: Staging Preflight succeeds when ping returns Double(1.0)")
    public void testStagingPreflightSucceedsWithDoublePingOk() throws Exception {
        StagingPreflightService service = createMockedPreflightService(new Document("ok", Double.valueOf(1.0)), false);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        assertTrue(result.isPassed(), "Preflight must pass when ping returns Double(1.0)");
        assertTrue(result.getChecks().get("connectivity_ping"), "connectivity_ping check must pass for Double(1.0)");
    }

    @Test
    @DisplayName("14. Regression: Staging Preflight succeeds when ping returns Long(1L)")
    public void testStagingPreflightSucceedsWithLongPingOk() throws Exception {
        StagingPreflightService service = createMockedPreflightService(new Document("ok", Long.valueOf(1L)), false);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        assertTrue(result.isPassed(), "Preflight must pass when ping returns Long(1L)");
        assertTrue(result.getChecks().get("connectivity_ping"), "connectivity_ping check must pass for Long(1L)");
    }

    @Test
    @DisplayName("15. Regression: Staging Preflight fails when ping ok is missing")
    public void testStagingPreflightFailsWhenPingOkMissing() throws Exception {
        StagingPreflightService service = createMockedPreflightService(new Document(), false);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        assertFalse(result.isPassed(), "Preflight must fail when ping result is missing 'ok'");
        assertFalse(result.getChecks().get("connectivity_ping"), "connectivity_ping check must fail");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Database ping did not return ok=1")));
    }

    @Test
    @DisplayName("16. Regression: Staging Preflight fails when ping ok is non-numeric")
    public void testStagingPreflightFailsWhenPingOkNonNumeric() throws Exception {
        StagingPreflightService service = createMockedPreflightService(new Document("ok", "true"), false);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        assertFalse(result.isPassed(), "Preflight must fail when ping ok is a String");
        assertFalse(result.getChecks().get("connectivity_ping"), "connectivity_ping check must fail");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Database ping did not return ok=1")));
    }

    @Test
    @DisplayName("17. Regression: Staging Preflight fails when ping ok is unsuccessful (0)")
    public void testStagingPreflightFailsWhenPingOkZero() throws Exception {
        StagingPreflightService service = createMockedPreflightService(new Document("ok", Integer.valueOf(0)), false);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        assertFalse(result.isPassed(), "Preflight must fail when ping returns ok=0");
        assertFalse(result.getChecks().get("connectivity_ping"), "connectivity_ping check must fail");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Database ping did not return ok=1")));
    }

    @Test
    @DisplayName("18. Fail-Closed: Staging Preflight fails when ping throws exception")
    public void testStagingPreflightFailsClosedWhenPingThrowsException() throws Exception {
        StagingPreflightService service = createMockedPreflightService(null, true);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.example.net");

        assertFalse(result.isPassed(), "Preflight must fail closed when ping throws");
        assertFalse(result.getChecks().get("connectivity_ping"), "connectivity_ping check must fail");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Failed to connect or ping target staging database")));
    }
}
