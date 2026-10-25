package com.issuepilot.controller;

import com.issuepilot.adapter.AdapterRegistry;
import com.issuepilot.adapter.BaseIssueSourceAdapter;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.JobRepository;
import com.issuepilot.service.DiscoveryService;
import com.issuepilot.service.LifecycleService;
import com.issuepilot.service.RefreshService;
import com.issuepilot.service.SchedulerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private final MongoConfig mongoConfig;
    private final AdapterRegistry adapterRegistry;
    private final IssueRepository issueRepository;
    private final JobRepository jobRepository;
    private final DiscoveryService discoveryService;
    private final RefreshService refreshService;
    private final LifecycleService lifecycleService;
    private final SchedulerService schedulerService;

    public IngestionController(
            MongoConfig mongoConfig,
            AdapterRegistry adapterRegistry,
            IssueRepository issueRepository,
            JobRepository jobRepository,
            DiscoveryService discoveryService,
            RefreshService refreshService,
            LifecycleService lifecycleService,
            SchedulerService schedulerService
    ) {
        this.mongoConfig = mongoConfig;
        this.adapterRegistry = adapterRegistry;
        this.issueRepository = issueRepository;
        this.jobRepository = jobRepository;
        this.discoveryService = discoveryService;
        this.refreshService = refreshService;
        this.lifecycleService = lifecycleService;
        this.schedulerService = schedulerService;
    }

    @PostMapping("/run")
    public Map<String, Object> triggerIngestionJob(@RequestParam(defaultValue = "15") int per_page) {
        try {
            Map<String, Object> summary = discoveryService.runDiscovery(null, 1, per_page, false, true, "github");
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("message", "Ingestion job completed successfully");
            res.put("job_summary", summary);
            return res;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Ingestion run failed: " + e.getMessage());
        }
    }

    @PostMapping("/discover")
    public Map<String, Object> triggerDiscoveryJob(
            @RequestParam(required = false, name = "max_issues") Integer maxIssues,
            @RequestParam(defaultValue = "2", name = "pages_per_query") int pagesPerQuery,
            @RequestParam(defaultValue = "15", name = "per_page") int perPage,
            @RequestParam(defaultValue = "true") boolean resume,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(defaultValue = "all") String source,
            @RequestParam(required = false, name = "creation_age_days") Integer creationAgeDays
    ) {
        try {
            Map<String, Object> result = discoveryService.runDiscovery(maxIssues, pagesPerQuery, perPage, resume, force, source, creationAgeDays);
            if ("locked".equals(result.get("status"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, (String) result.get("message"));
            }
            return result;
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Discovery job failed: " + e.getMessage());
        }
    }

    @PostMapping("/refresh")
    public Map<String, Object> triggerRefreshJob(
            @RequestParam(defaultValue = "50", name = "batch_size") int batchSize,
            @RequestParam(required = false, name = "max_age_days") Integer maxAgeDays,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        try {
            Map<String, Object> result = refreshService.runRefresh(batchSize, maxAgeDays, force);
            if ("locked".equals(result.get("status"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, (String) result.get("message"));
            }
            return result;
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Refresh job failed: " + e.getMessage());
        }
    }

    @PostMapping("/cleanup")
    public Map<String, Object> triggerCleanupJob(
            @RequestParam(required = false, name = "older_than_days") Integer olderThanDays,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        try {
            Map<String, Object> result = lifecycleService.runCleanup(olderThanDays, force);
            if ("locked".equals(result.get("status"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, (String) result.get("message"));
            }
            return result;
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cleanup job failed: " + e.getMessage());
        }
    }

    @PostMapping("/run-all")
    public Map<String, Object> triggerFullCycle() {
        try {
            return schedulerService.runAllNow();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cycle run failed: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public Map<String, Object> getPlatformsHealth() {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> activeSources = new ArrayList<>();
        List<String> degradedSources = new ArrayList<>();

        for (Map.Entry<String, BaseIssueSourceAdapter> entry : adapterRegistry.getAllAdapters().entrySet()) {
            String name = entry.getKey();
            BaseIssueSourceAdapter adapter = entry.getValue();
            try {
                Map<String, Object> h = adapter.checkHealth();
                report.put(name, h);
                if (Boolean.TRUE.equals(h.get("ingestion_enabled"))) {
                    activeSources.add(name);
                } else {
                    degradedSources.add(name);
                }
            } catch (Exception e) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("source", name);
                fallback.put("source_instance", adapter.getSourceInstance());
                fallback.put("configured", false);
                fallback.put("reachable", false);
                fallback.put("auth_status", "not_verified");
                fallback.put("read_permission_status", "denied");
                fallback.put("api_version", "unknown");
                fallback.put("rate_limit_info", Map.of("limit", "unknown", "remaining", "unknown"));
                fallback.put("error_message", "Unexpected health check error: " + e.getMessage());
                fallback.put("recommended_action", "Check configuration and network reachability.");
                fallback.put("ingestion_enabled", false);
                report.put(name, fallback);
                degradedSources.add(name);
            }
        }

        String overall = (activeSources.size() == adapterRegistry.getAllAdapters().size())
                ? "operational"
                : (!activeSources.isEmpty() ? "degraded" : "failing");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("overall_status", overall);
        response.put("active_sources", activeSources);
        response.put("degraded_sources", degradedSources);
        response.put("platforms", report);

        return response;
    }

    @PostMapping("/health/check")
    public Map<String, Object> runManualHealthCheck() {
        return getPlatformsHealth();
    }

    @GetMapping("/platforms")
    public Map<String, Object> getPlatformDetails() {
        Map<String, Object> healthData = getPlatformsHealth();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> platforms = (Map<String, Map<String, Object>>) healthData.get("platforms");
        Map<String, Object> platformsReport = new LinkedHashMap<>();

        Map<String, Long> dbCounts = new HashMap<>();
        if (mongoConfig.isConnected()) {
            try {
                for (String s : Arrays.asList("github", "gitlab", "codeberg", "bugzilla")) {
                    dbCounts.put(s, issueRepository.countBySource(s));
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> latestJob = mongoConfig.isConnected() ? jobRepository.getLatestJob() : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> latestPerSource = (latestJob != null && latestJob.get("per_source_summary") instanceof Map)
                ? (Map<String, Object>) latestJob.get("per_source_summary")
                : Collections.emptyMap();

        for (Map.Entry<String, Map<String, Object>> entry : platforms.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> h = entry.getValue();

            @SuppressWarnings("unchecked")
            Map<String, Object> srcJob = (Map<String, Object>) latestPerSource.getOrDefault(name, Collections.emptyMap());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("display_name", name.substring(0, 1).toUpperCase() + name.substring(1));
            item.put("source_instance", h.get("source_instance"));
            item.put("configured", h.getOrDefault("configured", false));
            item.put("reachable", h.getOrDefault("reachable", false));
            item.put("auth_status", h.get("auth_status"));
            item.put("read_permission_status", h.get("read_permission_status"));
            item.put("api_version", h.get("api_version"));
            item.put("rate_limit_info", h.getOrDefault("rate_limit_info", Collections.emptyMap()));
            item.put("last_successful_check_at", h.get("last_successful_check_at"));
            item.put("error_message", h.get("error_message"));
            item.put("recommended_action", h.get("recommended_action"));
            item.put("ingestion_enabled", h.getOrDefault("ingestion_enabled", false));
            item.put("candidate_count_in_atlas", dbCounts.getOrDefault(name, 0L));

            Map<String, Object> lastRunStats = new LinkedHashMap<>();
            lastRunStats.put("discovered", srcJob.getOrDefault("discovered", 0));
            lastRunStats.put("inserted", srcJob.getOrDefault("inserted", 0));
            lastRunStats.put("updated", srcJob.getOrDefault("updated", 0));
            lastRunStats.put("skipped", srcJob.getOrDefault("skipped", 0));
            lastRunStats.put("status", srcJob.getOrDefault("status", "idle"));
            item.put("last_run_stats", lastRunStats);

            platformsReport.put(name, item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        response.put("overall_status", healthData.get("overall_status"));
        response.put("active_sources", healthData.get("active_sources"));
        response.put("degraded_sources", healthData.get("degraded_sources"));
        response.put("platforms", platformsReport);

        return response;
    }

    @GetMapping("/status")
    public Map<String, Object> getPoolStatus() {
        if (!mongoConfig.isConnected()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("atlas_connected", false);
            resp.put("total_issues", 0);
            resp.put("open_issues", 0);
            resp.put("latest_job", null);
            resp.put("scheduler", schedulerService.getStatus());
            resp.put("rate_limit", adapterRegistry.getAdapter("github").getRateLimitState());
            resp.put("platforms_health", getPlatformsHealth());
            resp.put("message", "MongoDB Atlas connection not established");
            return resp;
        }

        try {
            Map<String, Object> poolMetrics = issueRepository.getPoolMetrics();
            Map<String, Object> latestJob = jobRepository.getLatestJob();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("atlas_connected", true);
            resp.put("total_issues", poolMetrics.getOrDefault("total_candidates", 0L));
            resp.put("open_issues", poolMetrics.getOrDefault("open_candidates", 0L));
            resp.put("closed_issues", poolMetrics.getOrDefault("closed_candidates", 0L));
            resp.put("status_breakdown", poolMetrics.getOrDefault("status_breakdown", Collections.emptyMap()));
            resp.put("source_breakdown", poolMetrics.getOrDefault("source_breakdown", Collections.emptyMap()));
            resp.put("unique_repositories", poolMetrics.getOrDefault("unique_repositories", 0));
            resp.put("top_repositories", poolMetrics.getOrDefault("top_repositories", Collections.emptyList()));
            resp.put("scheduler", schedulerService.getStatus());
            resp.put("rate_limit", adapterRegistry.getAdapter("github").getRateLimitState());
            resp.put("platforms_health", getPlatformsHealth());
            resp.put("latest_job", latestJob);
            return resp;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/rate-limit")
    public Map<String, Object> getRateLimits(@RequestParam(defaultValue = "all") String source) {
        if (source != null && "github".equalsIgnoreCase(source.trim())) {
            return adapterRegistry.getAdapter("github").getRateLimit();
        }
        Map<String, Object> res = new LinkedHashMap<>();
        for (Map.Entry<String, BaseIssueSourceAdapter> entry : adapterRegistry.getAllAdapters().entrySet()) {
            res.put(entry.getKey(), entry.getValue().getRateLimit());
        }
        return res;
    }

    @GetMapping("/jobs")
    public Map<String, Object> listIngestionJobs(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(required = false, name = "job_type") String jobType
    ) {
        if (!mongoConfig.isConnected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MongoDB Atlas unavailable");
        }
        try {
            List<Map<String, Object>> jobs = jobRepository.listJobs(limit, skip, jobType);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("count", jobs.size());
            res.put("jobs", jobs);
            return res;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> getJobDetails(@PathVariable String jobId) {
        if (!mongoConfig.isConnected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MongoDB Atlas unavailable");
        }
        Map<String, Object> job = jobRepository.getJob(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job '" + jobId + "' not found.");
        }
        return job;
    }

    @PostMapping("/scheduler/start")
    public Map<String, Object> startScheduler() {
        boolean started = schedulerService.start();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", started ? "started" : "already_running");
        res.put("scheduler", schedulerService.getStatus());
        return res;
    }

    @PostMapping("/scheduler/stop")
    public Map<String, Object> stopScheduler() {
        schedulerService.stop();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "stopped");
        res.put("scheduler", schedulerService.getStatus());
        return res;
    }

    @GetMapping("/scheduler/status")
    public Map<String, Object> getSchedulerStatus() {
        return schedulerService.getStatus();
    }
}
