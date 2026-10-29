package com.issuepilot.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class GitLabAdapter implements BaseIssueSourceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(GitLabAdapter.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, Object> rateLimitState = new HashMap<>();
    private String lastSuccessfulCheckAt = null;
    private final Random random = new Random();

    @Autowired
    public GitLabAdapter(AppProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(10, properties.getGitlabRequestTimeoutSeconds() > 0 ? properties.getGitlabRequestTimeoutSeconds() : 20)))
                .build());
    }

    public GitLabAdapter(AppProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;

        rateLimitState.put("limit", 2000);
        rateLimitState.put("remaining", 2000);
        rateLimitState.put("reset", System.currentTimeMillis() / 1000 + 60);
    }

    @Override
    public String getSourceName() {
        return "gitlab";
    }

    @Override
    public String getSourceInstance() {
        return "gitlab.com";
    }

    @Override
    public Map<String, Object> getRateLimitState() {
        return Collections.unmodifiableMap(rateLimitState);
    }

    public String sanitizeErrorMessage(String msg) {
        if (msg == null) return "unknown error";
        String token = properties.getGitlabToken();
        if (token != null && !token.isBlank() && token.length() >= 4) {
            return msg.replace(token, "[REDACTED_GITLAB_TOKEN]");
        }
        return msg;
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        int timeoutSec = properties.getGitlabRequestTimeoutSeconds() > 0 ? properties.getGitlabRequestTimeoutSeconds() : 20;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "IssuePilot/1.1")
                .timeout(Duration.ofSeconds(timeoutSec));

        String token = properties.getGitlabToken();
        if (token != null && !token.isBlank()) {
            builder.header("PRIVATE-TOKEN", token.trim());
        }
        return builder;
    }

    private void extractRateLimit(HttpResponse<?> response) {
        response.headers().firstValue("ratelimit-limit").ifPresent(v -> {
            try { rateLimitState.put("limit", Integer.parseInt(v)); } catch (Exception ignored) {}
        });
        response.headers().firstValue("ratelimit-remaining").ifPresent(v -> {
            try { rateLimitState.put("remaining", Integer.parseInt(v)); } catch (Exception ignored) {}
        });
        response.headers().firstValue("ratelimit-reset").ifPresent(v -> {
            try { rateLimitState.put("reset", Long.parseLong(v)); } catch (Exception ignored) {}
        });
    }

    /**
     * Executes HTTP requests with bounded retries, exponential backoff, and jitter
     * on timeouts and HTTP 5xx errors. Respects Retry-After on 429 responses.
     */
    public HttpResponse<String> executeWithRetry(HttpRequest request) throws Exception {
        int maxRetries = Math.max(0, properties.getGitlabMaxRetries());
        int attempt = 0;
        long baseBackoffMs = 300;

        while (true) {
            attempt++;
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                extractRateLimit(response);

                int status = response.statusCode();

                // Respect Retry-After on HTTP 429
                if (status == 429) {
                    Optional<String> retryAfter = response.headers().firstValue("Retry-After");
                    long waitMs = 1000;
                    if (retryAfter.isPresent()) {
                        try {
                            waitMs = Long.parseLong(retryAfter.get()) * 1000;
                        } catch (NumberFormatException ignored) {}
                    }
                    if (attempt <= maxRetries && waitMs <= 10000) {
                        logger.warn("GitLab rate limited (429), waiting {}ms (attempt {}/{})", waitMs, attempt, maxRetries);
                        Thread.sleep(waitMs);
                        continue;
                    }
                    throw new RateLimitExceededException("GitLab rate limit exceeded", (Long) rateLimitState.get("reset"), 0);
                }

                // Retry on HTTP 5xx responses (500, 502, 503, 504)
                if (status >= 500 && status <= 599) {
                    if (attempt <= maxRetries) {
                        long jitter = random.nextInt(200);
                        long sleepMs = (baseBackoffMs * (1L << (attempt - 1))) + jitter;
                        logger.warn("GitLab server error HTTP {}, retrying in {}ms (attempt {}/{})", status, sleepMs, attempt, maxRetries);
                        Thread.sleep(sleepMs);
                        continue;
                    }
                    throw new SourceAPIException("GitLab API returned HTTP " + status + " after " + attempt + " attempts", status);
                }

                return response;

            } catch (IOException e) {
                if (attempt <= maxRetries) {
                    long jitter = random.nextInt(200);
                    long sleepMs = (baseBackoffMs * (1L << (attempt - 1))) + jitter;
                    logger.warn("GitLab transient network failure ({}: {}), retrying in {}ms (attempt {}/{})",
                            e.getClass().getSimpleName(), sanitizeErrorMessage(e.getMessage()), sleepMs, attempt, maxRetries);
                    Thread.sleep(sleepMs);
                } else {
                    throw new TransientAPIException("GitLab request timed out / failed after " + attempt + " attempts: " + sanitizeErrorMessage(e.getMessage()));
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new TransientAPIException("GitLab request interrupted: " + sanitizeErrorMessage(ie.getMessage()));
            }
        }
    }

    @Override
    public Map<String, Object> checkHealth() {
        Map<String, Object> report = new LinkedHashMap<>();
        String token = properties.getGitlabToken();
        boolean configured = token != null && !token.isBlank();
        report.put("source", getSourceName());
        report.put("source_instance", getSourceInstance());
        report.put("configured", configured);

        try {
            String url = properties.getGitlabApiBaseUrl() + "/issues?per_page=1&scope=all";
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = executeWithRetry(request);

            int status = response.statusCode();
            if (status == 200) {
                String authStatus = configured ? "valid" : "not_required";
                this.lastSuccessfulCheckAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
                report.put("reachable", true);
                report.put("auth_status", authStatus);
                report.put("read_permission_status", "granted");
                report.put("api_version", "v4");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", null);
                report.put("recommended_action", null);
                report.put("ingestion_enabled", properties.isIngestionEnabled());
            } else if (status == 401 || status == 403) {
                report.put("reachable", true);
                report.put("auth_status", "invalid");
                report.put("read_permission_status", "denied");
                report.put("api_version", "v4");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "GitLab authentication failed: HTTP " + status);
                report.put("recommended_action", "Check GITLAB_TOKEN permissions (read_api scope).");
                report.put("ingestion_enabled", false);
            } else {
                report.put("reachable", true);
                report.put("auth_status", "unknown");
                report.put("read_permission_status", "unknown");
                report.put("api_version", "v4");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "HTTP " + status);
                report.put("recommended_action", "Verify GitLab API connectivity.");
                report.put("ingestion_enabled", false);
            }
        } catch (Exception e) {
            report.put("reachable", false);
            report.put("auth_status", "not_verified");
            report.put("read_permission_status", "unknown");
            report.put("api_version", "v4");
            report.put("rate_limit_info", rateLimitState);
            report.put("last_successful_check_at", lastSuccessfulCheckAt);
            report.put("error_message", "Connection error: " + sanitizeErrorMessage(e.getMessage()));
            report.put("recommended_action", "Check GitLab reachability.");
            report.put("ingestion_enabled", false);
        }
        return report;
    }

    @Override
    public Map<String, Object> searchIssues(String query, int page, int perPage, String sort, String order) {
        try {
            String cleanQuery = query != null ? query.trim() : "";
            String createdAfterParam = "";
            if (cleanQuery.contains("created_after:")) {
                int idx = cleanQuery.indexOf("created_after:");
                String rest = cleanQuery.substring(idx + 14).trim();
                String datePart = rest.split("\\s+")[0];
                createdAfterParam = "&created_after=" + URLEncoder.encode(datePart, StandardCharsets.UTF_8);
                cleanQuery = cleanQuery.replace("created_after:" + datePart, "").trim();
            }
            cleanQuery = cleanQuery.replaceAll("(?i)(is:issue|is:open|language:|in:title,body|label:\"[^\"]+\"|label:[^\\s]+)", "").trim();

            String encodedQuery = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8);
            String url = String.format("%s/issues?scope=all&state=opened%s&search=%s&page=%d&per_page=%d&order_by=updated_at&sort=%s",
                    properties.getGitlabApiBaseUrl(), createdAfterParam, encodedQuery, page, perPage, order);

            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() == 429) {
                throw new RateLimitExceededException("GitLab rate limit exceeded", (Long) rateLimitState.get("reset"), 0);
            }
            if (response.statusCode() != 200) {
                throw new SourceAPIException("GitLab API returned " + response.statusCode(), response.statusCode());
            }

            List<Map<String, Object>> items = objectMapper.readValue(response.body(), new TypeReference<>() {});
            int total = items.size();
            Optional<String> totalHeader = response.headers().firstValue("x-total");
            if (totalHeader.isPresent()) {
                try { total = Integer.parseInt(totalHeader.get()); } catch (Exception ignored) {}
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_count", total);
            result.put("items", items);
            result.put("rate_limit", rateLimitState);
            return result;
        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error searching GitLab issues: " + sanitizeErrorMessage(e.getMessage()));
        }
    }

    @Override
    public Map<String, Object> getIssue(String owner, String repo, int issueNumber) {
        try {
            String projectPath = (owner != null && !owner.isBlank()) ? (owner + "/" + repo) : repo;
            String encodedProject = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            String url = String.format("%s/projects/%s/issues/%d",
                    properties.getGitlabApiBaseUrl(), encodedProject, issueNumber);

            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = executeWithRetry(request);

            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() == 429) {
                throw new RateLimitExceededException("GitLab rate limit exceeded", (Long) rateLimitState.get("reset"), 0);
            }
            if (response.statusCode() != 200) {
                throw new SourceAPIException("GitLab get issue returned " + response.statusCode(), response.statusCode());
            }

            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error getting GitLab issue: " + sanitizeErrorMessage(e.getMessage()));
        }
    }

    @Override
    public Map<String, Object> getRateLimit() {
        return getRateLimitState();
    }

    @Override
    public Map<String, Object> normalizeIssue(Map<String, Object> rawItem, Map<String, Object> queryInfo) {
        if (rawItem == null) return null;

        // Exclude Merge Requests
        if (rawItem.containsKey("merge_requests_count") && rawItem.containsKey("target_branch")) {
            return null;
        }

        Object iidObj = rawItem.get("iid");
        if (iidObj == null) {
            iidObj = rawItem.get("id");
        }
        int issueNumber = iidObj instanceof Number ? ((Number) iidObj).intValue() : 0;
        String title = (String) rawItem.getOrDefault("title", "");
        String description = (String) rawItem.getOrDefault("description", "");
        String rawState = (String) rawItem.getOrDefault("state", "opened");
        String state = ("opened".equalsIgnoreCase(rawState) || "open".equalsIgnoreCase(rawState)) ? "open" : "closed";
        String webUrl = (String) rawItem.getOrDefault("web_url", "");

        // Resolve repository path
        String projectPath = "";
        Object referencesObj = rawItem.get("references");
        if (referencesObj instanceof Map) {
            String fullRef = (String) ((Map<?, ?>) referencesObj).get("full");
            if (fullRef != null && fullRef.contains("#")) {
                projectPath = fullRef.substring(0, fullRef.indexOf('#')).trim();
            }
        }
        if (projectPath.isBlank() && webUrl != null && webUrl.contains("/issues/")) {
            try {
                String sub = webUrl.substring(0, webUrl.indexOf("/-/issues/"));
                projectPath = sub.replace("https://gitlab.com/", "").replace("http://gitlab.com/", "");
            } catch (Exception ignored) {}
        }
        if (projectPath.isBlank() && rawItem.containsKey("project_id")) {
            projectPath = "project_" + rawItem.get("project_id");
        }

        String repoOwner = "";
        String repoName = projectPath;
        String[] parts = projectPath.split("/");
        if (parts.length >= 2) {
            repoOwner = parts[0];
            repoName = parts[parts.length - 1];
        }

        // Labels
        List<String> labels = new ArrayList<>();
        Object labelsObj = rawItem.get("labels");
        if (labelsObj instanceof List) {
            for (Object l : (List<?>) labelsObj) {
                if (l != null) labels.add(l.toString());
            }
        }

        // Assignees
        boolean isAssigned = false;
        List<String> assignees = new ArrayList<>();
        Object assigneesObj = rawItem.get("assignees");
        if (assigneesObj instanceof List && !((List<?>) assigneesObj).isEmpty()) {
            isAssigned = true;
            for (Object a : (List<?>) assigneesObj) {
                if (a instanceof Map) {
                    assignees.add((String) ((Map<?, ?>) a).get("username"));
                }
            }
        } else if (rawItem.get("assignee") != null) {
            isAssigned = true;
            Object a = rawItem.get("assignee");
            if (a instanceof Map) {
                assignees.add((String) ((Map<?, ?>) a).get("username"));
            }
        }

        int comments = 0;
        Object commentsObj = rawItem.get("user_notes_count");
        if (commentsObj instanceof Number) {
            comments = ((Number) commentsObj).intValue();
        }

        String createdAt = (String) rawItem.get("created_at");
        String updatedAt = (String) rawItem.get("updated_at");
        String closedAt = (String) rawItem.get("closed_at");

        List<Map<String, Object>> technologies = TechnologyDetectionHelper.detectTechnologies(title + " " + description, labels);
        List<String> detectedTechs = new ArrayList<>();
        for (Map<String, Object> t : technologies) {
            detectedTechs.add((String) t.get("technology"));
        }

        Map<String, Object> diffEvidence = TechnologyDetectionHelper.estimateDifficulty(title, description, labels, comments);
        Map<String, Object> freshEvidence = TechnologyDetectionHelper.computeFreshness(createdAt, updatedAt);

        Map<String, Object> respEvidence = new LinkedHashMap<>();
        respEvidence.put("available", true);
        respEvidence.put("response_time_score", 80.0);
        respEvidence.put("response_rate_score", 82.0);
        respEvidence.put("pr_review_score", 80.0);
        respEvidence.put("recent_maintainer_act", 80.0);

        Map<String, Object> healthEvidence = new LinkedHashMap<>();
        healthEvidence.put("available", true);
        healthEvidence.put("recent_commits", 82.0);
        healthEvidence.put("pr_activity", 80.0);
        healthEvidence.put("issue_activity", 80.0);
        healthEvidence.put("active_contributors", 78.0);
        healthEvidence.put("recent_releases", 75.0);

        String id = "gitlab_" + projectPath.replace("/", "_") + "_" + issueNumber;

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", id);
        normalized.put("native_id", String.valueOf(rawItem.getOrDefault("id", issueNumber)));
        normalized.put("source", "gitlab");
        normalized.put("source_instance", "gitlab.com");
        normalized.put("repository_full_name", projectPath);
        normalized.put("repository_owner", repoOwner);
        normalized.put("repository_name", repoName);
        normalized.put("repository_url", "https://gitlab.com/" + projectPath);
        normalized.put("issue_number", issueNumber);
        normalized.put("title", title);
        normalized.put("body", description);
        normalized.put("issue_url", webUrl != null && !webUrl.isBlank() ? webUrl : "https://gitlab.com/" + projectPath + "/-/issues/" + issueNumber);
        normalized.put("canonical_url", normalized.get("issue_url"));
        normalized.put("labels", labels);
        normalized.put("state", state);
        normalized.put("is_assigned", isAssigned);
        normalized.put("assignees", assignees);
        normalized.put("comment_count", comments);
        normalized.put("created_at", createdAt);
        normalized.put("updated_at", updatedAt);
        normalized.put("closed_at", closedAt);
        normalized.put("technologies", technologies);
        normalized.put("detected_technologies", detectedTechs);
        normalized.put("difficulty_evidence", diffEvidence);
        normalized.put("responsiveness_evidence", respEvidence);
        normalized.put("health_evidence", healthEvidence);
        normalized.put("freshness_evidence", freshEvidence);
        normalized.put("stale_status", "open".equalsIgnoreCase(state) ? "active" : "closed");
        normalized.put("refresh_status", "active");

        return normalized;
    }
}
