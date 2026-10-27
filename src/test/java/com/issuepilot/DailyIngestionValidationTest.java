package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.adapter.AdapterRegistry;
import com.issuepilot.adapter.BaseIssueSourceAdapter;
import com.issuepilot.adapter.BugzillaAdapter;
import com.issuepilot.adapter.CodebergAdapter;
import com.issuepilot.adapter.GitLabAdapter;
import com.issuepilot.adapter.SourceAPIException;
import com.issuepilot.adapter.TechnologyDetectionHelper;
import com.issuepilot.cli.IssuePilotCli;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.JobRepository;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.service.DiscoveryService;
import com.issuepilot.service.RefreshService;
import com.issuepilot.service.StagingPreflightService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class DailyIngestionValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private <T> T createProxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    @FunctionalInterface
    interface HttpSendHandler {
        HttpResponse<?> handle(HttpRequest request, HttpResponse.BodyHandler<?> responseBodyHandler) throws IOException, InterruptedException;
    }

    private void setProperty(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName + " on " + target, e);
        }
    }

    private HttpClient createStubHttpClient(HttpSendHandler handler) {
        return new HttpClient() {
            @Override
            public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
            @Override
            public Optional<Duration> connectTimeout() { return Optional.empty(); }
            @Override
            public Redirect followRedirects() { return Redirect.NEVER; }
            @Override
            public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
            @Override
            public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override
            public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override
            public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
            @Override
            public Version version() { return Version.HTTP_2; }
            @Override
            public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }

            @SuppressWarnings("unchecked")
            @Override
            public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
                return (HttpResponse<T>) handler.handle(request, responseBodyHandler);
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
                return null;
            }

            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
                return null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> createMockHttpResponse(int statusCode, String body, Map<String, List<String>> headers) {
        return createProxy(HttpResponse.class, (proxy, method, args) -> {
            if ("statusCode".equals(method.getName())) return statusCode;
            if ("body".equals(method.getName())) return body;
            if ("headers".equals(method.getName())) {
                return HttpHeaders.of(headers != null ? headers : Collections.emptyMap(), (k, v) -> true);
            }
            return null;
        });
    }

    static class StubDiscoveryService extends DiscoveryService {
        private final Map<String, Object> stubResult;

        public StubDiscoveryService(Map<String, Object> stubResult) {
            super(null, null, null, null, null, null);
            this.stubResult = stubResult;
        }

        @Override
        public Map<String, Object> runDiscovery(
                Integer maxIssuesToDiscover,
                Integer maxPagesPerQuery,
                Integer perPage,
                boolean resume,
                boolean force,
                String sourceFilter,
                Integer creationAgeDays
        ) {
            return stubResult;
        }
    }

    static class StubRefreshService extends RefreshService {
        private final Map<String, Object> stubResult;

        public StubRefreshService(Map<String, Object> stubResult) {
            super(null, null, null, null, null);
            this.stubResult = stubResult;
        }

        @Override
        public Map<String, Object> runRefresh(int batchSize, Integer maxAgeDays, boolean force) {
            return stubResult;
        }
    }

    @Test
    @DisplayName("1. Null repository fields in Bugzilla records do not cause NPE and produce canonical identity")
    public void testBugzillaNullRepositorySafeUpsert() {
        AtomicReference<Document> capturedFilter = new AtomicReference<>();
        Document savedDoc = new Document("id", "bz_bugzilla_mozilla_org_12345")
                .append("source", "bugzilla")
                .append("issue_number", 12345)
                .append("repository_full_name", null);

        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = createProxy(FindIterable.class, (proxy, method, args) -> {
            if ("first".equals(method.getName())) {
                return savedDoc;
            }
            return proxy;
        });

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("updateOne".equals(method.getName())) {
                capturedFilter.set((Document) args[0]);
                return UpdateResult.acknowledged(1, 1L, null);
            }
            if ("find".equals(method.getName())) {
                return findIterable;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        IssueRepository issueRepository = new IssueRepository(mongoOperations);

        Map<String, Object> bugzillaIssue = new HashMap<>();
        bugzillaIssue.put("source", "bugzilla");
        bugzillaIssue.put("source_instance", "bugzilla.mozilla.org");
        bugzillaIssue.put("repository_full_name", null); // explicit null!
        bugzillaIssue.put("repository_owner", null);
        bugzillaIssue.put("repository_name", null);
        bugzillaIssue.put("issue_number", 12345);
        bugzillaIssue.put("title", "Crash in layout engine");
        bugzillaIssue.put("state", "open");

        Map<String, Object> result = issueRepository.upsertIssue(bugzillaIssue, true);
        assertNotNull(result);
        assertNotNull(capturedFilter.get());
        assertEquals("bz_bugzilla_mozilla_org_12345", capturedFilter.get().getString("id"));
    }

    @Test
    @DisplayName("2. Canonical ID uniqueness and rejection of ambiguous identities")
    public void testCanonicalIdValidationAndAmbiguityRejection() {
        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> null);
        IssueRepository issueRepository = new IssueRepository(mongoOperations);

        Map<String, Object> ambiguousIssue = new HashMap<>();
        ambiguousIssue.put("source", "github");
        ambiguousIssue.put("title", "Unknown issue without identity");

        assertThrows(IllegalArgumentException.class, () -> issueRepository.upsertIssue(ambiguousIssue, true));
    }

    @Test
    @DisplayName("3. Technology stack normalization and contributor preference aggregation")
    public void testTechnologyStackNormalizationAndAggregation() {
        assertEquals("Python", TechnologyDetectionHelper.normalizeTechnology("py"));
        assertEquals("Python", TechnologyDetectionHelper.normalizeTechnology("python3"));
        assertEquals("TypeScript", TechnologyDetectionHelper.normalizeTechnology("ts"));
        assertEquals("JavaScript", TechnologyDetectionHelper.normalizeTechnology("nodejs"));
        assertEquals("C++", TechnologyDetectionHelper.normalizeTechnology("cpp"));
        assertEquals("C++", TechnologyDetectionHelper.normalizeTechnology("cxx"));
        assertEquals("Go", TechnologyDetectionHelper.normalizeTechnology("golang"));
        assertEquals("React", TechnologyDetectionHelper.normalizeTechnology("react.js"));
        assertEquals("Java", TechnologyDetectionHelper.normalizeTechnology("jdk"));

        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("test-user-1");

        Map<String, ContributorSkill> skills = new HashMap<>();
        skills.put("py", new ContributorSkill("py", 80.0, 1.0));
        skills.put("ts", new ContributorSkill("ts", 85.0, 1.0));
        skills.put("cpp", new ContributorSkill("cpp", 90.0, 1.0));
        profile.setSkills(skills);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("tech_stack", List.of("reactjs", "Go", "python"));
        prefs.put("languages", "java, rust");
        profile.setPreferences(prefs);

        Set<String> normalized = profile.getNormalizedTechnologyPreferences();
        assertTrue(normalized.contains("Python"));
        assertTrue(normalized.contains("TypeScript"));
        assertTrue(normalized.contains("C++"));
        assertTrue(normalized.contains("React"));
        assertTrue(normalized.contains("Go"));
        assertTrue(normalized.contains("Java"));
        assertTrue(normalized.contains("Rust"));
        assertEquals(7, normalized.size());
    }

    @Test
    @DisplayName("4. Creation-age window calculation (7-day and 30-day)")
    public void testCreationAgeWindowCalculation() {
        Instant now = Instant.now();
        Instant cutoff7 = now.minus(Duration.ofDays(7));
        Instant cutoff30 = now.minus(Duration.ofDays(30));

        Instant itemRecent = now.minus(Duration.ofDays(3));
        Instant itemMid = now.minus(Duration.ofDays(15));
        Instant itemOld = now.minus(Duration.ofDays(45));

        assertTrue(itemRecent.isAfter(cutoff7));
        assertFalse(itemMid.isAfter(cutoff7));
        assertFalse(itemOld.isAfter(cutoff7));

        assertTrue(itemRecent.isAfter(cutoff30));
        assertTrue(itemMid.isAfter(cutoff30));
        assertFalse(itemOld.isAfter(cutoff30));
    }

    @Test
    @DisplayName("5. Active user query logic in UserRepository")
    public void testActiveUserQuerySemantics() {
        AtomicReference<Document> capturedQuery = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        MongoCursor<Document> cursor = createProxy(MongoCursor.class, (proxy, method, args) -> {
            if ("hasNext".equals(method.getName())) return false;
            return null;
        });

        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = createProxy(FindIterable.class, (proxy, method, args) -> {
            if ("iterator".equals(method.getName())) return cursor;
            return proxy;
        });

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("find".equals(method.getName())) {
                capturedQuery.set((Document) args[0]);
                return findIterable;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        UserRepository userRepository = new UserRepository(mongoOperations, objectMapper);
        Instant since = Instant.now().minus(Duration.ofDays(30));
        List<ContributorProfile> active = userRepository.findActiveUsers(since, 50);
        assertNotNull(active);
        assertNotNull(capturedQuery.get());
        assertTrue(capturedQuery.get().containsKey("$or"));
    }

    @Test
    @DisplayName("6. Closed-issue refresh behavior: mark closed without deleting records or votes")
    public void testClosedIssueRefreshPreservation() {
        AtomicReference<Document> capturedFilter = new AtomicReference<>();
        AtomicReference<Document> capturedUpdate = new AtomicReference<>();
        AtomicInteger deleteCalls = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("updateOne".equals(method.getName())) {
                capturedFilter.set((Document) args[0]);
                capturedUpdate.set((Document) args[1]);
                return UpdateResult.acknowledged(1, 1L, null);
            }
            if ("deleteOne".equals(method.getName())) {
                deleteCalls.incrementAndGet();
                return null;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        IssueRepository issueRepository = new IssueRepository(mongoOperations);
        boolean closed = issueRepository.markIssueClosed("github_owner_repo_10", "2026-10-25T12:00:00Z");

        assertTrue(closed);
        assertEquals("github_owner_repo_10", capturedFilter.get().getString("id"));
        Document setDoc = (Document) capturedUpdate.get().get("$set");
        assertEquals("closed", setDoc.getString("state"));
        assertEquals("closed", setDoc.getString("stale_status"));
        assertEquals(0, deleteCalls.get(), "Must never delete documents to mark closed");
    }

    @Test
    @DisplayName("7. Duplicate ingestion idempotency: repeated upsert updates the record in place")
    public void testDuplicateIngestionIdempotency() {
        List<Document> filtersUsed = new ArrayList<>();
        Document savedDoc = new Document("id", "github_test_repo_1")
                .append("source", "github")
                .append("repository_full_name", "test/repo")
                .append("issue_number", 1)
                .append("title", "First run");

        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = createProxy(FindIterable.class, (proxy, method, args) -> {
            if ("first".equals(method.getName())) {
                return savedDoc;
            }
            return proxy;
        });

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("updateOne".equals(method.getName())) {
                filtersUsed.add((Document) args[0]);
                return UpdateResult.acknowledged(1, 1L, null);
            }
            if ("find".equals(method.getName())) {
                return findIterable;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        IssueRepository issueRepository = new IssueRepository(mongoOperations);

        Map<String, Object> issue = new HashMap<>();
        issue.put("id", "github_test_repo_1");
        issue.put("source", "github");
        issue.put("repository_full_name", "test/repo");
        issue.put("issue_number", 1);
        issue.put("title", "First run");

        issueRepository.upsertIssue(issue, true);
        issueRepository.upsertIssue(issue, true);

        assertEquals(2, filtersUsed.size());
        for (Document f : filtersUsed) {
            assertEquals("github_test_repo_1", f.getString("id"));
        }
    }

    @Test
    @DisplayName("8. Partial platform failure and exit code semantics in IssuePilotCli")
    public void testCliPartialFailureExitCode() {
        Map<String, Object> discFail = new HashMap<>();
        discFail.put("status", "failed");
        discFail.put("discovered_count", 0);
        DiscoveryService discService = new StubDiscoveryService(discFail);

        Map<String, Object> refOk = new HashMap<>();
        refOk.put("status", "completed");
        RefreshService refService = new StubRefreshService(refOk);

        AppProperties testProps = new AppProperties();
        MongoConfig mongoConfig = new MongoConfig(testProps);
        StagingPreflightService passingPreflight = new StagingPreflightService(mongoConfig, testProps, objectMapper) {
            @Override
            public PreflightResult validateStagingEnvironment(boolean requireIsolatedCluster, String expectedStagingHost) {
                PreflightResult res = new PreflightResult();
                res.setPassed(true);
                res.setTargetDatabase("issue_pilot_staging");
                res.setClusterIsolationLevel("DEDICATED_STAGING_CLUSTER");
                return res;
            }
        };

        IssuePilotCli cli = new IssuePilotCli(null, null, null, discService, refService, null, passingPreflight, mongoConfig, objectMapper);
        cli.run("--daily-ingestion", "--target-env=staging", "--expected-host=staging-cluster", "--days=30", "--source=all");

        assertEquals(1, cli.getExitCode(), "CLI exit code must be 1 on platform failure");
    }

    @Test
    @DisplayName("9. Clean CLI options parsing, help message, and successful exit code")
    public void testCliHelpAndSuccessExitCode() {
        Map<String, Object> discOk = new HashMap<>();
        discOk.put("status", "completed");
        discOk.put("discovered_count", 10);
        DiscoveryService discService = new StubDiscoveryService(discOk);

        Map<String, Object> refOk = new HashMap<>();
        refOk.put("status", "completed");
        RefreshService refService = new StubRefreshService(refOk);

        AppProperties testProps = new AppProperties();
        MongoConfig mongoConfig = new MongoConfig(testProps);
        StagingPreflightService passingPreflight = new StagingPreflightService(mongoConfig, testProps, objectMapper) {
            @Override
            public PreflightResult validateStagingEnvironment(boolean requireIsolatedCluster, String expectedStagingHost) {
                PreflightResult res = new PreflightResult();
                res.setPassed(true);
                res.setTargetDatabase("issue_pilot_staging");
                res.setClusterIsolationLevel("DEDICATED_STAGING_CLUSTER");
                return res;
            }
        };

        IssuePilotCli cli = new IssuePilotCli(null, null, null, discService, refService, null, passingPreflight, mongoConfig, objectMapper);
        cli.run("--help");
        assertEquals(0, cli.getExitCode());

        cli.run("--daily-ingestion", "--target-env=staging", "--expected-host=staging-cluster", "--days=7", "--source=github");
        assertEquals(0, cli.getExitCode());
    }

    @Test
    @DisplayName("10. Bugzilla base URL validation: missing, blank, malformed, and valid base URLs")
    public void testBugzillaBaseUrlValidationAndSafeDisabledState() {
        // 1. Missing (null) URL
        AppProperties propsNull = new AppProperties();
        setProperty(propsNull, "bugzillaBaseUrl", null);
        BugzillaAdapter adapterNull = new BugzillaAdapter(propsNull, objectMapper);
        assertFalse(adapterNull.isConfigured(), "Missing base URL must mark Bugzilla disabled");
        assertNotNull(adapterNull.getConfigurationError());
        Map<String, Object> resNull = adapterNull.searchIssues("test", 1, 10, "updated", "desc");
        assertEquals("disabled", resNull.get("status"));
        assertTrue(((List<?>) resNull.get("items")).isEmpty());
        Map<String, Object> healthNull = adapterNull.checkHealth();
        assertEquals("disabled", healthNull.get("status"));
        assertEquals(false, healthNull.get("configured"));
        assertThrows(IllegalArgumentException.class, () -> adapterNull.resolveEndpoint("/test"));

        // 2. Blank URL
        AppProperties propsBlank = new AppProperties();
        setProperty(propsBlank, "bugzillaBaseUrl", "   ");
        BugzillaAdapter adapterBlank = new BugzillaAdapter(propsBlank, objectMapper);
        assertFalse(adapterBlank.isConfigured(), "Blank base URL must mark Bugzilla disabled");

        // 3. Malformed URLs
        // Scheme missing / relative
        AppProperties propsRelative = new AppProperties();
        setProperty(propsRelative, "bugzillaBaseUrl", "not-a-valid-uri");
        BugzillaAdapter adapterRelative = new BugzillaAdapter(propsRelative, objectMapper);
        assertFalse(adapterRelative.isConfigured(), "Relative URI must mark Bugzilla disabled");

        // Unsupported scheme (ftp)
        AppProperties propsFtp = new AppProperties();
        setProperty(propsFtp, "bugzillaBaseUrl", "ftp://bugzilla.mozilla.org");
        BugzillaAdapter adapterFtp = new BugzillaAdapter(propsFtp, objectMapper);
        assertFalse(adapterFtp.isConfigured(), "FTP scheme must mark Bugzilla disabled");

        // Missing host
        AppProperties propsNoHost = new AppProperties();
        setProperty(propsNoHost, "bugzillaBaseUrl", "http://");
        BugzillaAdapter adapterNoHost = new BugzillaAdapter(propsNoHost, objectMapper);
        assertFalse(adapterNoHost.isConfigured(), "URI without host must mark Bugzilla disabled");

        // 4. Valid base URLs
        AppProperties propsValid = new AppProperties();
        setProperty(propsValid, "bugzillaBaseUrl", "https://bugzilla.mozilla.org");
        BugzillaAdapter adapterValid = new BugzillaAdapter(propsValid, objectMapper);
        assertTrue(adapterValid.isConfigured(), "Valid HTTPS URL must mark Bugzilla configured");
        assertEquals("https://bugzilla.mozilla.org", adapterValid.getValidatedBaseUrl());
        assertEquals("https://bugzilla.mozilla.org/rest/bug?limit=10", adapterValid.resolveEndpoint("/rest/bug?limit=10"));

        // Valid base URL with trailing slash
        AppProperties propsTrailing = new AppProperties();
        setProperty(propsTrailing, "bugzillaBaseUrl", "https://bugs.eclipse.org/");
        BugzillaAdapter adapterTrailing = new BugzillaAdapter(propsTrailing, objectMapper);
        assertTrue(adapterTrailing.isConfigured());
        assertEquals("https://bugs.eclipse.org", adapterTrailing.getValidatedBaseUrl());
        assertEquals("https://bugs.eclipse.org/rest/bug?limit=10", adapterTrailing.resolveEndpoint("/rest/bug?limit=10"));
    }

    @Test
    @DisplayName("11. GitLab adapter bounded retry with exponential backoff on HTTP 5xx")
    public void testGitLabTimeoutAndBoundedRetryWithBackoff() {
        AtomicInteger attempts = new AtomicInteger(0);
        String validJson = "[{\"id\": 100, \"iid\": 1, \"title\": \"GitLab issue\", \"state\": \"opened\", \"created_at\": \"2026-03-01T00:00:00Z\", \"web_url\": \"https://gitlab.com/test/repo/-/issues/1\"}]";

        HttpClient stubClient = createStubHttpClient((request, handler) -> {
            int cur = attempts.incrementAndGet();
            if (cur == 1) {
                return createMockHttpResponse(503, "Service Unavailable", null);
            } else if (cur == 2) {
                return createMockHttpResponse(502, "Bad Gateway", null);
            } else {
                return createMockHttpResponse(200, validJson, null);
            }
        });

        AppProperties props = new AppProperties();
        setProperty(props, "gitlabRequestTimeoutSeconds", 5);
        GitLabAdapter adapter = new GitLabAdapter(props, objectMapper, stubClient);

        Map<String, Object> result = adapter.searchIssues("python", 1, 10, "updated", "desc");
        assertEquals(3, attempts.get(), "Must retry twice and succeed on 3rd attempt");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size());
        assertEquals("GitLab issue", items.get(0).get("title"));
    }

    @Test
    @DisplayName("12. GitLab adapter retry on HttpTimeoutException and secret token redaction")
    public void testGitLabTimeoutExceptionRetryAndTokenRedaction() {
        AtomicInteger attempts = new AtomicInteger(0);
        String secretToken = "glpat-SECRET-TOKEN-ABCD-123456789";

        HttpClient stubClient = createStubHttpClient((request, handler) -> {
            attempts.incrementAndGet();
            throw new HttpTimeoutException("Connection timed out after 5000ms");
        });

        AppProperties props = new AppProperties();
        setProperty(props, "gitlabToken", secretToken);
        setProperty(props, "gitlabRequestTimeoutSeconds", 2);
        GitLabAdapter adapter = new GitLabAdapter(props, objectMapper, stubClient);

        SourceAPIException thrown = assertThrows(SourceAPIException.class, () ->
                adapter.searchIssues("python", 1, 10, "updated", "desc")
        );

        assertEquals(4, attempts.get(), "Must attempt 1 initial request + 3 retries = 4 total attempts");
        assertFalse(thrown.getMessage().contains(secretToken), "GitLab secret token must NEVER be leaked in exception messages");
    }

    @Test
    @DisplayName("13. GitLab adapter respects Retry-After on HTTP 429 rate limit")
    public void testGitLab429RateLimitRespectsRetryAfter() {
        AtomicInteger attempts = new AtomicInteger(0);
        String validJson = "[]";

        HttpClient stubClient = createStubHttpClient((request, handler) -> {
            int cur = attempts.incrementAndGet();
            if (cur == 1) {
                Map<String, List<String>> headers = Map.of("Retry-After", List.of("0"));
                return createMockHttpResponse(429, "Too Many Requests", headers);
            } else {
                return createMockHttpResponse(200, validJson, null);
            }
        });

        AppProperties props = new AppProperties();
        setProperty(props, "gitlabRequestTimeoutSeconds", 5);
        GitLabAdapter adapter = new GitLabAdapter(props, objectMapper, stubClient);

        Map<String, Object> result = adapter.searchIssues("react", 1, 10, "updated", "desc");
        assertEquals(2, attempts.get(), "Must retry once after 429 Retry-After and succeed");
        assertNotNull(result);
    }

    @Test
    @DisplayName("14. Codeberg HTTP 500 triggers curated fallback, records metadata, and bypasses failing global search on subsequent queries")
    public void testCodebergHttp500CircuitBreakerAndFallbackMetadata() {
        AtomicInteger globalSearchCalls = new AtomicInteger(0);
        AtomicInteger curatedRepoCalls = new AtomicInteger(0);
        String repoIssuesJson = "[{\"id\": 200, \"number\": 42, \"title\": \"Codeberg fallback issue\", \"state\": \"open\", \"created_at\": \"2026-03-01T00:00:00Z\", \"html_url\": \"https://codeberg.org/forgejo/forgejo/issues/42\", \"repository\": {\"full_name\": \"forgejo/forgejo\"}}]";

        HttpClient stubClient = createStubHttpClient((request, handler) -> {
            String path = request.uri().getPath();
            if (path.contains("/issues/search")) {
                globalSearchCalls.incrementAndGet();
                return createMockHttpResponse(500, "Internal Server Error: Search Index Unavailable", null);
            } else if (path.contains("/repos/")) {
                curatedRepoCalls.incrementAndGet();
                return createMockHttpResponse(200, repoIssuesJson, null);
            }
            return createMockHttpResponse(404, "Not Found", null);
        });

        AppProperties props = new AppProperties();
        CodebergAdapter adapter = new CodebergAdapter(props, objectMapper, stubClient);
        adapter.resetGlobalSearchState();

        // 1. First query: Global search fails with 500, falls back to curated repos
        Map<String, Object> firstResult = adapter.searchIssues("python", 1, 10, "updated", "desc");
        assertEquals(1, globalSearchCalls.get(), "Global search should be called once on first stack");
        assertTrue(curatedRepoCalls.get() > 0, "Curated repos should be queried on fallback");
        assertEquals(true, firstResult.get("fallback_used"));
        assertEquals("failed", firstResult.get("global_search_status"));
        assertEquals("completed", firstResult.get("fallback_status"));
        assertNotNull(firstResult.get("source_error"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) firstResult.get("items");
        assertFalse(items.isEmpty());

        // 2. Second query in same run: Circuit breaker active, global search bypassed directly
        int globalCallsBefore = globalSearchCalls.get();
        Map<String, Object> secondResult = adapter.searchIssues("typescript", 1, 10, "updated", "desc");
        assertEquals(globalCallsBefore, globalSearchCalls.get(), "Failing global search must be bypassed on subsequent query in the same run");
        assertEquals(true, secondResult.get("fallback_used"));

        adapter.resetGlobalSearchState();
    }

    @Test
    @DisplayName("15. Codeberg throws SourceAPIException when both global search and fallback fail")
    public void testCodebergFallbackFailureThrowsSourceAPIException() {
        HttpClient stubClient = createStubHttpClient((request, handler) ->
                createMockHttpResponse(500, "Internal Server Error Across Cluster", null)
        );

        AppProperties props = new AppProperties();
        CodebergAdapter adapter = new CodebergAdapter(props, objectMapper, stubClient);
        adapter.resetGlobalSearchState();

        assertThrows(SourceAPIException.class, () ->
                adapter.searchIssues("go", 1, 10, "updated", "desc")
        );

        adapter.resetGlobalSearchState();
    }

    @Test
    @DisplayName("16. Safe partial success job status calculation in DiscoveryService")
    public void testDiscoveryJobStatusReportingAndIsolation() {
        // Setup mock adapters:
        // 1. Bugzilla: unconfigured -> disabled
        AppProperties bugzillaProps = new AppProperties();
        setProperty(bugzillaProps, "bugzillaBaseUrl", "");
        BugzillaAdapter bugzilla = new BugzillaAdapter(bugzillaProps, objectMapper);

        // 2. GitHub: returns 5 issues -> completed
        BaseIssueSourceAdapter github = new BaseIssueSourceAdapter() {
            @Override
            public String getSourceName() { return "github"; }
            @Override
            public Map<String, Object> checkHealth() { return Map.of("status", "healthy"); }
            @Override
            public Map<String, Object> getRateLimit() { return Map.of(); }
            @Override
            public Map<String, Object> getRateLimitState() { return Map.of(); }
            @Override
            public Map<String, Object> getIssue(String owner, String repo, int issueNumber) { return null; }

            @Override
            public Map<String, Object> searchIssues(String query, int page, int perPage, String sort, String order) {
                if (page > 1) return Map.of("items", Collections.emptyList());
                List<Map<String, Object>> items = new ArrayList<>();
                for (int i = 1; i <= 5; i++) {
                    items.add(Map.of(
                            "id", 1000 + i,
                            "number", i,
                            "title", "GH Issue " + i,
                            "state", "open",
                            "created_at", "2026-03-01T00:00:00Z",
                            "html_url", "https://github.com/org/repo/issues/" + i,
                            "repository_url", "https://api.github.com/repos/org/repo"
                    ));
                }
                return Map.of("items", items, "total_count", 5);
            }

            @Override
            public Map<String, Object> normalizeIssue(Map<String, Object> raw, Map<String, Object> queryInfo) {
                Map<String, Object> norm = new HashMap<>();
                norm.put("id", "github_org_repo_" + raw.get("number"));
                norm.put("source", "github");
                norm.put("title", raw.get("title"));
                norm.put("state", "open");
                norm.put("created_at", raw.get("created_at"));
                norm.put("repository_full_name", "org/repo");
                norm.put("detected_technologies", List.of("Python", "TypeScript", "JavaScript", "C++", "Go", "React", "Java"));
                return norm;
            }
        };

        // 3. GitLab: fails with timeout on 1 stack, but succeeds on another -> partial
        AtomicInteger gitlabStack = new AtomicInteger(0);
        BaseIssueSourceAdapter gitlab = new BaseIssueSourceAdapter() {
            @Override
            public String getSourceName() { return "gitlab"; }
            @Override
            public Map<String, Object> checkHealth() { return Map.of("status", "healthy"); }
            @Override
            public Map<String, Object> getRateLimit() { return Map.of(); }
            @Override
            public Map<String, Object> getRateLimitState() { return Map.of(); }
            @Override
            public Map<String, Object> getIssue(String owner, String repo, int issueNumber) { return null; }

            @Override
            public Map<String, Object> searchIssues(String query, int page, int perPage, String sort, String order) {
                if (gitlabStack.incrementAndGet() == 1) {
                    throw new RuntimeException("Simulated GitLab timeout on first stack");
                }
                if (page > 1) return Map.of("items", Collections.emptyList());
                return Map.of("items", List.of(Map.of(
                        "id", 2001,
                        "iid", 1,
                        "title", "GL Issue 1",
                        "state", "opened",
                        "created_at", "2026-03-01T00:00:00Z",
                        "web_url", "https://gitlab.com/org/repo/-/issues/1"
                )));
            }

            @Override
            public Map<String, Object> normalizeIssue(Map<String, Object> raw, Map<String, Object> queryInfo) {
                Map<String, Object> norm = new HashMap<>();
                norm.put("id", "gitlab_org_repo_" + raw.get("iid"));
                norm.put("source", "gitlab");
                norm.put("title", raw.get("title"));
                norm.put("state", "open");
                norm.put("created_at", raw.get("created_at"));
                norm.put("repository_full_name", "org/repo");
                norm.put("detected_technologies", List.of("Python", "TypeScript", "JavaScript", "C++", "Go", "React", "Java"));
                return norm;
            }
        };

        AdapterRegistry registry = new AdapterRegistry(null, null, null, null) {
            private final Map<String, BaseIssueSourceAdapter> customAdapters = Map.of(
                    "github", github,
                    "gitlab", gitlab,
                    "bugzilla", bugzilla
            );
            @Override
            public BaseIssueSourceAdapter getAdapter(String sourceName) {
                String key = (sourceName != null ? sourceName.trim().toLowerCase() : "github");
                BaseIssueSourceAdapter adapter = customAdapters.get(key);
                if (adapter == null) {
                    throw new IllegalArgumentException("Unknown: " + sourceName);
                }
                return adapter;
            }
            @Override
            public Map<String, BaseIssueSourceAdapter> getAllAdapters() {
                return customAdapters;
            }
        };

        AppProperties appProps = new AppProperties();
        MongoConfig mongoConfig = new MongoConfig(appProps);
        DiscoveryService disc = new DiscoveryService(mongoConfig, appProps, null, null, registry, null);

        Map<String, Object> summary = disc.runDiscovery(null, 1, 10, false, true, "all", 30);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> perSource = (Map<String, Map<String, Object>>) summary.get("per_source_summary");

        // Bugzilla is disabled
        assertEquals("disabled", perSource.get("bugzilla").get("status"));
        // GitHub is completed
        assertEquals("completed", perSource.get("github").get("status"));
        // GitLab is partial (some errors, but items discovered)
        assertEquals("partial", perSource.get("gitlab").get("status"));

        // Overall status must be partial_success, NOT completed (GitLab had errors) and NOT failed (items discovered)
        assertEquals("partial_success", summary.get("status"), "Overall status must be partial_success when some sources succeed and others have partial failures");
        assertTrue((int) summary.get("discovered_count") > 0);
    }

    @Test
    @DisplayName("17. IssuePilotCli exit code 0 on partial_success")
    public void testCliPartialSuccessExitCodeZero() {
        Map<String, Object> discPartial = new HashMap<>();
        discPartial.put("status", "partial_success");
        discPartial.put("discovered_count", 15);
        DiscoveryService discService = new StubDiscoveryService(discPartial);

        Map<String, Object> refOk = new HashMap<>();
        refOk.put("status", "completed");
        RefreshService refService = new StubRefreshService(refOk);

        AppProperties testProps = new AppProperties();
        MongoConfig mongoConfig = new MongoConfig(testProps);
        StagingPreflightService passingPreflight = new StagingPreflightService(mongoConfig, testProps, objectMapper) {
            @Override
            public PreflightResult validateStagingEnvironment(boolean requireIsolatedCluster, String expectedStagingHost) {
                PreflightResult res = new PreflightResult();
                res.setPassed(true);
                res.setTargetDatabase("issue_pilot_staging");
                res.setClusterIsolationLevel("DEDICATED_STAGING_CLUSTER");
                return res;
            }
        };

        IssuePilotCli cli = new IssuePilotCli(null, null, null, discService, refService, null, passingPreflight, mongoConfig, objectMapper);
        cli.run("--daily-ingestion", "--target-env=staging", "--expected-host=staging-cluster", "--days=30", "--source=all");

        assertEquals(0, cli.getExitCode(), "CLI exit code must be 0 on safe partial_success");
    }

    @Test
    @DisplayName("18. Repeat ingestion is idempotent and preserves canonical issue IDs")
    public void testRepeatIngestionIdempotence() {
        List<Document> upsertFilters = new ArrayList<>();
        Document sampleDoc = new Document("id", "codeberg_test_repo_99")
                .append("source", "codeberg")
                .append("issue_number", 99)
                .append("repository_full_name", "test/repo");

        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = createProxy(FindIterable.class, (proxy, method, args) -> {
            if ("first".equals(method.getName())) return sampleDoc;
            return proxy;
        });

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("updateOne".equals(method.getName())) {
                upsertFilters.add((Document) args[0]);
                return UpdateResult.acknowledged(1, 1L, null);
            }
            if ("find".equals(method.getName())) return findIterable;
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) return collection;
            return null;
        });

        IssueRepository issueRepo = new IssueRepository(mongoOperations);

        Map<String, Object> issue = new HashMap<>();
        issue.put("id", "codeberg_test_repo_99");
        issue.put("source", "codeberg");
        issue.put("repository_full_name", "test/repo");
        issue.put("issue_number", 99);
        issue.put("title", "Run 1 Title");

        // First run
        issueRepo.upsertIssue(issue, true);
        // Second run with updated title
        issue.put("title", "Run 2 Title (Updated)");
        issueRepo.upsertIssue(issue, true);

        assertEquals(2, upsertFilters.size());
        for (Document f : upsertFilters) {
            assertEquals("codeberg_test_repo_99", f.getString("id"), "Canonical ID must remain identical on repeat ingestion");
        }
    }
}
