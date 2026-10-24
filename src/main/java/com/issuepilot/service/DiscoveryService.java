package com.issuepilot.service;

import com.issuepilot.adapter.AdapterRegistry;
import com.issuepilot.adapter.BaseIssueSourceAdapter;
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
public class DiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryService.class);

    public record Topic(String id, String label, String query) {}

    public static final List<Topic> ROTATING_TOPICS = List.of(
            new Topic("python", "Python", "language:Python"),
            new Topic("typescript", "TypeScript", "language:TypeScript"),
            new Topic("javascript", "JavaScript", "language:JavaScript"),
            new Topic("react", "React", "react in:title,body"),
            new Topic("cpp", "C++", "language:C++"),
            new Topic("go", "Go", "language:Go"),
            new Topic("rust", "Rust", "language:Rust"),
            new Topic("java", "Java", "language:Java"),
            new Topic("good_first_issue", "Good First Issue", "label:\"good first issue\""),
            new Topic("help_wanted", "Help Wanted", "label:\"help wanted\""),
            new Topic("bug", "Bugs", "label:bug"),
            new Topic("documentation", "Docs", "label:documentation"),
            new Topic("performance", "Performance", "label:performance"),
            new Topic("security", "Security", "label:security")
    );

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final IssueRepository issueRepository;
    private final JobRepository jobRepository;
    private final AdapterRegistry adapterRegistry;

    public DiscoveryService(
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

    public Map<String, Object> runDiscovery(
            Integer maxIssuesToDiscover,
            Integer maxPagesPerQuery,
            Integer perPage,
            boolean resume,
            boolean force,
            String sourceFilter
    ) {
        String jobType = "discovery";
        String jobId = null;

        if (mongoConfig.isConnected()) {
            jobId = jobRepository.acquireJobLock(jobType);
            if (jobId == null && !force) {
                Map<String, Object> locked = new LinkedHashMap<>();
                locked.put("status", "locked");
                locked.put("message", "Another discovery job is currently in progress. Please wait for it to complete or release the lock.");
                locked.put("job_id", null);
                return locked;
            } else if (jobId == null && force) {
                jobId = "discovery_forced_" + System.currentTimeMillis();
            }
        } else {
            jobId = "discovery_offline_" + System.currentTimeMillis();
        }

        long t0 = System.currentTimeMillis();
        int targetPoolSize = properties.getCandidatePoolTarget();
        int maxPerRepo = properties.getMaxIssuesPerRepo();
        int pagesPerQuery = (maxPagesPerQuery != null && maxPagesPerQuery > 0) ? maxPagesPerQuery : properties.getMaxSearchPagesPerRun();
        int itemsPerPage = (perPage != null && perPage > 0) ? perPage : properties.getPerPageCount();

        long currentActivePool = 0;
        if (mongoConfig.isConnected()) {
            try {
                currentActivePool = issueRepository.countIssues();
            } catch (Exception ignored) {}
        }

        if (currentActivePool >= targetPoolSize) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "completed");
            summary.put("job_id", jobId);
            summary.put("job_type", "discovery");
            summary.put("discovered_count", 0);
            summary.put("inserted_count", 0);
            summary.put("updated_count", 0);
            summary.put("skipped_count", 0);
            summary.put("active_pool_size", currentActivePool);
            summary.put("target_pool_size", targetPoolSize);
            summary.put("note", "Candidate pool target reached; discovery halted.");
            if (mongoConfig.isConnected()) {
                jobRepository.releaseJobLock(jobId, "completed", summary);
            }
            return summary;
        }

        List<String> targetSources = new ArrayList<>();
        if (sourceFilter == null || sourceFilter.isBlank() || "all".equalsIgnoreCase(sourceFilter)) {
            targetSources.addAll(adapterRegistry.getAllAdapters().keySet());
        } else {
            targetSources.add(sourceFilter.toLowerCase().trim());
        }

        int totalDiscovered = 0;
        int totalInserted = 0;
        int totalUpdated = 0;
        int totalSkipped = 0;
        int totalSkippedDiversity = 0;
        List<String> errors = new ArrayList<>();
        Map<String, Map<String, Object>> perSourceSummary = new LinkedHashMap<>();

        for (String src : targetSources) {
            BaseIssueSourceAdapter adapter;
            try {
                adapter = adapterRegistry.getAdapter(src);
            } catch (Exception e) {
                errors.add("Source " + src + " not found: " + e.getMessage());
                continue;
            }

            int srcDiscovered = 0;
            int srcInserted = 0;
            int srcUpdated = 0;
            int srcSkipped = 0;

            for (Topic topic : ROTATING_TOPICS) {
                if (maxIssuesToDiscover != null && totalDiscovered >= maxIssuesToDiscover) {
                    break;
                }

                for (int page = 1; page <= pagesPerQuery; page++) {
                    if (maxIssuesToDiscover != null && totalDiscovered >= maxIssuesToDiscover) {
                        break;
                    }

                    try {
                        Map<String, Object> searchResult = adapter.searchIssues(topic.query(), page, itemsPerPage, "updated", "desc");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items = (List<Map<String, Object>>) searchResult.getOrDefault("items", Collections.emptyList());

                        if (items.isEmpty()) {
                            break;
                        }

                        for (Map<String, Object> item : items) {
                            if (maxIssuesToDiscover != null && totalDiscovered >= maxIssuesToDiscover) {
                                break;
                            }

                            srcDiscovered++;
                            totalDiscovered++;

                            Map<String, Object> queryInfo = Map.of("topic", topic.id(), "technology", topic.label());
                            Map<String, Object> normalized = adapter.normalizeIssue(item, queryInfo);

                            if (normalized == null) {
                                srcSkipped++;
                                totalSkipped++;
                                continue;
                            }

                            String repoFullName = (String) normalized.get("repository_full_name");
                            if (repoFullName != null && mongoConfig.isConnected()) {
                                long repoCount = issueRepository.countByRepository(repoFullName);
                                if (repoCount >= maxPerRepo) {
                                    totalSkippedDiversity++;
                                    srcSkipped++;
                                    totalSkipped++;
                                    continue;
                                }
                            }

                            if (mongoConfig.isConnected()) {
                                try {
                                    Map<String, Object> upserted = issueRepository.upsertIssue(normalized, true);
                                    if (upserted != null) {
                                        srcInserted++;
                                        totalInserted++;
                                    } else {
                                        srcUpdated++;
                                        totalUpdated++;
                                    }
                                } catch (Exception e) {
                                    srcSkipped++;
                                    totalSkipped++;
                                }
                            } else {
                                srcInserted++;
                                totalInserted++;
                            }
                        }

                    } catch (Exception e) {
                        errors.add(src + " query '" + topic.id() + "' page " + page + " failed: " + e.getMessage());
                        break;
                    }
                }
            }

            Map<String, Object> srcStats = new LinkedHashMap<>();
            srcStats.put("discovered", srcDiscovered);
            srcStats.put("inserted", srcInserted);
            srcStats.put("updated", srcUpdated);
            srcStats.put("skipped", srcSkipped);
            srcStats.put("status", srcDiscovered > 0 ? "completed" : "idle");
            perSourceSummary.put(src, srcStats);
        }

        long elapsedSec = (System.currentTimeMillis() - t0) / 1000;
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("job_id", jobId);
        summary.put("job_type", "discovery");
        summary.put("status", errors.isEmpty() ? "completed" : (totalDiscovered > 0 ? "completed_with_errors" : "failed"));
        summary.put("discovered_count", totalDiscovered);
        summary.put("inserted_count", totalInserted);
        summary.put("updated_count", totalUpdated);
        summary.put("skipped_count", totalSkipped);
        summary.put("skipped_diversity_count", totalSkippedDiversity);
        summary.put("duration_seconds", elapsedSec);
        summary.put("per_source_summary", perSourceSummary);
        summary.put("errors", errors);
        summary.put("finished_at", nowIso);

        if (mongoConfig.isConnected()) {
            jobRepository.releaseJobLock(jobId, (String) summary.get("status"), summary);
        }

        return summary;
    }
}
