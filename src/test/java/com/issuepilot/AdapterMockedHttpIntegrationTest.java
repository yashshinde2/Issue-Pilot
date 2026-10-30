package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.adapter.*;
import com.issuepilot.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Mocked HTTP Integration Test Suite.
 * Validates deterministic HTTP error handling, rate limiting, pagination headers,
 * status transitions, and role resolution across GitHub, GitLab, Codeberg, and Bugzilla.
 *
 * Uses pure Java test doubles for HttpClient and HttpResponse for maximum reproducibility
 * across JDK versions without requiring dynamic agent bytecode instrumentation.
 */
public class AdapterMockedHttpIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AppProperties appProperties;
    private FakeHttpClient fakeHttpClient;

    static class FakeHttpResponse implements HttpResponse<String> {
        private final int statusCode;
        private final String body;
        private final HttpHeaders headers;

        public FakeHttpResponse(int statusCode, String body, Map<String, List<String>> headersMap) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = HttpHeaders.of(headersMap, (k, v) -> true);
        }

        @Override public int statusCode() { return statusCode; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return headers; }
        @Override public String body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("http://localhost"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    static class FakeHttpClient extends HttpClient {
        private HttpResponse<String> nextResponse;
        private Exception nextException;

        public void setNextResponse(HttpResponse<String> response) {
            this.nextResponse = response;
            this.nextException = null;
        }

        public void setNextException(Exception ex) {
            this.nextException = ex;
            this.nextResponse = null;
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return null; }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            if (nextException instanceof IOException) throw (IOException) nextException;
            if (nextException instanceof InterruptedException) throw (InterruptedException) nextException;
            if (nextException instanceof RuntimeException) throw (RuntimeException) nextException;
            return (HttpResponse<T>) nextResponse;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) nextResponse);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) nextResponse);
        }
    }

    @BeforeEach
    public void setup() {
        appProperties = new AppProperties();
        appProperties.setGithubToken("ghp_test_token");
        appProperties.setGithubApiBaseUrl("https://api.github.com");
        appProperties.setGitlabToken("glpat_test_token");
        appProperties.setGitlabApiBaseUrl("https://gitlab.com/api/v4");
        appProperties.setGitlabMaxRetries(0);
        appProperties.setCodebergToken("cb_test_token");
        appProperties.setCodebergApiBaseUrl("https://codeberg.org/api/v1");
        appProperties.setBugzillaBaseUrl("https://bugzilla.mozilla.org/rest");

        fakeHttpClient = new FakeHttpClient();
    }

    // ==========================================
    // 1. GitHub Adapter Mocked Tests
    // ==========================================

    @Test
    @DisplayName("GitHub: 200 OK checkHealth parses rate limit and reports valid auth")
    public void testGitHubCheckHealth_200OK() {
        Map<String, List<String>> headers = Map.of(
                "x-ratelimit-limit", List.of("5000"),
                "x-ratelimit-remaining", List.of("4990"),
                "x-ratelimit-reset", List.of("1700000000")
        );
        fakeHttpClient.setNextResponse(new FakeHttpResponse(200, "{\"resources\":{}}", headers));

        GitHubAdapter adapter = new GitHubAdapter(appProperties, objectMapper, fakeHttpClient);
        Map<String, Object> health = adapter.checkHealth();

        assertTrue((Boolean) health.get("reachable"));
        assertEquals("valid", health.get("auth_status"));
        assertEquals("granted", health.get("read_permission_status"));
        assertEquals("github", health.get("source"));
    }

    @Test
    @DisplayName("GitHub: 401 Unauthorized checkHealth reports bad credentials without throwing")
    public void testGitHubCheckHealth_401Unauthorized() {
        fakeHttpClient.setNextResponse(new FakeHttpResponse(401, "{\"message\":\"Bad credentials\"}", Collections.emptyMap()));

        GitHubAdapter adapter = new GitHubAdapter(appProperties, objectMapper, fakeHttpClient);
        Map<String, Object> health = adapter.checkHealth();

        assertTrue((Boolean) health.get("reachable"));
        assertEquals("invalid", health.get("auth_status"));
        assertEquals("denied", health.get("read_permission_status"));
        assertNotNull(health.get("error_message"));
        assertTrue(health.get("error_message").toString().contains("Bad credentials"));
    }

    @Test
    @DisplayName("GitHub: 429 Rate Limit throws RateLimitExceededException with reset timestamp")
    public void testGitHubSearchIssues_429RateLimit() {
        Map<String, List<String>> headers = Map.of(
                "x-ratelimit-limit", List.of("60"),
                "x-ratelimit-remaining", List.of("0"),
                "x-ratelimit-reset", List.of("1700005555")
        );
        fakeHttpClient.setNextResponse(new FakeHttpResponse(429, "{\"message\":\"API rate limit exceeded\"}", headers));

        GitHubAdapter adapter = new GitHubAdapter(appProperties, objectMapper, fakeHttpClient);
        RateLimitExceededException ex = assertThrows(RateLimitExceededException.class, () ->
                adapter.searchIssues("kubernetes", 1, 10, "created", "desc")
        );
        assertEquals(1700005555L, ex.getResetTimestamp());
    }

    @Test
    @DisplayName("GitHub: Multi-page Link header correctly parses next/last pages")
    public void testGitHubPaginationLinkHeader() {
        String linkHeaderWithNext = "<https://api.github.com/search/issues?page=2&per_page=10>; rel=\"next\", " +
                "<https://api.github.com/search/issues?page=5&per_page=10>; rel=\"last\"";
        assertTrue(PaginationCompletenessValidator.hasNextPageLinkHeader(linkHeaderWithNext));

        String linkHeaderTerminal = "<https://api.github.com/search/issues?page=1&per_page=10>; rel=\"prev\"";
        assertFalse(PaginationCompletenessValidator.hasNextPageLinkHeader(linkHeaderTerminal));
    }

    @Test
    @DisplayName("GitHub: Maintainer role resolution from comment author association")
    public void testGitHubMaintainerRoleResolution() {
        // OWNER / MEMBER / COLLABORATOR -> Maintainer
        Map<String, Object> memberComment = Map.of(
                "user", Map.of("login", "octocat", "type", "User"),
                "author_association", "MEMBER"
        );
        var memberRole = MaintainerResolutionHelper.resolveGitHubCommentMaintainer(memberComment);
        assertTrue(memberRole.available());
        assertTrue(memberRole.isMaintainer());

        // CONTRIBUTOR / NONE -> Not maintainer
        Map<String, Object> contribComment = Map.of(
                "user", Map.of("login", "contributor1", "type", "User"),
                "author_association", "CONTRIBUTOR"
        );
        var contribRole = MaintainerResolutionHelper.resolveGitHubCommentMaintainer(contribComment);
        assertTrue(contribRole.available());
        assertFalse(contribRole.isMaintainer());

        // Bot comment -> Excluded
        Map<String, Object> botComment = Map.of(
                "user", Map.of("login", "dependabot[bot]", "type", "Bot"),
                "author_association", "MEMBER"
        );
        var botRole = MaintainerResolutionHelper.resolveGitHubCommentMaintainer(botComment);
        assertFalse(botRole.isMaintainer());
        assertTrue(botRole.isBot());
    }

    // ==========================================
    // 2. GitLab Adapter Mocked Tests
    // ==========================================

    @Test
    @DisplayName("GitLab: 200 OK checkHealth parses ratelimit headers and reports valid auth")
    public void testGitLabCheckHealth_200OK() {
        Map<String, List<String>> headers = Map.of(
                "ratelimit-limit", List.of("2000"),
                "ratelimit-remaining", List.of("1995"),
                "ratelimit-reset", List.of("1700000060")
        );
        fakeHttpClient.setNextResponse(new FakeHttpResponse(200, "{\"version\":\"16.8.0\",\"revision\":\"abc\"}", headers));

        GitLabAdapter adapter = new GitLabAdapter(appProperties, objectMapper, fakeHttpClient);
        Map<String, Object> health = adapter.checkHealth();

        assertTrue((Boolean) health.get("reachable"));
        assertEquals("valid", health.get("auth_status"));
        assertEquals("granted", health.get("read_permission_status"));
        assertEquals("gitlab", health.get("source"));
    }

    @Test
    @DisplayName("GitLab: 403 Forbidden on project membership returns RoleResult.unavailable")
    public void testGitLabMembership403ReturnsUnavailable() {
        var result = MaintainerResolutionHelper.resolveGitLabProjectMaintainer("alice", 12345, null, 403);
        assertFalse(result.available());
        assertFalse(result.isMaintainer());
        assertTrue(result.reason().contains("403"));
    }

    @Test
    @DisplayName("GitLab: Access levels >= 40 are maintainers; bot users are excluded")
    public void testGitLabAccessLevels() {
        // Developer (30) -> nonMaintainer, Maintainer (40) -> maintainer, Owner (50) -> maintainer
        Map<String, Object> devData = Map.of("access_level", 30, "bot", false);
        var devRole = MaintainerResolutionHelper.resolveGitLabProjectMaintainer("alice", 12345, devData, 200);
        assertTrue(devRole.available());
        assertFalse(devRole.isMaintainer());

        Map<String, Object> maintData = Map.of("access_level", 40, "bot", false);
        var maintRole = MaintainerResolutionHelper.resolveGitLabProjectMaintainer("bob", 12345, maintData, 200);
        assertTrue(maintRole.available());
        assertTrue(maintRole.isMaintainer());

        // Bot with Developer (30) access -> Excluded
        Map<String, Object> botData = Map.of("access_level", 40, "bot", true);
        var botRole = MaintainerResolutionHelper.resolveGitLabProjectMaintainer("gitlab-bot", 12345, botData, 200);
        assertFalse(botRole.isMaintainer());
        assertTrue(botRole.isBot());
    }

    @Test
    @DisplayName("GitLab: X-Next-Page header parsed by PaginationCompletenessValidator")
    public void testGitLabPaginationHeader() {
        assertTrue(PaginationCompletenessValidator.hasNextPageGitLabHeader("2"));
        assertFalse(PaginationCompletenessValidator.hasNextPageGitLabHeader(""));
        assertFalse(PaginationCompletenessValidator.hasNextPageGitLabHeader(null));
    }

    // ==========================================
    // 3. Codeberg / Forgejo Adapter Mocked Tests
    // ==========================================

    @Test
    @DisplayName("Codeberg: checkHealth identifies Forgejo/Gitea capabilities via /api/v1/version")
    public void testCodebergVersionCapabilityDiscovery() {
        Map<String, List<String>> headers = Map.of(
                "x-ratelimit-limit", List.of("1000"),
                "x-ratelimit-remaining", List.of("999")
        );
        fakeHttpClient.setNextResponse(new FakeHttpResponse(200, "{\"version\":\"1.21.0+forgejo\"}", headers));

        CodebergAdapter adapter = new CodebergAdapter(appProperties, objectMapper, fakeHttpClient);
        Map<String, Object> health = adapter.checkHealth();

        assertTrue((Boolean) health.get("reachable"));
        assertEquals("valid", health.get("auth_status"));
        assertEquals("granted", health.get("read_permission_status"));
        assertEquals("codeberg", health.get("source"));
    }

    @Test
    @DisplayName("Codeberg: Collaborator write/admin permission identifies maintainer")
    public void testCodebergCollaboratorPermissions() {
        // write / admin -> Maintainer
        Map<String, Object> adminData = Map.of("permissions", Map.of("admin", true, "push", true));
        var adminRole = MaintainerResolutionHelper.resolveCodebergRepoMaintainer("charlie", "forgejo/forgejo", adminData, 200);
        assertTrue(adminRole.available());
        assertTrue(adminRole.isMaintainer());

        // read -> Not maintainer
        Map<String, Object> readData = Map.of("permissions", Map.of("admin", false, "push", false));
        var readRole = MaintainerResolutionHelper.resolveCodebergRepoMaintainer("dave", "forgejo/forgejo", readData, 200);
        assertTrue(readRole.available());
        assertFalse(readRole.isMaintainer());

        // 403 (insufficient permissions) -> available=false
        var permDenied = MaintainerResolutionHelper.resolveCodebergRepoMaintainer("eve", "forgejo/forgejo", null, 403);
        assertFalse(permDenied.available());
    }

    // ==========================================
    // 4. Bugzilla Adapter Mocked Tests
    // ==========================================

    @Test
    @DisplayName("Bugzilla: 200 OK getIssue parses bug and enforces unassigned placeholder")
    public void testBugzillaGetIssue_200OK() {
        String bugJson = "{\"bugs\":[{" +
                "\"id\":1899123," +
                "\"summary\":\"WebRender glyph rendering artifact\"," +
                "\"product\":\"Core\"," +
                "\"component\":\"Graphics: WebRender\"," +
                "\"status\":\"NEW\"," +
                "\"assigned_to\":\"nobody@mozilla.org\"," +
                "\"creation_time\":\"2024-05-01T10:00:00Z\"," +
                "\"last_change_time\":\"2024-05-02T12:00:00Z\"," +
                "\"comments\":[{\"count\":0,\"author\":\"test@mozilla.org\"}]" +
                "}]}";

        fakeHttpClient.setNextResponse(new FakeHttpResponse(200, bugJson, Collections.emptyMap()));

        BugzillaAdapter adapter = new BugzillaAdapter(appProperties, objectMapper, fakeHttpClient);
        Map<String, Object> bug = adapter.getIssue("Core", "Graphics: WebRender", 1899123);

        assertNotNull(bug);
        assertEquals(1899123, bug.get("id"));

        Map<String, Object> normalized = adapter.normalizeIssue(bug, Collections.emptyMap());
        assertNotNull(normalized);
        assertEquals("bugzilla|bugzilla.mozilla.org|Core:Graphics: WebRender|1899123", normalized.get("issue_key"));
        assertFalse((Boolean) normalized.get("is_assigned"), "nobody@mozilla.org placeholder must be unassigned");
        assertNull(normalized.get("repository_full_name"), "Must never fabricate repo full name");
    }

    @Test
    @DisplayName("Bugzilla: 401 Unauthorized throws SourceAPIException")
    public void testBugzilla_401Unauthorized() {
        String errJson = "{\"error\":true,\"message\":\"You must log in to access Bugzilla\"}";
        fakeHttpClient.setNextResponse(new FakeHttpResponse(401, errJson, Collections.emptyMap()));

        BugzillaAdapter adapter = new BugzillaAdapter(appProperties, objectMapper, fakeHttpClient);
        SourceAPIException ex = assertThrows(SourceAPIException.class, () ->
                adapter.getIssue("Core", "Graphics", 999999)
        );
        assertEquals(401, ex.getStatusCode());
    }

    @Test
    @DisplayName("Bugzilla: Status transitions and resolution semantics")
    public void testBugzillaStatusTransitions() {
        BugzillaAdapter adapter = new BugzillaAdapter(appProperties, objectMapper, fakeHttpClient);

        // Active statuses without resolution
        for (String status : List.of("UNCONFIRMED", "NEW", "ASSIGNED", "REOPENED")) {
            Map<String, Object> raw = Map.of("id", 1, "product", "P", "component", "C", "status", status, "resolution", "");
            Map<String, Object> norm = adapter.normalizeIssue(raw, Collections.emptyMap());
            assertEquals("open", norm.get("state"), "Status " + status + " must map to open");
        }

        // Terminal statuses with resolution
        for (String status : List.of("RESOLVED", "VERIFIED", "CLOSED")) {
            Map<String, Object> raw = Map.of("id", 2, "product", "P", "component", "C", "status", status, "resolution", "FIXED");
            Map<String, Object> norm = adapter.normalizeIssue(raw, Collections.emptyMap());
            assertEquals("closed", norm.get("state"), "Status " + status + " with resolution FIXED must map to closed");
        }
    }

    // ==============================================================
    // 5. Cross-Platform Pagination Completeness Verification Tests
    // ==============================================================

    @Test
    @DisplayName("Pagination: GitHub & Codeberg Link Header Completeness Semantics")
    public void testGitHubAndCodebergPaginationCompleteness() {
        // 1. Single page response without Link header: Complete
        var singlePage = PaginationCompletenessValidator.evaluateGitHubLinkPagination(null, 15, 30, false, 200);
        assertTrue(singlePage.isComplete(), "Single-page response without Link header must be complete");
        assertFalse(singlePage.hasMorePages());
        assertEquals(15, singlePage.itemsRetrieved());

        // 2. Active pagination with rel="next": In progress (incomplete)
        String linkWithNext = "<https://api.github.com/issues?page=2>; rel=\"next\", <https://api.github.com/issues?page=5>; rel=\"last\"";
        var inProgress = PaginationCompletenessValidator.evaluateGitHubLinkPagination(linkWithNext, 30, 30, false, 200);
        assertFalse(inProgress.isComplete(), "Responses with rel=\"next\" are not complete");
        assertTrue(inProgress.hasMorePages());

        // 3. Multi-page final page without rel="next": Complete
        String finalPageLink = "<https://api.github.com/issues?page=4>; rel=\"prev\", <https://api.github.com/issues?page=1>; rel=\"first\"";
        var multiPageFinal = PaginationCompletenessValidator.evaluateGitHubLinkPagination(finalPageLink, 12, 30, false, 200);
        assertTrue(multiPageFinal.isComplete(), "Terminal page lacking rel=\"next\" must be complete");
        assertFalse(multiPageFinal.hasMorePages());

        // 4. Malformed Link header: Incomplete / malformed
        var malformed = PaginationCompletenessValidator.evaluateGitHubLinkPagination("not-a-valid-link-header", 10, 30, false, 200);
        assertFalse(malformed.isComplete(), "Malformed link header must produce isComplete = false");

        // 5. Interrupted or rate-limited traversal: Incomplete
        var rateLimited = PaginationCompletenessValidator.evaluateGitHubLinkPagination(linkWithNext, 30, 30, false, 429);
        assertFalse(rateLimited.isComplete(), "Rate limited response must produce isComplete = false");

        var interrupted = PaginationCompletenessValidator.evaluateGitHubLinkPagination(linkWithNext, 30, 30, true, 200);
        assertFalse(interrupted.isComplete(), "Interrupted loop must produce isComplete = false");
    }

    @Test
    @DisplayName("Pagination: GitLab X-Next-Page Header Completeness Semantics")
    public void testGitLabPaginationCompleteness() {
        // 1. Single page or final page (null or empty X-Next-Page): Complete
        var completeNull = PaginationCompletenessValidator.evaluateGitLabPagination(null, 25, false, 200);
        assertTrue(completeNull.isComplete());
        assertFalse(completeNull.hasMorePages());

        var completeEmpty = PaginationCompletenessValidator.evaluateGitLabPagination("", 25, false, 200);
        assertTrue(completeEmpty.isComplete());

        var completeZero = PaginationCompletenessValidator.evaluateGitLabPagination("0", 25, false, 200);
        assertTrue(completeZero.isComplete());

        // 2. Active pagination with next page: In progress
        var inProgress = PaginationCompletenessValidator.evaluateGitLabPagination("2", 100, false, 200);
        assertFalse(inProgress.isComplete());
        assertTrue(inProgress.hasMorePages());

        // 3. Malformed non-numeric X-Next-Page: Incomplete
        var malformed = PaginationCompletenessValidator.evaluateGitLabPagination("invalid-page-token", 50, false, 200);
        assertFalse(malformed.isComplete());

        // 4. Rate-limited or interrupted: Incomplete
        var rateLimited = PaginationCompletenessValidator.evaluateGitLabPagination("2", 100, false, 429);
        assertFalse(rateLimited.isComplete());

        var interrupted = PaginationCompletenessValidator.evaluateGitLabPagination("2", 100, true, 200);
        assertFalse(interrupted.isComplete());
    }

    @Test
    @DisplayName("Pagination: Bugzilla Offset/Limit/TotalMatches Completeness Semantics")
    public void testBugzillaPaginationCompleteness() {
        // 1. Complete single page: items >= total matches
        var complete = PaginationCompletenessValidator.evaluateBugzillaPagination(0, 50, 20, 20, false, 200);
        assertTrue(complete.isComplete());
        assertFalse(complete.hasMorePages());

        // 2. Complete multi-page exhaustion: offset + items == total matches
        var multiComplete = PaginationCompletenessValidator.evaluateBugzillaPagination(40, 20, 55, 15, false, 200);
        assertTrue(multiComplete.isComplete());
        assertFalse(multiComplete.hasMorePages());

        // 3. In progress: offset + items < total matches
        var inProgress = PaginationCompletenessValidator.evaluateBugzillaPagination(0, 20, 55, 20, false, 200);
        assertFalse(inProgress.isComplete());
        assertTrue(inProgress.hasMorePages());

        // 4. Malformed parameters: null or negative offset
        var malformed = PaginationCompletenessValidator.evaluateBugzillaPagination(null, 20, 55, 20, false, 200);
        assertFalse(malformed.isComplete());

        // 5. Interrupted or error: Incomplete
        var httpError = PaginationCompletenessValidator.evaluateBugzillaPagination(0, 20, 55, 20, false, 401);
        assertFalse(httpError.isComplete());

        var interrupted = PaginationCompletenessValidator.evaluateBugzillaPagination(0, 20, 55, 20, true, 200);
        assertFalse(interrupted.isComplete());
    }
}
