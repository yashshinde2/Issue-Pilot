package com.issuepilot.service;

import com.issuepilot.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerService.class);

    private final AppProperties properties;
    private final DiscoveryService discoveryService;
    private final RefreshService refreshService;
    private final LifecycleService lifecycleService;

    private ScheduledExecutorService executorService;
    private volatile boolean running = false;
    private String lastRunAt = null;
    private Map<String, Object> lastRunSummary = new HashMap<>();

    public SchedulerService(
            AppProperties properties,
            DiscoveryService discoveryService,
            RefreshService refreshService,
            LifecycleService lifecycleService
    ) {
        this.properties = properties;
        this.discoveryService = discoveryService;
        this.refreshService = refreshService;
        this.lifecycleService = lifecycleService;
    }

    @PostConstruct
    public void init() {
        if (properties.isSchedulerEnabled()) {
            logger.info("Auto-starting background ingestion scheduler...");
            start();
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    public synchronized boolean start() {
        if (running) {
            return false;
        }

        executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "issue-pilot-scheduler");
            t.setDaemon(true);
            return t;
        });

        int intervalMinutes = properties.getSchedulerIntervalMinutes();
        if (intervalMinutes <= 0) intervalMinutes = 60;

        executorService.scheduleAtFixedRate(
                this::runScheduledCycle,
                intervalMinutes * 60L,
                intervalMinutes * 60L,
                TimeUnit.SECONDS
        );

        running = true;
        logger.info("Ingestion scheduler started with interval {} minutes.", intervalMinutes);
        return true;
    }

    public synchronized boolean stop() {
        if (!running) {
            return false;
        }

        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        running = false;
        logger.info("Ingestion scheduler stopped.");
        return true;
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> runAllNow() {
        logger.info("Executing full ingestion cycle immediately: Discovery -> Refresh -> Stale Cleanup...");
        long t0 = System.currentTimeMillis();

        Map<String, Object> discResult = discoveryService.runDiscovery(null, null, null, true, true, "all");
        Map<String, Object> refResult = refreshService.runRefresh(50, null, true);
        Map<String, Object> cleanResult = lifecycleService.runCleanup(null, true);

        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("status", "completed");
        cycle.put("executed_at", nowIso);
        cycle.put("total_duration_seconds", elapsed);
        cycle.put("discovery", discResult);
        cycle.put("refresh", refResult);
        cycle.put("cleanup", cleanResult);

        this.lastRunAt = nowIso;
        this.lastRunSummary = cycle;

        return cycle;
    }

    private void runScheduledCycle() {
        try {
            runAllNow();
        } catch (Exception e) {
            logger.error("Scheduled ingestion cycle failed: {}", e.getMessage(), e);
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("is_running", running);
        status.put("scheduler_enabled_in_config", properties.isSchedulerEnabled());
        status.put("interval_minutes", properties.getSchedulerIntervalMinutes());
        status.put("last_run_at", lastRunAt);
        status.put("last_run_summary", lastRunSummary);
        return status;
    }
}
