package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.service.StagingPreflightService;
import com.issuepilot.service.StagingPreflightService.PreflightResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

public class StagingIsolationPreflightTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("Fail-Closed: Rejects production database 'issue_pilot'")
    public void testRejectsProductionDatabase() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://test_user:secret_password@staging-cluster.example.net/issue_pilot");
        setField(props, "mongodbDatabase", "issue_pilot");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(false, null);

        assertFalse(result.isPassed(), "Preflight must fail when target database is production 'issue_pilot'");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("CRITICAL VIOLATION") && e.contains("forbidden or production")),
                "Must report critical violation for production database");
        assertFalse(result.getSanitizedUri().contains("secret_password"), "Password must never be in sanitized URI");
    }

    @Test
    @DisplayName("Fail-Closed: Rejects non-staging database names (missing staging/test marker)")
    public void testRejectsUnmarkedDatabase() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://test_user:secret_password@staging-cluster.example.net/custom_app_db");
        setField(props, "mongodbDatabase", "custom_app_db");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(false, null);

        assertFalse(result.isPassed(), "Preflight must fail when database does not contain staging or test");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("does not contain 'staging' or 'test'")));
    }

    @Test
    @DisplayName("Fail-Closed: Rejects production Atlas cluster host when requireIsolatedCluster=true")
    public void testRejectsProductionClusterHostWhenStrictIsolationRequired() throws Exception {
        AppProperties props = new AppProperties();
        // Pointing to the production Atlas cluster host with an isolated staging DB
        setField(props, "mongodbUri", "mongodb+srv://staging_user:staging_pass@cluster0.eueidog.mongodb.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, null);

        assertFalse(result.isPassed(), "Must reject shared production cluster when requireIsolatedCluster=true");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Cluster-level isolation requirement failed")));
        assertEquals("SHARED_CLUSTER_ISOLATED_DB", result.getClusterIsolationLevel());
    }

    @Test
    @DisplayName("Dedicated Staging Cluster: Passes cluster check when pointing to dedicated staging host")
    public void testAcceptsDedicatedStagingClusterHost() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "mongodb+srv://staging_user:staging_pass@staging-cluster0.staging.mongodb.net/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(true, "staging.mongodb.net");

        // Cluster isolation passes; connectivity ping fails as this is a dummy host in unit test
        assertTrue(result.getChecks().get("cluster_level_isolation"));
        assertTrue(result.getChecks().get("database_name_safe"));
        assertTrue(result.getChecks().get("expected_host_matched"));
        assertEquals("DEDICATED_STAGING_CLUSTER", result.getClusterIsolationLevel());
        assertEquals("issue_pilot_staging", result.getTargetDatabase());
        assertFalse(result.getSanitizedUri().contains("staging_pass"));
    }

    @Test
    @DisplayName("Database Name Precedence: Resolves effective database from URI path over default property")
    public void testEffectiveDatabaseResolutionFromUri() throws Exception {
        AppProperties props = new AppProperties();
        // URI specifies staging database, but property still has default issue_pilot
        setField(props, "mongodbUri", "mongodb://localhost:27017/issue_pilot_staging");
        setField(props, "mongodbDatabase", "issue_pilot");

        MongoConfig mongoConfig = new MongoConfig(props);
        assertEquals("issue_pilot_staging", mongoConfig.getEffectiveDatabaseName(),
                "URI path database must override default property value");
    }

    @Test
    @DisplayName("Fail-Closed: Rejects blank or empty URI")
    public void testRejectsBlankUri() throws Exception {
        AppProperties props = new AppProperties();
        setField(props, "mongodbUri", "");
        setField(props, "mongodbDatabase", "issue_pilot_staging");

        MongoConfig mongoConfig = new MongoConfig(props);
        StagingPreflightService service = new StagingPreflightService(mongoConfig, props, objectMapper);

        PreflightResult result = service.validateStagingEnvironment(false, null);

        assertFalse(result.isPassed());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("No MongoDB URI configured")));
    }
}
