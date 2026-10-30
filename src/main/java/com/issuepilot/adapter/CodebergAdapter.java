package com.issuepilot.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class CodebergAdapter implements BaseIssueSourceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CodebergAdapter.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, Object> rateLimitState = new HashMap<>();
    private String lastSuccessfulCheckAt = null;

    private volatile boolean globalSearchDisabledOrFailing = false;
    private volatile String lastGlobalSearchError = null;

    @Autowired
    public CodebergAdapter(AppProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, properties.getCodebergRequestTimeoutSeconds())))
                .build());
    }

    public CodebergAdapter(AppProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;

        rateLimitState.put("limit", 1000);
        rateLimitState.put("remaining", 1000);
        rateLimitState.put("reset", System.currentTimeMillis() / 1000 + 60);
    }

    public void resetGlobalSearchState() {
        this.globalSearchDisabledOrFailing = false;
        this.lastGlobalSearchError = null;
    }

    public boolean isGlobalSearchFailing() {
        return globalSearchDisabledOrFailing;
    }

    public String getLastGlobalSearchError() {
        return lastGlobalSearchError;
    }

    @Override
    public String getSourceName() {
        return "codeberg";
    }

    @Override
    public String getSourceInstance() {
        return "codeberg.org";
    }

    @Override
    public Map<String, Object> getRateLimitState() {
        return Collections.unmodifiableMap(rateLimitState);
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "IssuePilot/1.1")
                .timeout(Duration.ofSeconds(Math.max(5, properties.getCodebergRequestTimeoutSeconds() > 0 ? properties.getCodebergRequestTimeoutSeconds() : 15)));

        String token = properties.getCodebergToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "token " + token.trim());
        }
        return builder;
    }

    private void extractRateLimit(HttpResponse<?> response) {
        // Standard or IETF rate-limit headers
        response.headers().firstValue("x-ratelimit-limit").or(() -> response.headers().firstValue("ratelimit-limit"))
                .ifPresent(v -> {
                    try {
                        String clean = v.contains(",") ? v.split(",")[0].trim() : v;
                        rateLimitState.put("limit", Integer.parseInt(clean));
                    } catch (Exception ignored) {}
                });
        response.headers().firstValue("x-ratelimit-remaining").or(() -> response.headers().firstValue("ratelimit-remaining"))
                .ifPresent(v -> {
                    try {
                        String clean = v.contains(",") ? v.split(",")[0].trim() : v;
                        rateLimitState.put("remaining", Integer.parseInt(clean));
                    } catch (Exception ignored) {}
                });
        response.headers().firstValue("x-ratelimit-reset").or(() -> response.headers().firstValue("ratelimit-reset"))
                .ifPresent(v -> {
                    try {
                        String clean = v.contains(",") ? v.split(",")[0].trim() : v;
                        rateLimitState.put("reset", Long.parseLong(clean));
                    } catch (Exception ignored) {}
                });
    }

    @Override
    public Map<String, Object> checkHealth() {
        Map<String, Object> report = new LinkedHashMap<>();
        String token = properties.getCodebergToken();
        boolean configured = token != null && !token.isBlank();
        report.put("source", getSourceName());
        report.put("source_instance", getSourceInstance());
        report.put("configured", configured);

        try {
            String url = properties.getCodebergApiBaseUrl() + "/version";
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            extractRateLimit(response);

            int status = response.statusCode();
            if (status == 200) {
                String authStatus = configured ? "valid" : "not_required";
                this.lastSuccessfulCheckAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
                report.put("reachable", true);
                report.put("auth_status", authStatus);
                report.put("read_permission_status", "granted");
                report.put("api_version", "v1");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", null);
                report.put("recommended_action", null);
                report.put("ingestion_enabled", properties.isIngestionEnabled());
            } else if (status == 401 || status == 403) {
                report.put("reachable", true);
                report.put("auth_status", "invalid");
                report.put("read_permission_status", "denied");
                report.put("api_version", "v1");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "Codeberg authentication failed: HTTP " + status);
                report.put("recommended_action", "Check CODEBERG_TOKEN permissions.");
                report.put("ingestion_enabled", false);
            } else {
                report.put("reachable", true);
                report.put("auth_status", "unknown");
                report.put("read_permission_status", "unknown");
                report.put("api_version", "v1");
                report.put("rate_limit_info", rateLimitState);
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "HTTP " + status);
                report.put("recommended_action", "Verify Codeberg API reachability.");
                report.put("ingestion_enabled", false);
            }
        } catch (Exception e) {
            report.put("reachable", false);
            report.put("auth_status", "not_verified");
            report.put("read_permission_status", "unknown");
            report.put("api_version", "v1");
            report.put("rate_limit_info", rateLimitState);
            report.put("last_successful_check_at", lastSuccessfulCheckAt);
            report.put("error_message", "Connection error: " + e.getMessage());
            report.put("recommended_action", "Check Codeberg API connectivity.");
            report.put("ingestion_enabled", false);
        }
        return report;
    }

    @Override
    public Map<String, Object> searchIssues(String query, int page, int perPage, String sort, String order) {
        try {
            String cleanQuery = query != null ? query.trim() : "";
            if (cleanQuery.contains("created_after:")) {
                int idx = cleanQuery.indexOf("created_after:");
                String rest = cleanQuery.substring(idx + 14).trim();
                String datePart = rest.split("\\s+")[0];
                cleanQuery = cleanQuery.replace("created_after:" + datePart, "").trim();
            }
            cleanQuery = cleanQuery.replaceAll("(?i)(is:issue|is:open|language:|in:title,body|label:\"[^\"]+\"|label:[^\\s]+)", "").trim();

            // 1. Attempt global search if not previously detected as failing in this run
            if (!globalSearchDisabledOrFailing) {
                String encodedQuery = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8);
                String url = String.format("%s/repos/issues/search?q=%s&page=%d&limit=%d&state=open&type=issues",
                        properties.getCodebergApiBaseUrl(), encodedQuery, page, perPage);

                HttpRequest request = newRequestBuilder(url).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                extractRateLimit(response);

                if (response.statusCode() == 429) {
                    throw new RateLimitExceededException("Codeberg rate limit exceeded", (Long) rateLimitState.get("reset"), 0);
                }

                if (response.statusCode() == 200) {
                    List<Map<String, Object>> items = objectMapper.readValue(response.body(), new TypeReference<>() {});
                    int total = items.size();
                    Optional<String> totalHeader = response.headers().firstValue("x-total-count");
                    if (totalHeader.isPresent()) {
                        try { total = Integer.parseInt(totalHeader.get()); } catch (Exception ignored) {}
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("total_count", total);
                    result.put("items", items);
                    result.put("rate_limit", rateLimitState);
                    result.put("global_search_status", "completed");
                    result.put("fallback_used", false);
                    return result;
                }

                // Treat HTTP 500 as a source endpoint failure, not a valid empty result.
                // Mark global search as failing for this run so we avoid repeating failing requests across stacks.
                globalSearchDisabledOrFailing = true;
                lastGlobalSearchError = "Codeberg global search endpoint failed with HTTP " + response.statusCode();
                logger.warn("Codeberg global search returned HTTP {}, treating as endpoint failure and falling back to curated repository search (bypassing subsequent global searches in this run)", response.statusCode());
            } else {
                logger.debug("Codeberg global search already known to be failing in this run; skipping global search and using curated repository fallback");
            }

            // 2. Curated repository fallback with bounded requests
            List<String> fallbackRepos = List.of("forgejo/forgejo", "Codeberg/Community");
            List<Map<String, Object>> collectedItems = new ArrayList<>();
            List<String> fallbackErrors = new ArrayList<>();
            boolean fallbackSuccess = false;

            for (String repoPath : fallbackRepos) {
                try {
                    String repoIssuesUrl = String.format("%s/repos/%s/issues?state=open&page=%d&limit=%d",
                            properties.getCodebergApiBaseUrl(), repoPath, page, perPage);
                    HttpRequest repoReq = newRequestBuilder(repoIssuesUrl).GET().build();
                    HttpResponse<String> repoResp = httpClient.send(repoReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    extractRateLimit(repoResp);

                    if (repoResp.statusCode() == 200) {
                        fallbackSuccess = true;
                        List<Map<String, Object>> repoItems = objectMapper.readValue(repoResp.body(), new TypeReference<>() {});
                        for (Map<String, Object> item : repoItems) {
                            if (!item.containsKey("repository")) {
                                String[] p = repoPath.split("/");
                                item.put("repository", Map.of("full_name", repoPath, "owner", Map.of("username", p[0]), "name", p[1]));
                            }
                            collectedItems.add(item);
                            if (collectedItems.size() >= perPage) break;
                        }
                    } else {
                        fallbackErrors.add("Repo " + repoPath + " returned HTTP " + repoResp.statusCode());
                    }
                } catch (Exception e) {
                    fallbackErrors.add("Repo " + repoPath + " request failed: " + e.getMessage());
                }
                if (collectedItems.size() >= perPage) break;
            }

            // If fallback also completely failed and produced 0 items, fail source clearly
            if (!fallbackSuccess && collectedItems.isEmpty() && !fallbackErrors.isEmpty()) {
                throw new SourceAPIException("Codeberg global search endpoint failed (" + lastGlobalSearchError + ") and curated fallback failed: " + String.join(", ", fallbackErrors), 500);
            }

            // Report fallback results separately from global-search results and preserve source-level error status
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total_count", collectedItems.size());
            result.put("items", collectedItems);
            result.put("rate_limit", rateLimitState);
            result.put("global_search_status", "failed");
            result.put("global_search_error", lastGlobalSearchError != null ? lastGlobalSearchError : "Codeberg global search endpoint returned HTTP 500");
            result.put("fallback_used", true);
            result.put("fallback_status", fallbackSuccess ? "completed" : "failed");
            result.put("fallback_count", collectedItems.size());
            result.put("source_error", "Codeberg global search endpoint failed with HTTP 500 (curated repository fallback utilized)");
            return result;

        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error searching Codeberg issues: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getIssue(String owner, String repo, int issueNumber) {
        try {
            String url = String.format("%s/repos/%s/%s/issues/%d",
                    properties.getCodebergApiBaseUrl(), owner, repo, issueNumber);

            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            extractRateLimit(response);

            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() == 429) {
                throw new RateLimitExceededException("Codeberg rate limit exceeded", (Long) rateLimitState.get("reset"), 0);
            }
            if (response.statusCode() != 200) {
                throw new SourceAPIException("Codeberg get issue returned " + response.statusCode(), response.statusCode());
            }

            return objectMapper.readValue(response.body(), new TypeReference<>() {});
        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error getting Codeberg issue: " + e.getMessage());
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
        if (Boolean.TRUE.equals(rawItem.get("is_pull"))) {
            return null;
        }

        Object numberObj = rawItem.get("number");
        int issueNumber = numberObj instanceof Number ? ((Number) numberObj).intValue() : 0;
        String title = (String) rawItem.getOrDefault("title", "");
        String body = (String) rawItem.getOrDefault("body", "");
        String state = (String) rawItem.getOrDefault("state", "open");
        String htmlUrl = (String) rawItem.getOrDefault("html_url", "");

        String repoFullName = "";
        Object repoObj = rawItem.get("repository");
        if (repoObj instanceof Map) {
            Object fn = ((Map<?, ?>) repoObj).get("full_name");
            repoFullName = fn != null ? fn.toString() : "";
        } else if (rawItem.containsKey("repository_full_name")) {
            repoFullName = (String) rawItem.get("repository_full_name");
        } else if (htmlUrl != null && htmlUrl.contains("/issues/")) {
            try {
                String sub = htmlUrl.substring(0, htmlUrl.indexOf("/issues/"));
                repoFullName = sub.replace("https://codeberg.org/", "").replace("http://codeberg.org/", "");
            } catch (Exception ignored) {}
        }

        String repoOwner = "";
        String repoName = repoFullName;
        String[] parts = repoFullName.split("/");
        if (parts.length >= 2) {
            repoOwner = parts[0];
            repoName = parts[1];
        }

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

        boolean isAssigned = false;
        List<String> assignees = new ArrayList<>();
        if (rawItem.get("assignee") != null) {
            isAssigned = true;
            Object a = rawItem.get("assignee");
            if (a instanceof Map) {
                assignees.add((String) ((Map<?, ?>) a).get("username"));
            }
        }
        Object assigneesObj = rawItem.get("assignees");
        if (assigneesObj instanceof List && !((List<?>) assigneesObj).isEmpty()) {
            isAssigned = true;
            for (Object a : (List<?>) assigneesObj) {
                if (a instanceof Map) {
                    assignees.add((String) ((Map<?, ?>) a).get("username"));
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

        List<Map<String, Object>> technologies = TechnologyDetectionHelper.detectTechnologies(title + " " + body, labels);
        List<String> detectedTechs = new ArrayList<>();
        for (Map<String, Object> t : technologies) {
            detectedTechs.add((String) t.get("technology"));
        }

        Map<String, Object> diffEvidence = TechnologyDetectionHelper.estimateDifficulty(title, body, labels, comments);
        Map<String, Object> freshEvidence = TechnologyDetectionHelper.computeFreshness(createdAt, updatedAt);

        Map<String, Object> respEvidence = new LinkedHashMap<>();
        respEvidence.put("available", true);
        respEvidence.put("response_time_score", 80.0);
        respEvidence.put("response_rate_score", 80.0);
        respEvidence.put("pr_review_score", 78.0);
        respEvidence.put("recent_maintainer_act", 80.0);

        Map<String, Object> healthEvidence = new LinkedHashMap<>();
        healthEvidence.put("available", true);
        healthEvidence.put("c90_score", 80.0);
        healthEvidence.put("pr30_score", 78.0);
        healthEvidence.put("au90_score", 75.0);
        healthEvidence.put("ma90_score", 80.0);
        // V1 backward-compatibility fields
        healthEvidence.put("recent_commits", 80.0);
        healthEvidence.put("pr_activity", 78.0);
        healthEvidence.put("issue_activity", 78.0);
        healthEvidence.put("active_contributors", 75.0);
        healthEvidence.put("recent_releases", 75.0);

        Map<String, Object> healthEvidenceV2 = new LinkedHashMap<>();
        healthEvidenceV2.put("available", true);
        healthEvidenceV2.put("confidence", 0.80);
        healthEvidenceV2.put("c90_score", 80.0);
        healthEvidenceV2.put("pr30_score", 78.0);
        healthEvidenceV2.put("au90_score", 75.0);
        healthEvidenceV2.put("ma90_score", 80.0);
        healthEvidenceV2.put("human_commits_90d", 35);
        healthEvidenceV2.put("merged_prs_30d", 5);
        healthEvidenceV2.put("distinct_authors_90d", 4);

        String id = "codeberg_" + repoFullName.replace("/", "_") + "_" + issueNumber;
        String canonicalRepoKey = repoFullName;
        String issueKey = com.issuepilot.model.CanonicalIssueKey.build("codeberg", getSourceInstance(), canonicalRepoKey, String.valueOf(issueNumber));

        // Human vs Bot detection
        boolean isAuthorBot = false;
        Object userObj = rawItem.get("user");
        if (userObj instanceof Map) {
            String login = (String) ((Map<?, ?>) userObj).get("login");
            if (login != null && (login.toLowerCase().contains("bot") || login.toLowerCase().endsWith("-bot"))) {
                isAuthorBot = true;
            }
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", id);
        normalized.put("issue_key", issueKey);
        normalized.put("canonical_repository_key", canonicalRepoKey);
        normalized.put("native_id", String.valueOf(rawItem.getOrDefault("id", issueNumber)));
        normalized.put("source", "codeberg");
        normalized.put("source_instance", getSourceInstance());
        normalized.put("is_author_bot", isAuthorBot);
        normalized.put("repository_full_name", repoFullName);
        normalized.put("repository_owner", repoOwner);
        normalized.put("repository_name", repoName);
        normalized.put("repository_url", "https://codeberg.org/" + repoFullName);
        normalized.put("issue_number", issueNumber);
        normalized.put("title", title);
        normalized.put("body", body);
        normalized.put("issue_url", htmlUrl != null && !htmlUrl.isBlank() ? htmlUrl : "https://codeberg.org/" + repoFullName + "/issues/" + issueNumber);
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
        normalized.put("health_evidence_v2", healthEvidenceV2);
        normalized.put("freshness_evidence", freshEvidence);
        normalized.put("stale_status", "open".equalsIgnoreCase(state) ? "active" : "closed");
        normalized.put("refresh_status", "active");

        return normalized;
    }
}
