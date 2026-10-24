package com.issuepilot.service;

import com.issuepilot.adapter.AdapterRegistry;
import com.issuepilot.adapter.BaseIssueSourceAdapter;
import com.issuepilot.adapter.RateLimitExceededException;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class RefreshService {

    private static final Logger logger = LoggerFactory.getLogger(RefreshService.class);

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final IssueRepository issueRepository;
    private final JobRepository jobRepository;
    private final AdapterRegistry adapterRegistry;

    public RefreshService(
            MongoConfig mongoConfig,
            AppProperties properties,
            IssueRepository issueRepository,
            JobRepository jobRepository,
            AdapterRegistry adapterRegistry
    ) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.issueRepository = issueRepository;
        this.jobRepository = jobRepository;
        this.adapterRegistry = adapterRegistry;
    }

    public Map<String, Object> runRefresh(int batchSize, Integer maxAgeDays, boolean force) {
        String jobType = "refresh";
        String jobId = null;

        if (mongoConfig.isConnected()) {
            jobId = jobRepository.acquireJobLock(jobType);
            if (jobId == null && !force) {
                Map<String, Object> locked = new LinkedHashMap<>();
                locked.put("status", "locked");
                locked.put("message", "Another refresh job is currently running.");
                locked.put("job_id", null);
                return locked;
            } else if (jobId == null && force) {
                jobId = "refresh_forced_" + System.currentTimeMillis();
            }
        } else {
            jobId = "refresh_offline_" + System.currentTimeMillis();
        }

        long t0 = System.currentTimeMillis();
        int refreshAge = (maxAgeDays != null && maxAgeDays > 0) ? maxAgeDays : properties.getRefreshCadenceDays();

        if (!mongoConfig.isConnected()) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("job_id", jobId);
            failed.put("status", "failed");
            failed.put("message", "MongoDB Atlas connection unavailable.");
            return failed;
        }

        List<Map<String, Object>> candidates = issueRepository.getCandidatesForRefresh(batchSize, refreshAge);
        if (candidates.isEmpty()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "completed");
            summary.put("job_id", jobId);
            summary.put("job_type", "refresh");
            summary.put("checked_count", 0);
            summary.put("verified_count", 0);
            summary.put("issues_refreshed", 0);
            summary.put("issues_marked_closed", 0);
            summary.put("issues_marked_unavailable", 0);
            summary.put("message", "No candidates currently require refresh.");
            jobRepository.releaseJobLock(jobId, "completed", summary);
            return summary;
        }

        int totalRefreshed = 0;
        int totalClosed = 0;
        int totalUnavailable = 0;
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> candidate : candidates) {
            String source = (String) candidate.getOrDefault("source", "github");
            String repoOwner = (String) candidate.get("repository_owner");
            String repoName = (String) candidate.get("repository_name");
            String repoFullName = (String) candidate.getOrDefault("repository_full_name", "");
            Object issueNumObj = candidate.get("issue_number");
            int issueNumber = issueNumObj instanceof Number ? ((Number) issueNumObj).intValue() : 0;
            String issueId = (String) candidate.get("id");

            if (!"bugzilla".equalsIgnoreCase(source) && (repoOwner == null || repoName == null)) {
                if (repoFullName != null && repoFullName.contains("/")) {
                    String[] parts = repoFullName.split("/", 2);
                    repoOwner = parts[0];
                    repoName = parts[1];
                } else {
                    continue;
                }
            }

            try {
                BaseIssueSourceAdapter adapter = adapterRegistry.getAdapter(source);
                Map<String, Object> freshData;
                if ("bugzilla".equalsIgnoreCase(source)) {
                    freshData = adapter.getIssue("", "", issueNumber);
                } else {
                    freshData = adapter.getIssue(repoOwner, repoName, issueNumber);
                }

                if (freshData == null) {
                    issueRepository.markIssueUnavailable(issueId);
                    totalUnavailable++;
                    continue;
                }

                // Check state
                String state = (String) freshData.getOrDefault("state", "open");
                if ("closed".equalsIgnoreCase(state) || "resolved".equalsIgnoreCase(state)) {
                    String closedAt = (String) freshData.get("closed_at");
                    issueRepository.markIssueClosed(issueId, closedAt);
                    totalClosed++;
                } else {
                    // Normalize & update
                    Map<String, Object> normalized = adapter.normalizeIssue(freshData, null);
                    if (normalized != null) {
                        normalized.put("id", issueId);
                        normalized.put("last_seen_at", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
                        normalized.put("stale_status", "active");
                        issueRepository.upsertIssue(normalized, true);
                        totalRefreshed++;
                    }
                }
            } catch (RateLimitExceededException e) {
                errors.add("Rate limit exhausted during refresh: " + e.getMessage());
                break;
            } catch (Exception e) {
                errors.add("Error refreshing " + issueId + ": " + e.getMessage());
            }
        }

        long elapsedSec = (System.currentTimeMillis() - t0) / 1000;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("job_id", jobId);
        summary.put("job_type", "refresh");
        summary.put("status", errors.isEmpty() ? "completed" : "completed_with_errors");
        summary.put("checked_count", candidates.size());
        summary.put("verified_count", totalRefreshed);
        summary.put("issues_refreshed", totalRefreshed);
        summary.put("issues_marked_closed", totalClosed);
        summary.put("issues_marked_unavailable", totalUnavailable);
        summary.put("duration_seconds", elapsedSec);
        summary.put("errors", errors);

        jobRepository.releaseJobLock(jobId, (String) summary.get("status"), summary);
        return summary;
    }
}
