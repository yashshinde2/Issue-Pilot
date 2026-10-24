package com.issuepilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.adapter.*;
import com.issuepilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LiveVerifier {

    private static final Logger logger = LoggerFactory.getLogger(LiveVerifier.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final AdapterRegistry adapterRegistry;
    private final HttpClient httpClient;

    // Cache: key -> CacheEntry
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> rateLimitState = new ConcurrentHashMap<>();

    public static class VerificationResult {
        private final boolean valid;
        private final String status; // "live_verified", "excluded", "inconclusive", "sample_verified"
        private final String reason;
        private final String canonicalRepo;
        private final String canonicalUrl;
        private final boolean operationalFailure;
        private final boolean inconclusive;
        private final Map<String, Object> responseMetadata;

        public VerificationResult(
                boolean valid,
                String status,
                String reason,
                String canonicalRepo,
                String canonicalUrl,
                boolean operationalFailure,
                boolean inconclusive,
                Map<String, Object> responseMetadata
        ) {
            this.valid = valid;
            this.status = status;
            this.reason = reason;
            this.canonicalRepo = canonicalRepo;
            this.canonicalUrl = canonicalUrl;
            this.operationalFailure = operationalFailure;
            this.inconclusive = inconclusive;
            this.responseMetadata = responseMetadata != null ? responseMetadata : new HashMap<>();
        }

        public boolean isValid() { return valid; }
        public String getStatus() { return status; }
        public String getReason() { return reason; }
        public String getCanonicalRepo() { return canonicalRepo; }
        public String getCanonicalUrl() { return canonicalUrl; }
        public boolean isOperationalFailure() { return operationalFailure; }
        public boolean isInconclusive() { return inconclusive; }
        public Map<String, Object> getResponseMetadata() { return responseMetadata; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("is_valid", valid);
            m.put("status", status);
            m.put("reason", reason);
            m.put("canonical_repo", canonicalRepo);
            m.put("canonical_url", canonicalUrl);
            m.put("is_operational_failure", operationalFailure);
            m.put("is_inconclusive", inconclusive);
            m.put("response_metadata", responseMetadata);
            return m;
        }
    }

    private static class CacheEntry {
        final long cachedAt;
        final String etag;
        final VerificationResult result;

        CacheEntry(long cachedAt, String etag, VerificationResult result) {
            this.cachedAt = cachedAt;
            this.etag = etag;
            this.result = result;
        }
    }

    public static class VerificationAndBackfillResult {
        private final List<Map<String, Object>> deliveredRecommendations;
        private final List<Map<String, Object>> auditedRecords;
        private final Map<String, Object> metrics;

        public VerificationAndBackfillResult(
                List<Map<String, Object>> deliveredRecommendations,
                List<Map<String, Object>> auditedRecords,
                Map<String, Object> metrics
        ) {
            this.deliveredRecommendations = deliveredRecommendations;
            this.auditedRecords = auditedRecords;
            this.metrics = metrics;
        }

        public List<Map<String, Object>> getDeliveredRecommendations() { return deliveredRecommendations; }
        public List<Map<String, Object>> getAuditedRecords() { return auditedRecords; }
        public Map<String, Object> getMetrics() { return metrics; }
    }

    public LiveVerifier(AppProperties properties, ObjectMapper objectMapper, AdapterRegistry adapterRegistry) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.adapterRegistry = adapterRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getVerificationTimeoutSeconds()))
                .build();

        rateLimitState.put("limit", properties.getGithubToken() != null && !properties.getGithubToken().isBlank() ? 5000 : 60);
        rateLimitState.put("remaining", properties.getGithubToken() != null && !properties.getGithubToken().isBlank() ? 5000 : 60);
        rateLimitState.put("reset", System.currentTimeMillis() / 1000 + 3600);
    }

    public void clearCache() {
        cache.clear();
        rateLimitState.put("limit", properties.getGithubToken() != null && !properties.getGithubToken().isBlank() ? 5000 : 60);
        rateLimitState.put("remaining", properties.getGithubToken() != null && !properties.getGithubToken().isBlank() ? 5000 : 60);
        rateLimitState.put("reset", System.currentTimeMillis() / 1000 + 3600);
    }

    public Map<String, Object> getRateLimitState() {
        return Collections.unmodifiableMap(rateLimitState);
    }

    private void updateRateLimitFromHeaders(HttpResponse<?> response) {
        response.headers().firstValue("x-ratelimit-remaining").ifPresent(v -> {
            try { rateLimitState.put("remaining", Integer.parseInt(v)); } catch (Exception ignored) {}
        });
        response.headers().firstValue("x-ratelimit-limit").ifPresent(v -> {
            try { rateLimitState.put("limit", Integer.parseInt(v)); } catch (Exception ignored) {}
        });
        response.headers().firstValue("x-ratelimit-reset").ifPresent(v -> {
            try { rateLimitState.put("reset", Long.parseLong(v)); } catch (Exception ignored) {}
        });
    }

    public VerificationResult verifyCandidate(Map<String, Object> candidate, Integer timeout, Integer maxRetries) {
        String source = (String) candidate.get("source");
        if (source == null || source.isBlank()) {
            String id = (String) candidate.getOrDefault("id", "");
            String url = (String) candidate.getOrDefault("canonical_url", candidate.getOrDefault("issue_url", ""));
            if (id.startsWith("gl_") || url.contains("gitlab.com")) {
                source = "gitlab";
            } else if (id.startsWith("cb_") || url.contains("codeberg.org")) {
                source = "codeberg";
            } else if (id.startsWith("bz_") || url.contains("bugzilla")) {
                source = "bugzilla";
            } else {
                source = "github";
            }
        }
        source = source.toLowerCase().trim();

        switch (source) {
            case "gitlab":
                return verifyGitLabIssue(candidate, timeout, maxRetries);
            case "codeberg":
                return verifyCodebergIssue(candidate, timeout, maxRetries);
            case "bugzilla":
                return verifyBugzillaIssue(candidate, timeout, maxRetries);
            case "github":
            default: {
                String repo = (String) candidate.get("repository_full_name");
                Object numObj = candidate.get("issue_number");
                int number = numObj instanceof Number ? ((Number) numObj).intValue() : 0;
                return verifyGitHubIssue(repo, number, timeout, maxRetries);
            }
        }
    }

    public VerificationResult verifyGitHubIssue(String repoFullName, int issueNumber, Integer timeout, Integer maxRetries) {
        if (repoFullName == null || !repoFullName.contains("/") || issueNumber <= 0) {
            return new VerificationResult(
                    false, "excluded", "Invalid repository identity or issue number", repoFullName, null, false, false, null
            );
        }

        int timeoutSec = (timeout != null && timeout > 0) ? timeout : properties.getVerificationTimeoutSeconds();
        int retries = Math.max(1, (maxRetries != null && maxRetries > 0) ? maxRetries : properties.getVerificationMaxRetries());
        String cacheKey = "gh:" + repoFullName.toLowerCase() + "#" + issueNumber;
        long now = System.currentTimeMillis();

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null) {
            if ((now - cached.cachedAt) < (properties.getVerificationCacheTtlSeconds() * 1000L)) {
                return new VerificationResult(
                        cached.result.isValid(),
                        cached.result.getStatus(),
                        cached.result.getReason() + " (cached)",
                        cached.result.getCanonicalRepo(),
                        cached.result.getCanonicalUrl(),
                        cached.result.isOperationalFailure(),
                        cached.result.isInconclusive(),
                        Map.of("cached", true, "cached_at", cached.cachedAt)
                );
            }
        }

        // Rate limit buffer check
        Object remObj = rateLimitState.get("remaining");
        int remaining = remObj instanceof Number ? ((Number) remObj).intValue() : 999;
        if (remaining <= properties.getGithubRateLimitBuffer()) {
            return new VerificationResult(
                    false, "inconclusive",
                    "GitHub API rate limit budget exhausted (Remaining: " + remaining + " <= Buffer: " + properties.getGithubRateLimitBuffer() + "); verification deferred.",
                    repoFullName, null, true, true, Map.of("rate_limited", true, "remaining", remaining)
            );
        }

        String baseUrl = properties.getGithubApiBaseUrl().replaceAll("/+$", "");
        String url = baseUrl + "/repos/" + repoFullName + "/issues/" + issueNumber;

        Exception lastException = null;

        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "IssuePilot-Verifier/1.1")
                        .timeout(Duration.ofSeconds(timeoutSec));

                String token = properties.getGithubToken();
                if (token != null && !token.isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + token.trim());
                }

                if (cached != null && cached.etag != null) {
                    reqBuilder.header("If-None-Match", cached.etag);
                }

                HttpResponse<String> resp = httpClient.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                updateRateLimitFromHeaders(resp);

                int status = resp.statusCode();

                // 304 Not Modified
                if (status == 304 && cached != null) {
                    cache.put(cacheKey, new CacheEntry(now, cached.etag, cached.result));
                    return new VerificationResult(
                            cached.result.isValid(),
                            cached.result.getStatus(),
                            "Confirmed unmodified via ETag conditional request",
                            cached.result.getCanonicalRepo(),
                            cached.result.getCanonicalUrl(),
                            false,
                            false,
                            Map.of("status_code", 304, "etag", cached.etag)
                    );
                }

                // 403 or 429
                if (status == 403 || status == 429) {
                    boolean isRateLimit = status == 429
                            || "0".equals(resp.headers().firstValue("x-ratelimit-remaining").orElse(""))
                            || resp.body().toLowerCase().contains("rate limit");

                    if (isRateLimit) {
                        rateLimitState.put("remaining", 0);
                        return new VerificationResult(
                                false, "inconclusive",
                                "GitHub API rate limit exhausted (HTTP " + status + "); verification inconclusive",
                                repoFullName, null, true, true, Map.of("status_code", status, "rate_limited", true)
                        );
                    }
                    return new VerificationResult(
                            false, "excluded",
                            "Repository access restricted or forbidden (HTTP " + status + ")",
                            repoFullName, null, false, false, Map.of("status_code", status)
                    );
                }

                // 404 Not Found
                if (status == 404) {
                    VerificationResult r = new VerificationResult(
                            false, "excluded", "Issue or repository no longer exists (HTTP 404)", repoFullName, null, false, false, Map.of("status_code", 404)
                    );
                    cache.put(cacheKey, new CacheEntry(now, null, r));
                    return r;
                }

                // 5xx Transient server error
                if (status >= 500 && status < 600) {
                    if (attempt < retries - 1) {
                        Thread.sleep((long) (500 * Math.pow(2, attempt)));
                        continue;
                    }
                    return new VerificationResult(
                            false, "inconclusive",
                            "GitHub server error HTTP " + status + " after " + retries + " retries; verification inconclusive",
                            repoFullName, null, true, true, Map.of("status_code", status)
                    );
                }

                if (status != 200) {
                    return new VerificationResult(
                            false, "inconclusive",
                            "Unexpected GitHub HTTP " + status + " response",
                            repoFullName, null, true, true, Map.of("status_code", status)
                    );
                }

                // Parse 200 OK
                Map<String, Object> data = objectMapper.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
                String etag = resp.headers().firstValue("ETag").orElse(null);

                String htmlUrl = (String) data.get("html_url");
                String repoUrl = (String) data.get("repository_url");
                String canonicalRepo = repoFullName;
                if (repoUrl != null && repoUrl.contains("/repos/")) {
                    canonicalRepo = repoUrl.substring(repoUrl.indexOf("/repos/") + 7).replaceAll("^/+|/+$", "");
                } else if (htmlUrl != null && htmlUrl.contains("github.com/")) {
                    String[] parts = htmlUrl.substring(htmlUrl.indexOf("github.com/") + 11).split("/");
                    if (parts.length >= 2) {
                        canonicalRepo = parts[0] + "/" + parts[1];
                    }
                }
                String canonicalUrl = (htmlUrl != null && !htmlUrl.isBlank()) ? htmlUrl : "https://github.com/" + canonicalRepo + "/issues/" + issueNumber;

                Map<String, Object> meta = new HashMap<>();
                meta.put("status_code", 200);
                if (etag != null) meta.put("etag", etag);
                meta.put("canonical_repo", canonicalRepo);
                meta.put("canonical_url", canonicalUrl);

                // Pull request check
                if (data.containsKey("pull_request")) {
                    VerificationResult r = new VerificationResult(
                            false, "excluded", "Candidate is a pull request, not an issue", canonicalRepo, canonicalUrl, false, false, meta
                    );
                    cache.put(cacheKey, new CacheEntry(now, etag, r));
                    return r;
                }

                // State check
                String state = (String) data.getOrDefault("state", "unknown");
                if (!"open".equalsIgnoreCase(state)) {
                    VerificationResult r = new VerificationResult(
                            false, "excluded", "Issue is " + state + " on GitHub", canonicalRepo, canonicalUrl, false, false, meta
                    );
                    cache.put(cacheKey, new CacheEntry(now, etag, r));
                    return r;
                }

                // Assignee check
                Object assignee = data.get("assignee");
                Object assigneesObj = data.get("assignees");
                boolean hasAssignee = assignee != null || (assigneesObj instanceof List && !((List<?>) assigneesObj).isEmpty());
                if (hasAssignee) {
                    VerificationResult r = new VerificationResult(
                            false, "excluded", "Issue is assigned to another contributor", canonicalRepo, canonicalUrl, false, false, meta
                    );
                    cache.put(cacheKey, new CacheEntry(now, etag, r));
                    return r;
                }

                // Archived repo check
                Object repoDataObj = data.get("repository");
                boolean isArchived = Boolean.TRUE.equals(data.get("archived"))
                        || (repoDataObj instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) repoDataObj).get("archived")));
                if (isArchived) {
                    VerificationResult r = new VerificationResult(
                            false, "excluded", "Repository is archived and read-only", canonicalRepo, canonicalUrl, false, false, meta
                    );
                    cache.put(cacheKey, new CacheEntry(now, etag, r));
                    return r;
                }

                // Locked check
                if (Boolean.TRUE.equals(data.get("locked"))) {
                    String lockReason = (String) data.get("active_lock_reason");
                    if ("resolved".equalsIgnoreCase(lockReason) || "off-topic".equalsIgnoreCase(lockReason) || "spam".equalsIgnoreCase(lockReason)) {
                        VerificationResult r = new VerificationResult(
                                false, "excluded", "Issue is locked (" + lockReason + ")", canonicalRepo, canonicalUrl, false, false, meta
                        );
                        cache.put(cacheKey, new CacheEntry(now, etag, r));
                        return r;
                    }
                }

                // All checks passed! Confirmed open and eligible!
                VerificationResult r = new VerificationResult(
                        true, "live_verified", "Live GitHub state confirmed: Open & Unassigned", canonicalRepo, canonicalUrl, false, false, meta
                );
                cache.put(cacheKey, new CacheEntry(now, etag, r));
                return r;

            } catch (Exception e) {
                lastException = e;
                if (attempt < retries - 1) {
                    try {
                        Thread.sleep((long) (500 * Math.pow(2, attempt)));
                    } catch (InterruptedException ignored) {}
                }
            }
        }

        return new VerificationResult(
                false, "inconclusive",
                "GitHub connection timeout or network outage after " + retries + " attempts (" + (lastException != null ? lastException.getMessage() : "unknown") + ")",
                repoFullName, null, true, true, Map.of("error", lastException != null ? lastException.getMessage() : "timeout")
        );
    }

    public VerificationResult verifyGitLabIssue(Map<String, Object> candidate, Integer timeout, Integer maxRetries) {
        String repo = (String) candidate.get("repository_full_name");
        Object numObj = candidate.get("issue_number");
        int issueNum = numObj instanceof Number ? ((Number) numObj).intValue() : 0;
        String cacheKey = "gl:" + (repo != null ? repo.toLowerCase() : "") + "#" + issueNum;

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.cachedAt) < (properties.getVerificationCacheTtlSeconds() * 1000L)) {
            return cached.result;
        }

        try {
            BaseIssueSourceAdapter adapter = adapterRegistry.getAdapter("gitlab");
            String owner = "";
            String repoName = "";
            if (repo != null && repo.contains("/")) {
                String[] p = repo.split("/", 2);
                owner = p[0];
                repoName = p[1];
            }
            Map<String, Object> data = adapter.getIssue(owner, repoName, issueNum);
            if (data == null) {
                VerificationResult r = new VerificationResult(false, "excluded", "GitLab issue not found or deleted (HTTP 404)", repo, null, false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            String state = (String) data.getOrDefault("state", "opened");
            if (!"opened".equalsIgnoreCase(state) && !"open".equalsIgnoreCase(state)) {
                VerificationResult r = new VerificationResult(false, "excluded", "Issue is " + state + " on GitLab", repo, (String) data.get("web_url"), false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            Object assigneesObj = data.get("assignees");
            if (assigneesObj instanceof List && !((List<?>) assigneesObj).isEmpty()) {
                VerificationResult r = new VerificationResult(false, "excluded", "Issue is already assigned on GitLab", repo, (String) data.get("web_url"), false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            VerificationResult r = new VerificationResult(true, "live_verified", "Confirmed open and eligible via GitLab API", repo, (String) data.get("web_url"), false, false, null);
            cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
            return r;
        } catch (RateLimitExceededException e) {
            return new VerificationResult(false, "inconclusive", "GitLab rate limit budget exhausted: " + e.getMessage(), repo, null, true, true, null);
        } catch (Exception e) {
            return new VerificationResult(false, "inconclusive", "GitLab verification operational failure: " + e.getMessage(), repo, null, true, true, null);
        }
    }

    public VerificationResult verifyCodebergIssue(Map<String, Object> candidate, Integer timeout, Integer maxRetries) {
        String repo = (String) candidate.get("repository_full_name");
        Object numObj = candidate.get("issue_number");
        int issueNum = numObj instanceof Number ? ((Number) numObj).intValue() : 0;
        String cacheKey = "cb:" + (repo != null ? repo.toLowerCase() : "") + "#" + issueNum;

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.cachedAt) < (properties.getVerificationCacheTtlSeconds() * 1000L)) {
            return cached.result;
        }

        try {
            BaseIssueSourceAdapter adapter = adapterRegistry.getAdapter("codeberg");
            String owner = "";
            String repoName = "";
            if (repo != null && repo.contains("/")) {
                String[] p = repo.split("/", 2);
                owner = p[0];
                repoName = p[1];
            }
            Map<String, Object> data = adapter.getIssue(owner, repoName, issueNum);
            if (data == null) {
                VerificationResult r = new VerificationResult(false, "excluded", "Codeberg issue not found or deleted (HTTP 404)", repo, null, false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            if (data.containsKey("pull_request")) {
                VerificationResult r = new VerificationResult(false, "excluded", "Candidate is a pull request on Codeberg", repo, (String) data.get("html_url"), false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            String state = (String) data.getOrDefault("state", "open");
            if (!"open".equalsIgnoreCase(state)) {
                VerificationResult r = new VerificationResult(false, "excluded", "Issue is " + state + " on Codeberg", repo, (String) data.get("html_url"), false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            Object assignees = data.get("assignees");
            if ((assignees instanceof List && !((List<?>) assignees).isEmpty()) || data.get("assignee") != null) {
                VerificationResult r = new VerificationResult(false, "excluded", "Issue is already assigned on Codeberg", repo, (String) data.get("html_url"), false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            VerificationResult r = new VerificationResult(true, "live_verified", "Confirmed open and eligible via Codeberg API", repo, (String) data.get("html_url"), false, false, null);
            cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
            return r;
        } catch (RateLimitExceededException e) {
            return new VerificationResult(false, "inconclusive", "Codeberg rate limit budget exhausted: " + e.getMessage(), repo, null, true, true, null);
        } catch (Exception e) {
            return new VerificationResult(false, "inconclusive", "Codeberg verification operational failure: " + e.getMessage(), repo, null, true, true, null);
        }
    }

    public VerificationResult verifyBugzillaIssue(Map<String, Object> candidate, Integer timeout, Integer maxRetries) {
        String repo = (String) candidate.get("repository_full_name");
        Object numObj = candidate.get("issue_number");
        int issueNum = numObj instanceof Number ? ((Number) numObj).intValue() : 0;
        String cacheKey = "bz:#" + issueNum;

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.cachedAt) < (properties.getVerificationCacheTtlSeconds() * 1000L)) {
            return cached.result;
        }

        try {
            BaseIssueSourceAdapter adapter = adapterRegistry.getAdapter("bugzilla");
            Map<String, Object> bug = adapter.getIssue("", "", issueNum);
            if (bug == null) {
                VerificationResult r = new VerificationResult(false, "excluded", "Bugzilla bug not found or inaccessible (HTTP 404)", repo, null, false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            String bzStatus = ((String) bug.getOrDefault("status", "NEW")).toUpperCase();
            String resolution = ((String) bug.getOrDefault("resolution", "")).toUpperCase();
            Set<String> openStatuses = Set.of("NEW", "UNCONFIRMED", "ASSIGNED", "CONFIRMED", "IN_PROGRESS", "REOPENED");

            if (!openStatuses.contains(bzStatus) || !resolution.isBlank()) {
                VerificationResult r = new VerificationResult(false, "excluded", "Bug resolved or closed on Bugzilla (status: " + bzStatus + ")", repo, null, false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            String assignedTo = (String) bug.getOrDefault("assigned_to", "");
            if (assignedTo != null && !assignedTo.isBlank() && !assignedTo.toLowerCase().contains("nobody@")) {
                VerificationResult r = new VerificationResult(false, "excluded", "Bug is already assigned to " + assignedTo + " on Bugzilla", repo, null, false, false, null);
                cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
                return r;
            }

            String canonicalUrl = "https://bugzilla.mozilla.org/show_bug.cgi?id=" + issueNum;
            VerificationResult r = new VerificationResult(true, "live_verified", "Confirmed open and eligible via Bugzilla REST API", repo, canonicalUrl, false, false, null);
            cache.put(cacheKey, new CacheEntry(System.currentTimeMillis(), null, r));
            return r;
        } catch (RateLimitExceededException e) {
            return new VerificationResult(false, "inconclusive", "Bugzilla request budget exhausted: " + e.getMessage(), repo, null, true, true, null);
        } catch (Exception e) {
            return new VerificationResult(false, "inconclusive", "Bugzilla verification operational failure: " + e.getMessage(), repo, null, true, true, null);
        }
    }

    public VerificationAndBackfillResult verifyAndBackfill(
            List<Map<String, Object>> rankedCandidates,
            int targetCount,
            int maxBudget,
            boolean demoMode,
            Integer timeout,
            Integer maxRetries
    ) {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> delivered = new ArrayList<>();
        List<Map<String, Object>> audited = new ArrayList<>();

        int effectiveBudget = Math.min(maxBudget, rankedCandidates.size());
        int verifiedCount = 0;
        int excludedCount = 0;
        int inconclusiveCount = 0;

        for (int i = 0; i < rankedCandidates.size(); i++) {
            Map<String, Object> c = new HashMap<>(rankedCandidates.get(i));
            c.put("original_rank", i + 1);
            c.put("original_score", c.get("final_score"));

            if (delivered.size() < targetCount && i < effectiveBudget) {
                verifiedCount++;
                if (demoMode) {
                    c.put("verification_status", "sample_verified");
                    c.put("verification_notes", "Verified via local sample/offline demonstration dataset.");
                    c.put("decision", "included");
                    c.put("delivered_rank", delivered.size() + 1);
                    delivered.add(c);
                    audited.add(c);
                } else {
                    VerificationResult vResult = verifyCandidate(c, timeout, maxRetries);
                    c.put("verification_status", vResult.getStatus());
                    c.put("verification_notes", vResult.getReason());
                    c.put("canonical_repo", vResult.getCanonicalRepo());
                    c.put("canonical_url", vResult.getCanonicalUrl());
                    c.put("response_metadata", vResult.getResponseMetadata());

                    if (vResult.isValid()) {
                        c.put("decision", "included");
                        c.put("delivered_rank", delivered.size() + 1);
                        delivered.add(c);
                        audited.add(c);
                    } else if (vResult.isInconclusive()) {
                        inconclusiveCount++;
                        c.put("decision", "inconclusive");
                        c.put("exclusion_reason", vResult.getReason());
                        audited.add(c);
                    } else {
                        excludedCount++;
                        c.put("decision", "rejected");
                        c.put("exclusion_reason", vResult.getReason());
                        audited.add(c);
                    }
                }
            } else if (i < effectiveBudget) {
                // Already reached targetCount, mark remaining candidates in budget as skipped
                c.put("decision", "skipped");
                c.put("verification_status", "unverified");
                c.put("exclusion_reason", "Target recommendation count satisfied");
                audited.add(c);
            } else {
                // Beyond budget
                c.put("decision", "skipped");
                c.put("verification_status", "unverified");
                c.put("exclusion_reason", "Candidate exceeded verification shortlist budget");
                audited.add(c);
            }
        }

        // Renumber delivered ranks 1..N
        for (int idx = 0; idx < delivered.size(); idx++) {
            delivered.get(idx).put("delivered_rank", idx + 1);
            delivered.get(idx).put("rank", idx + 1);
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        boolean isPartial = delivered.size() < targetCount;
        String partialReason = null;
        if (isPartial) {
            if (inconclusiveCount > 0) {
                partialReason = "Verification inconclusive due to upstream rate limits or network issues.";
            } else {
                partialReason = "Fewer than " + targetCount + " eligible candidates verified within budget.";
            }
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("shortlist_size", effectiveBudget);
        metrics.put("candidates_verified", verifiedCount);
        metrics.put("candidates_excluded", excludedCount);
        metrics.put("candidates_inconclusive", inconclusiveCount);
        metrics.put("delivered_count", delivered.size());
        metrics.put("is_partial_result", isPartial);
        metrics.put("partial_result_reason", partialReason);
        metrics.put("duration_ms", elapsedMs);

        return new VerificationAndBackfillResult(delivered, audited, metrics);
    }
}
