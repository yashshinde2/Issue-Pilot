package com.issuepilot.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class BugzillaAdapter implements BaseIssueSourceAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BugzillaAdapter.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private int localRequestsUsed = 0;
    private String lastSuccessfulCheckAt = null;

    public BugzillaAdapter(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getBugzillaRequestTimeoutSeconds()))
                .build();
    }

    @Override
    public String getSourceName() {
        return "bugzilla";
    }

    @Override
    public String getSourceInstance() {
        String base = properties.getBugzillaBaseUrl();
        if (base == null || base.isBlank()) {
            return "bugzilla-instance";
        }
        try {
            URI u = URI.create(base);
            return (u.getHost() != null && !u.getHost().isBlank()) ? u.getHost() : "bugzilla-instance";
        } catch (Exception e) {
            return "bugzilla-instance";
        }
    }

    @Override
    public Map<String, Object> getRateLimitState() {
        Map<String, Object> state = new LinkedHashMap<>();
        int budget = properties.getBugzillaLocalRequestBudget();
        int remaining = Math.max(0, budget - localRequestsUsed);
        state.put("limit", "unknown");
        state.put("remaining", "unknown");
        state.put("local_budget_limit", budget);
        state.put("local_budget_remaining", remaining);
        state.put("used", localRequestsUsed);
        return state;
    }

    private String resolveEndpoint(String path) {
        String base = properties.getBugzillaBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        if (base.contains("/rest")) {
            return base.replaceAll("/+$", "") + "/" + cleanPath;
        }
        return base.replaceAll("/+$", "") + "/rest/" + cleanPath;
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "IssuePilot/1.1")
                .timeout(Duration.ofSeconds(properties.getBugzillaRequestTimeoutSeconds()));

        String apiKey = properties.getBugzillaApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-BUGZILLA-API-KEY", apiKey.trim());
        }
        return builder;
    }

    private void checkLocalBudget() {
        int budget = properties.getBugzillaLocalRequestBudget();
        if (localRequestsUsed >= budget) {
            throw new RateLimitExceededException(
                    "Bugzilla local request budget exhausted (" + localRequestsUsed + "/" + budget + ")",
                    System.currentTimeMillis() / 1000 + 3600,
                    0
            );
        }
    }

    @Override
    public Map<String, Object> checkHealth() {
        Map<String, Object> report = new LinkedHashMap<>();
        String base = properties.getBugzillaBaseUrl();
        boolean configured = base != null && !base.isBlank();
        report.put("source", getSourceName());
        report.put("source_instance", getSourceInstance());
        report.put("configured", configured);

        if (!configured) {
            report.put("reachable", false);
            report.put("auth_status", "not_required");
            report.put("read_permission_status", "unknown");
            report.put("api_version", "unknown");
            report.put("rate_limit_info", getRateLimitState());
            report.put("last_successful_check_at", lastSuccessfulCheckAt);
            report.put("error_message", "Bugzilla base URL not configured.");
            report.put("recommended_action", "Set BUGZILLA_BASE_URL environment variable.");
            report.put("ingestion_enabled", false);
            return report;
        }

        try {
            checkLocalBudget();
            localRequestsUsed++;
            String url = resolveEndpoint("version");
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            int status = response.statusCode();
            if (status == 200) {
                this.lastSuccessfulCheckAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
                String authStatus = (properties.getBugzillaApiKey() != null && !properties.getBugzillaApiKey().isBlank())
                        ? "valid" : "not_required";

                report.put("reachable", true);
                report.put("auth_status", authStatus);
                report.put("read_permission_status", "granted");
                report.put("api_version", "rest");
                report.put("rate_limit_info", getRateLimitState());
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", null);
                report.put("recommended_action", null);
                report.put("ingestion_enabled", properties.isIngestionEnabled());
            } else if (status == 401 || status == 403) {
                report.put("reachable", true);
                report.put("auth_status", "invalid");
                report.put("read_permission_status", "denied");
                report.put("api_version", "unknown");
                report.put("rate_limit_info", getRateLimitState());
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "Bugzilla authentication failed: HTTP " + status);
                report.put("recommended_action", "Check BUGZILLA_API_KEY environment variable.");
                report.put("ingestion_enabled", false);
            } else {
                report.put("reachable", true);
                report.put("auth_status", "unknown");
                report.put("read_permission_status", "unknown");
                report.put("api_version", "unknown");
                report.put("rate_limit_info", getRateLimitState());
                report.put("last_successful_check_at", lastSuccessfulCheckAt);
                report.put("error_message", "HTTP " + status);
                report.put("recommended_action", "Verify Bugzilla REST API endpoint.");
                report.put("ingestion_enabled", false);
            }
        } catch (Exception e) {
            report.put("reachable", false);
            report.put("auth_status", "not_verified");
            report.put("read_permission_status", "unknown");
            report.put("api_version", "unknown");
            report.put("rate_limit_info", getRateLimitState());
            report.put("last_successful_check_at", lastSuccessfulCheckAt);
            report.put("error_message", "Connection error: " + e.getMessage());
            report.put("recommended_action", "Verify Bugzilla host reachability.");
            report.put("ingestion_enabled", false);
        }
        return report;
    }

    @Override
    public Map<String, Object> searchIssues(String query, int page, int perPage, String sort, String order) {
        checkLocalBudget();
        try {
            int offset = Math.max(0, (page - 1) * perPage);
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = resolveEndpoint(String.format("bug?quicksearch=%s&limit=%d&offset=%d", encodedQuery, perPage, offset));

            localRequestsUsed++;
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 429) {
                throw new RateLimitExceededException("Bugzilla rate limit exceeded", null, 0);
            }
            if (response.statusCode() != 200) {
                throw new SourceAPIException("Bugzilla search returned " + response.statusCode(), response.statusCode());
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<?> bugs = (List<?>) body.getOrDefault("bugs", Collections.emptyList());

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("total_count", bugs.size());
            res.put("items", bugs);
            res.put("rate_limit", getRateLimitState());
            return res;
        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error searching Bugzilla: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getIssue(String owner, String repo, int issueNumber) {
        checkLocalBudget();
        try {
            String url = resolveEndpoint("bug/" + issueNumber);
            localRequestsUsed++;
            HttpRequest request = newRequestBuilder(url).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() == 429) {
                throw new RateLimitExceededException("Bugzilla rate limit exceeded", null, 0);
            }
            if (response.statusCode() != 200) {
                throw new SourceAPIException("Bugzilla get issue returned " + response.statusCode(), response.statusCode());
            }

            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            List<?> bugs = (List<?>) body.get("bugs");
            if (bugs != null && !bugs.isEmpty() && bugs.get(0) instanceof Map) {
                return (Map<String, Object>) bugs.get(0);
            }
            return body;
        } catch (SourceAPIException e) {
            throw e;
        } catch (Exception e) {
            throw new TransientAPIException("Error getting Bugzilla issue: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getRateLimit() {
        return getRateLimitState();
    }

    @Override
    public Map<String, Object> normalizeIssue(Map<String, Object> rawItem, Map<String, Object> queryInfo) {
        if (rawItem == null) return null;

        Object idObj = rawItem.get("id");
        int bugId = idObj instanceof Number ? ((Number) idObj).intValue() : 0;
        if (bugId == 0) return null;

        String summary = (String) rawItem.getOrDefault("summary", "");
        String bzStatus = ((String) rawItem.getOrDefault("status", "NEW")).toUpperCase();
        String resolution = ((String) rawItem.getOrDefault("resolution", "")).toUpperCase();

        Set<String> openStatuses = Set.of("NEW", "UNCONFIRMED", "ASSIGNED", "CONFIRMED", "IN_PROGRESS", "REOPENED");
        String state = (openStatuses.contains(bzStatus) && resolution.isBlank()) ? "open" : "closed";

        String product = (String) rawItem.getOrDefault("product", "Bugzilla");
        String component = (String) rawItem.getOrDefault("component", "General");

        String baseWeb = properties.getBugzillaBaseUrl() != null ? properties.getBugzillaBaseUrl().replace("/rest", "").replaceAll("/+$", "") : "https://bugzilla.mozilla.org";
        String canonicalUrl = baseWeb + "/show_bug.cgi?id=" + bugId;

        // Assigned
        String assignedTo = (String) rawItem.get("assigned_to");
        boolean isAssigned = false;
        List<String> assignees = new ArrayList<>();
        if (assignedTo != null && !assignedTo.toLowerCase().contains("nobody@")) {
            isAssigned = true;
            assignees.add(assignedTo);
        }

        // Keywords and labels
        List<String> labels = new ArrayList<>();
        Object kwObj = rawItem.get("keywords");
        if (kwObj instanceof List) {
            for (Object k : (List<?>) kwObj) {
                if (k != null) labels.add(k.toString());
            }
        }
        if (product != null && !product.isBlank()) labels.add(product);
        if (component != null && !component.isBlank()) labels.add(component);

        int comments = 0;
        Object commentsObj = rawItem.get("comments_count");
        if (commentsObj == null) commentsObj = rawItem.get("comment_count");
        if (commentsObj instanceof Number) comments = ((Number) commentsObj).intValue();

        String createdAt = (String) rawItem.getOrDefault("creation_time", rawItem.get("created_at"));
        String updatedAt = (String) rawItem.getOrDefault("last_change_time", rawItem.get("updated_at"));
        String closedAt = "closed".equalsIgnoreCase(state) ? updatedAt : null;

        List<Map<String, Object>> technologies = TechnologyDetectionHelper.detectTechnologies(summary, labels);
        List<String> detectedTechs = new ArrayList<>();
        for (Map<String, Object> t : technologies) {
            detectedTechs.add((String) t.get("technology"));
        }

        Map<String, Object> diffEvidence = TechnologyDetectionHelper.estimateDifficulty(summary, "", labels, comments);
        Map<String, Object> freshEvidence = TechnologyDetectionHelper.computeFreshness(createdAt, updatedAt);

        // Responsiveness & Health: Unavailable on Bugzilla -> triggers C++ engine weight renormalization
        Map<String, Object> respEvidence = new LinkedHashMap<>();
        respEvidence.put("available", false);
        respEvidence.put("response_time_score", 80.0);
        respEvidence.put("response_rate_score", 80.0);
        respEvidence.put("pr_review_score", 80.0);
        respEvidence.put("recent_maintainer_act", 80.0);

        Map<String, Object> healthEvidence = new LinkedHashMap<>();
        healthEvidence.put("available", false);
        healthEvidence.put("recent_commits", 80.0);
        healthEvidence.put("pr_activity", 80.0);
        healthEvidence.put("issue_activity", 80.0);
        healthEvidence.put("active_contributors", 80.0);
        healthEvidence.put("recent_releases", 80.0);

        String instanceClean = getSourceInstance().replace("/", "_").replace(":", "_").replace(".", "_");
        String id = "bz_" + instanceClean + "_" + bugId;

        // Note: Do NOT fabricate repository metadata for Bugzilla records
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", id);
        normalized.put("native_id", String.valueOf(bugId));
        normalized.put("source", "bugzilla");
        normalized.put("source_instance", getSourceInstance());
        normalized.put("repository_full_name", null); // NO repo fabrication
        normalized.put("repository_owner", null);
        normalized.put("repository_name", null);
        normalized.put("repository_url", null);
        normalized.put("issue_number", bugId);
        normalized.put("title", summary);
        normalized.put("body", "");
        normalized.put("issue_url", canonicalUrl);
        normalized.put("canonical_url", canonicalUrl);
        normalized.put("product", product);
        normalized.put("component", component);
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
