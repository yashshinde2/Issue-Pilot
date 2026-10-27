package com.issuepilot.service;

import com.issuepilot.adapter.AdapterRegistry;
import com.issuepilot.adapter.BaseIssueSourceAdapter;
import com.issuepilot.adapter.TechnologyDetectionHelper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.JobRepository;
import com.issuepilot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class DiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryService.class);

    public static final List<String> BASELINE_STACKS = List.of(
            "Python",
            "TypeScript",
            "JavaScript",
            "React",
            "C++",
            "Go",
            "Rust",
            "Java"
    );

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final IssueRepository issueRepository;
    private final JobRepository jobRepository;
    private final AdapterRegistry adapterRegistry;
    private final UserRepository userRepository;

    public DiscoveryService(
            MongoConfig mongoConfig,
            AppProperties properties,
            IssueRepository issueRepository,
            JobRepository jobRepository,
            AdapterRegistry adapterRegistry,
            UserRepository userRepository
    ) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.issueRepository = issueRepository;
        this.jobRepository = jobRepository;
        this.adapterRegistry = adapterRegistry;
        this.userRepository = userRepository;
    }

    public Map<String, Object> runDiscovery(
            Integer maxIssuesToDiscover,
            Integer maxPagesPerQuery,
            Integer perPage,
            boolean resume,
            boolean force,
            String sourceFilter
    ) {
        return runDiscovery(maxIssuesToDiscover, maxPagesPerQuery, perPage, resume, force, sourceFilter, 30);
    }

    public Map<String, Object> runDiscovery(
            Integer maxIssuesToDiscover,
            Integer maxPagesPerQuery,
            Integer perPage,
            boolean resume,
            boolean force,
            String sourceFilter,
            Integer creationAgeDays
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

        int ageDays = (creationAgeDays != null && (creationAgeDays == 7 || creationAgeDays == 30)) ? creationAgeDays : 30;
        Instant cutoffInstant = Instant.now().minus(Duration.ofDays(ageDays));
        String cutoffIso = DateTimeFormatter.ISO_INSTANT.format(cutoffInstant);
        String cutoffDate = DateTimeFormatter.ISO_LOCAL_DATE.format(cutoffInstant.atZone(ZoneOffset.UTC));

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
            summary.put("creation_age_days", ageDays);
            summary.put("note", "Candidate pool target reached; discovery halted.");
            if (mongoConfig.isConnected()) {
                jobRepository.releaseJobLock(jobId, "completed", summary);
            }
            return summary;
        }

        // Deduplicate Baseline and Active-User Stack preferences
        Set<String> targetStacks = new LinkedHashSet<>(BASELINE_STACKS);
        if (mongoConfig.isConnected()) {
            try {
                Set<String> activeUserTechs = userRepository.getActiveTechnologyPreferences(Instant.now().minus(Duration.ofDays(30)), 100);
                if (activeUserTechs != null) {
                    targetStacks.addAll(activeUserTechs);
                }
            } catch (Exception e) {
                logger.warn("Could not retrieve active-user technology preferences: {}", e.getMessage());
            }
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
        int totalFilteredState = 0;
        int totalFilteredAge = 0;
        int totalFilteredTech = 0;
        int totalFilteredIdentity = 0;
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

            if (!adapter.isConfigured()) {
                Map<String, Object> disabledStats = new LinkedHashMap<>();
                disabledStats.put("discovered", 0);
                disabledStats.put("inserted", 0);
                disabledStats.put("updated", 0);
                disabledStats.put("skipped", 0);
                disabledStats.put("filtered_state", 0);
                disabledStats.put("filtered_age", 0);
                disabledStats.put("filtered_tech", 0);
                disabledStats.put("filtered_identity", 0);
                disabledStats.put("errors", Collections.emptyList());
                disabledStats.put("rate_limit", adapter.getRateLimitState());
                disabledStats.put("status", "disabled");
                disabledStats.put("reason", adapter.getConfigurationError() != null ? adapter.getConfigurationError() : "Source disabled/not configured");
                perSourceSummary.put(src, disabledStats);
                logger.info("Skipping unconfigured source '{}': {}", src, disabledStats.get("reason"));
                continue;
            }

            int srcDiscovered = 0;
            int srcInserted = 0;
            int srcUpdated = 0;
            int srcSkipped = 0;
            int srcFilteredState = 0;
            int srcFilteredAge = 0;
            int srcFilteredTech = 0;
            int srcFilteredIdentity = 0;
            List<String> srcErrors = new ArrayList<>();
            boolean srcFallbackUsed = false;
            String fallbackStatus = null;
            String globalSearchStatus = null;
            String globalSearchError = null;

            for (String stack : targetStacks) {
                if (maxIssuesToDiscover != null && totalDiscovered >= maxIssuesToDiscover) {
                    break;
                }

                String query = buildPlatformQuery(src, stack, cutoffDate, cutoffIso);

                for (int page = 1; page <= pagesPerQuery; page++) {
                    if (maxIssuesToDiscover != null && totalDiscovered >= maxIssuesToDiscover) {
                        break;
                    }

                    try {
                        Map<String, Object> searchResult = adapter.searchIssues(query, page, itemsPerPage, "updated", "desc");
                        if (searchResult.containsKey("source_error")) {
                            String se = (String) searchResult.get("source_error");
                            if (se != null && !srcErrors.contains(se)) {
                                srcErrors.add(se);
                            }
                        }
                        if (Boolean.TRUE.equals(searchResult.get("fallback_used"))) {
                            srcFallbackUsed = true;
                            fallbackStatus = (String) searchResult.get("fallback_status");
                            globalSearchStatus = (String) searchResult.get("global_search_status");
                            globalSearchError = (String) searchResult.get("global_search_error");
                        }

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

                            Map<String, Object> queryInfo = Map.of("topic", stack.toLowerCase(), "technology", stack);
                            Map<String, Object> normalized = adapter.normalizeIssue(item, queryInfo);

                            if (normalized == null) {
                                srcSkipped++;
                                totalSkipped++;
                                continue;
                            }

                            // 1. Post-Retrieval Validation: Canonical Identity check
                            String issueId = (String) normalized.get("id");
                            if (issueId == null || issueId.isBlank()) {
                                srcFilteredIdentity++;
                                totalFilteredIdentity++;
                                srcSkipped++;
                                totalSkipped++;
                                continue;
                            }

                            // 2. Post-Retrieval Validation: Open state check
                            String state = (String) normalized.getOrDefault("state", "open");
                            if (!"open".equalsIgnoreCase(state)) {
                                srcFilteredState++;
                                totalFilteredState++;
                                srcSkipped++;
                                totalSkipped++;
                                continue;
                            }

                            // 3. Post-Retrieval Validation: Creation-age window check
                            String createdAt = (String) normalized.get("created_at");
                            if (createdAt != null && !createdAt.isBlank()) {
                                try {
                                    Instant createdInstant = Instant.parse(createdAt);
                                    if (createdInstant.isBefore(cutoffInstant)) {
                                        srcFilteredAge++;
                                        totalFilteredAge++;
                                        srcSkipped++;
                                        totalSkipped++;
                                        continue;
                                    }
                                } catch (Exception ignored) {}
                            }

                            // 4. Post-Retrieval Validation: Technology relevance check
                            if (!isRelevanceMatch(normalized, stack)) {
                                srcFilteredTech++;
                                totalFilteredTech++;
                                srcSkipped++;
                                totalSkipped++;
                                continue;
                            }

                            // Diversity check (max issues per repository)
                            String repoFullName = (String) normalized.get("repository_full_name");
                            if (repoFullName != null && !repoFullName.isBlank() && mongoConfig.isConnected()) {
                                long repoCount = issueRepository.countByRepository(repoFullName);
                                if (repoCount >= maxPerRepo) {
                                    totalSkippedDiversity++;
                                    srcSkipped++;
                                    totalSkipped++;
                                    continue;
                                }
                            }

                            // Idempotent upsert
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
                                    srcErrors.add("Upsert failed for " + issueId + ": " + e.getMessage());
                                }
                            } else {
                                srcInserted++;
                                totalInserted++;
                            }
                        }

                    } catch (Exception e) {
                        String errMsg = src + " stack '" + stack + "' page " + page + " failed: " + e.getMessage();
                        srcErrors.add(errMsg);
                        errors.add(errMsg);
                        break;
                    }
                }

                // Small pacing to avoid overloading remote platform APIs
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {}
            }

            Map<String, Object> srcStats = new LinkedHashMap<>();
            srcStats.put("discovered", srcDiscovered);
            srcStats.put("inserted", srcInserted);
            srcStats.put("updated", srcUpdated);
            srcStats.put("skipped", srcSkipped);
            srcStats.put("filtered_state", srcFilteredState);
            srcStats.put("filtered_age", srcFilteredAge);
            srcStats.put("filtered_tech", srcFilteredTech);
            srcStats.put("filtered_identity", srcFilteredIdentity);
            srcStats.put("errors", srcErrors);
            srcStats.put("rate_limit", adapter.getRateLimitState());
            if (srcFallbackUsed) {
                srcStats.put("fallback_used", true);
                srcStats.put("fallback_status", fallbackStatus);
                srcStats.put("global_search_status", globalSearchStatus);
                srcStats.put("global_search_error", globalSearchError);
            }

            String srcStatus = "completed";
            if (!srcErrors.isEmpty()) {
                srcStatus = srcDiscovered > 0 ? "partial" : "failed";
            }
            srcStats.put("status", srcStatus);
            perSourceSummary.put(src, srcStats);
        }

        long elapsedSec = (System.currentTimeMillis() - t0) / 1000;
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        // Overall status:
        // Configured adapters are completed, partial, or failed. Disabled sources are excluded from failure checks.
        boolean hasConfiguredFailure = false;
        boolean hasConfiguredPartial = false;
        int configuredSourceCount = 0;
        int completedSourceCount = 0;

        for (Map<String, Object> stats : perSourceSummary.values()) {
            String st = (String) stats.get("status");
            if ("disabled".equals(st)) {
                continue;
            }
            configuredSourceCount++;
            if ("failed".equals(st)) {
                hasConfiguredFailure = true;
            } else if ("partial".equals(st)) {
                hasConfiguredPartial = true;
            } else if ("completed".equals(st)) {
                completedSourceCount++;
            }
        }

        String overallStatus;
        if (configuredSourceCount == 0) {
            overallStatus = "disabled";
        } else if (hasConfiguredFailure) {
            // Never report total success while a configured source has failed.
            if (totalDiscovered > 0 && (completedSourceCount > 0 || hasConfiguredPartial)) {
                overallStatus = "partial_success";
            } else {
                overallStatus = "failed";
            }
        } else if (hasConfiguredPartial || !errors.isEmpty()) {
            if (totalDiscovered > 0) {
                overallStatus = "partial_success";
            } else {
                overallStatus = "failed";
            }
        } else {
            overallStatus = "completed";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("job_id", jobId);
        summary.put("job_type", "discovery");
        summary.put("status", overallStatus);
        summary.put("creation_age_days", ageDays);
        summary.put("cutoff_date", cutoffDate);
        summary.put("target_stacks_count", targetStacks.size());
        summary.put("target_stacks", new ArrayList<>(targetStacks));
        summary.put("discovered_count", totalDiscovered);
        summary.put("inserted_count", totalInserted);
        summary.put("updated_count", totalUpdated);
        summary.put("skipped_count", totalSkipped);
        summary.put("filtered_state_count", totalFilteredState);
        summary.put("filtered_age_count", totalFilteredAge);
        summary.put("filtered_tech_count", totalFilteredTech);
        summary.put("filtered_identity_count", totalFilteredIdentity);
        summary.put("skipped_diversity_count", totalSkippedDiversity);
        summary.put("duration_seconds", elapsedSec);
        summary.put("per_source_summary", perSourceSummary);
        summary.put("errors", errors);
        summary.put("finished_at", nowIso);

        if (mongoConfig.isConnected()) {
            jobRepository.releaseJobLock(jobId, overallStatus, summary);
        }

        return summary;
    }

    private String buildPlatformQuery(String source, String stack, String cutoffDate, String cutoffIso) {
        String s = source.toLowerCase();
        if ("github".equals(s)) {
            if ("react".equalsIgnoreCase(stack)) {
                return "react in:title,body created:>=" + cutoffDate + " is:open is:issue";
            } else if ("c++".equalsIgnoreCase(stack)) {
                return "language:C++ created:>=" + cutoffDate + " is:open is:issue";
            } else {
                return "language:" + stack + " created:>=" + cutoffDate + " is:open is:issue";
            }
        } else if ("gitlab".equals(s)) {
            return stack.toLowerCase() + " created_after:" + cutoffIso;
        } else if ("bugzilla".equals(s)) {
            return stack.toLowerCase() + " created_after:" + cutoffDate;
        } else {
            // Codeberg / Forgejo & default
            return stack.toLowerCase() + " created_after:" + cutoffIso;
        }
    }

    private boolean isRelevanceMatch(Map<String, Object> normalized, String targetStack) {
        if (targetStack == null || targetStack.isBlank()) return true;
        String normTarget = TechnologyDetectionHelper.normalizeTechnology(targetStack);

        @SuppressWarnings("unchecked")
        List<String> detected = (List<String>) normalized.get("detected_technologies");
        if (detected != null) {
            for (String d : detected) {
                String normD = TechnologyDetectionHelper.normalizeTechnology(d);
                if (normTarget != null && normTarget.equalsIgnoreCase(normD)) {
                    return true;
                }
            }
        }

        String title = (String) normalized.getOrDefault("title", "");
        String body = (String) normalized.getOrDefault("body", "");
        String combined = (title + " " + body).toLowerCase();
        return combined.contains(targetStack.toLowerCase());
    }
}
