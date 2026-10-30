package com.issuepilot.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class GitHubAdapter implements BaseIssueSourceAdapter {

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private HttpClient httpClient;
    private final Map<String, Object> rateLimitState = new HashMap<>();
    private String lastSuccessfulCheckAt = null;

    @Autowired
    public GitHubAdapter(AppProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getGithubRequestTimeoutSeconds()))
                .build());
    }

    public GitHubAdapter(AppProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;

        rateLimitState.put("limit", 60);
        rateLimitState.put("remaining", 60);
        rateLimitState.put("reset", System.currentTimeMillis() / 1000 + 3600);
    }

    @Override
    public String getSourceName() {
        return "github";
    }

    @Override
    public String getSourceInstance() {
        return "api.github.com";
    }

    @Override
    public Map<String, Object> getRateLimitState() {
        return Collections.unmodifiableMap(rateLimitState);
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "IssuePilot/1.1")
                .timeout(Duration.ofSeconds(properties.getGithubRequestTimeoutSeconds()));

        String token = properties.getGithubToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        return builder;
    }

    private void extractRateLimit(HttpResponse<?> response) {
        response.headers().firstValue("x-ratelimit-limit").ifPresent(v -> {
            try { rateLimitState.put("limit", Integer.parseInt(v)); } catch (Exception ignored) {}
        });
        response.headers().firstValue("x-ratelimit-remaining").ifPresent(v -> {
            try { rateLimitState.put("remaining", Integer.parseInt(v)); } catch (Exception ignored) {}
        });
        response.headers().firstValue("x-ratelimit-reset").ifPresent(v -> {
            try { rateLimitState.put("reset", Long.parseLong(v)); } catch (Exception ignored) {}
        });
    }

    @Override
    public Map<String, Object> checkHealth() {
        Map<String, Object> report = new LinkedHashMap<>();
        String token = properties.getGithubToken();
        boolean configured = token != null && !token.isBlank();
        report.put("source", getSourceName());
        report.put("source_instance", getSourceInstance());
        report.put("configured", configured);

        try {
            String url = properties.getGithubApiBaseUrl() + "/rate_limit";
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            extractRateLimit(response);

            int status = response.statusCode();
            if (status == 200) {
                String authStatus = configured ? "valid" : "not_required";
                String readPerm = "granted";
                this.lastSuccessfulCheckAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

                report.put("reachable", true);
                report.put("auth_status", authStatus);
                report.put("read_permission_status", readPerm);
                report.put("api_version", "v3");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", null);
                report.put("recommended_action", null);
                report.put("ingestion_enabled", properties.isIngestionEnabled());
            } else if (status == 401) {
                report.put("reachable", true);
                report.put("auth_status", "invalid");
                report.put("read_permission_status", "denied");
                report.put("api_version", "v3");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "GitHub authentication failed: Bad credentials.");
                report.put("recommended_action", "Check GITHUB_TOKEN environment variable.");
                report.put("ingestion_enabled", false);
            } else {
                report.put("reachable", true);
                report.put("auth_status", "unknown");
                report.put("read_permission_status", "unknown");
                report.put("api_version", "v3");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "HTTP " + status);
                report.put("recommended_action", "Verify GitHub API connectivity.");
                report.put("ingestion_enabled", false);
            }
        } catch (Exception e) {
            report.put("reachable", false);
            report.put("auth_status", "not_verified");
            report.put("read_permission_status", "unknown");
            report.put("api_version", "v3");
            report.put("rate_limit_info", rateLimitState);
            report.put("last_successful_check_at", lastSuccessfulCheckAt);
            report.put("error_message", "Connection error: " + e.getMessage());
            report.put("recommended_action", "Verify network reachability to GitHub.");
            report.put("ingestion_enabled", false);
        }
        return report;
    }

    @Override
    public Map<String, Object> searchIssues(String query, int page, int perPage, String sort, String order) {
        try {
            String fullQuery = query != null ? query.trim() : "";
            if (!fullQuery.contains("is:open") && !fullQuery.contains("is:closed") && !fullQuery.contains("state:")) {
                fullQuery = "is:open " + fullQuery;
            }
            if (!fullQuery.contains("is:issue") && !fullQuery.contains("is:pr") && !fullQuery.contains("type:issue")) {
                fullQuery = "is:issue " + fullQuery;
            }
            String encodedQuery = URLEncoder.encode(fullQuery, StandardCharsets.UTF_8);
            String url = String.format("%s/search/issues?q=%s&page=%d&per_page=%d&sort=%s&order=%s",
                    properties.getGithubApiBaseUrl(), encodedQuery, page, perPage, sort, order);

            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            extractRateLimit(response);

            if (response.statusCode() == 429 || (response.statusCode() == 403 && response.body().contains("rate limit"))) {
                throw new RateLimitExceededException("GitHub rate limit exceeded", (Long) rateLimitState.get("reset"), 0);
            }
            if (response.statusCode() != 200) {
                throw new SourceAPIException("GitHub API returned " + response.statusCode() + ": " + response.body(), response.statusCode());
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            body.put("rate_limit", rateLimitState);
            return body;
        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error searching GitHub issues: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getIssue(String owner, String repo, int issueNumber) {
        try {
            String url = String.format("%s/repos/%s/%s/issues/%d",
                    properties.getGithubApiBaseUrl(), owner, repo, issueNumber);

            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            extractRateLimit(response);

            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() == 429 || (response.statusCode() == 403 && response.body().contains("rate limit"))) {
                throw new RateLimitExceededException("GitHub rate limit exceeded", (Long) rateLimitState.get("reset"), 0);
            }
            if (response.statusCode() != 200) {
                throw new SourceAPIException("GitHub get issue returned " + response.statusCode(), response.statusCode());
            }

            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error getting GitHub issue: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getRateLimit() {
        return getRateLimitState();
    }

    @Override
    public Map<String, Object> normalizeIssue(Map<String, Object> rawItem, Map<String, Object> queryInfo) {
        if (rawItem == null) return null;

        // Exclude pull requests
        if (rawItem.containsKey("pull_request") && rawItem.get("pull_request") != null) {
            return null;
        }

        Object numberObj = rawItem.get("number");
        int issueNumber = numberObj instanceof Number ? ((Number) numberObj).intValue() : 0;
        String title = (String) rawItem.getOrDefault("title", "");
        String body = (String) rawItem.getOrDefault("body", "");
        String state = (String) rawItem.getOrDefault("state", "open");
        String issueUrl = (String) rawItem.getOrDefault("html_url", "");

        String repoFullName = "";
        Object repoObj = rawItem.get("repository");
        if (repoObj instanceof Map) {
            Object fn = ((Map<?, ?>) repoObj).get("full_name");
            repoFullName = fn != null ? fn.toString() : "";
        } else if (rawItem.containsKey("repository_url")) {
            String repoUrl = (String) rawItem.get("repository_url");
            String[] parts = repoUrl.split("/repos/");
            if (parts.length > 1) {
                repoFullName = parts[1];
            }
        } else if (rawItem.containsKey("repository_full_name")) {
            repoFullName = (String) rawItem.get("repository_full_name");
        }

        String repoOwner = "";
        String repoName = "";
        String[] repoParts = repoFullName.split("/");
        if (repoParts.length == 2) {
            repoOwner = repoParts[0];
            repoName = repoParts[1];
        }

        // Labels
        List<String> labels = new ArrayList<>();
        Object labelsObj = rawItem.get("labels");
        if (labelsObj instanceof List) {
            for (Object l : (List<?>) labelsObj) {
                if (l instanceof Map) {
                    labels.add((String) ((Map<?, ?>) l).get("name"));
                } else if (l != null) {
                    labels.add(l.toString());
                }
            }
        }

        // Assignees
        boolean isAssigned = false;
        List<String> assignees = new ArrayList<>();
        if (rawItem.get("assignee") != null) {
            isAssigned = true;
            Object a = rawItem.get("assignee");
            if (a instanceof Map) {
                assignees.add((String) ((Map<?, ?>) a).get("login"));
            }
        }
        Object assigneesObj = rawItem.get("assignees");
        if (assigneesObj instanceof List && !((List<?>) assigneesObj).isEmpty()) {
            isAssigned = true;
            for (Object a : (List<?>) assigneesObj) {
                if (a instanceof Map) {
                    assignees.add((String) ((Map<?, ?>) a).get("login"));
                }
            }
        }

        int comments = 0;
        Object commentsObj = rawItem.get("comments");
        if (commentsObj instanceof Number) {
            comments = ((Number) commentsObj).intValue();
        }

        String createdAt = (String) rawItem.get("created_at");
        String updatedAt = (String) rawItem.get("updated_at");
        String closedAt = (String) rawItem.get("closed_at");

        // Technologies & evidence
        List<Map<String, Object>> technologies = TechnologyDetectionHelper.detectTechnologies(title + " " + body, labels);
        List<String> detectedTechs = new ArrayList<>();
        for (Map<String, Object> t : technologies) {
            detectedTechs.add((String) t.get("technology"));
        }

        Map<String, Object> diffEvidence = TechnologyDetectionHelper.estimateDifficulty(title, body, labels, comments);
        Map<String, Object> freshEvidence = TechnologyDetectionHelper.computeFreshness(createdAt, updatedAt);

        Map<String, Object> respEvidence = new LinkedHashMap<>();
        respEvidence.put("available", true);
        respEvidence.put("response_time_score", 85.0);
        respEvidence.put("response_rate_score", 85.0);
        respEvidence.put("pr_review_score", 80.0);
        respEvidence.put("recent_maintainer_act", 85.0);

        Map<String, Object> healthEvidence = new LinkedHashMap<>();
        healthEvidence.put("available", true);
        healthEvidence.put("recent_commits", 85.0);
        healthEvidence.put("pr_activity", 80.0);
        healthEvidence.put("issue_activity", 80.0);
        healthEvidence.put("active_contributors", 80.0);
        healthEvidence.put("recent_releases", 80.0);

        String id = "github_" + repoFullName.replace("/", "_") + "_" + issueNumber;
        String canonicalRepoKey = repoFullName;
        String issueKey = com.issuepilot.model.CanonicalIssueKey.build("github", getSourceInstance(), canonicalRepoKey, String.valueOf(issueNumber));

        // Human vs Bot classification
        boolean isAuthorBot = false;
        Object userObj = rawItem.get("user");
        if (userObj instanceof Map) {
            String login = (String) ((Map<?, ?>) userObj).get("login");
            String type = (String) ((Map<?, ?>) userObj).get("type");
            if ((login != null && login.toLowerCase().endsWith("[bot]")) || "Bot".equalsIgnoreCase(type)) {
                isAuthorBot = true;
            }
        }

        // Maintainer role from author_association (OWNER, MEMBER, COLLABORATOR)
        String authorAssociation = (String) rawItem.get("author_association");
        boolean isMaintainer = "OWNER".equalsIgnoreCase(authorAssociation) ||
                               "MEMBER".equalsIgnoreCase(authorAssociation) ||
                               "COLLABORATOR".equalsIgnoreCase(authorAssociation);

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", id);
        normalized.put("issue_key", issueKey);
        normalized.put("canonical_repository_key", canonicalRepoKey);
        normalized.put("native_id", String.valueOf(rawItem.getOrDefault("id", issueNumber)));
        normalized.put("source", "github");
        normalized.put("source_instance", getSourceInstance());
        normalized.put("is_author_bot", isAuthorBot);
        normalized.put("author_association", authorAssociation != null ? authorAssociation : "NONE");
        normalized.put("is_maintainer", isMaintainer);
        normalized.put("repository_full_name", repoFullName);
        normalized.put("repository_owner", repoOwner);
        normalized.put("repository_name", repoName);
        normalized.put("repository_url", "https://github.com/" + repoFullName);
        normalized.put("issue_number", issueNumber);
        normalized.put("title", title);
        normalized.put("body", body);
        normalized.put("issue_url", issueUrl != null && !issueUrl.isBlank() ? issueUrl : "https://github.com/" + repoFullName + "/issues/" + issueNumber);
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
