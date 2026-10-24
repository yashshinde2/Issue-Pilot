package com.issuepilot.controller;

import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.service.CppRankingClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final CppRankingClient cppRankingClient;
    private final com.issuepilot.service.SchedulerService schedulerService;

    public HealthController(
            MongoConfig mongoConfig,
            AppProperties properties,
            CppRankingClient cppRankingClient,
            com.issuepilot.service.SchedulerService schedulerService
    ) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.cppRankingClient = cppRankingClient;
        this.schedulerService = schedulerService;
    }

    public boolean isSchedulerActive() {
        return schedulerService != null && schedulerService.isRunning();
    }

    @GetMapping
    public Map<String, Object> healthCheck() {
        boolean atlasOk = mongoConfig.isConnected();
        boolean cppOk = false;
        String cppVer = "unknown";
        try {
            cppOk = cppRankingClient.isAvailable();
            if (cppOk) {
                cppVer = cppRankingClient.getVersion();
            }
        } catch (Exception ignored) {
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", (atlasOk && cppOk) ? "healthy" : "degraded");
        response.put("mongodb_atlas_connected", atlasOk);
        response.put("database_name", properties.getMongodbDatabase());
        response.put("cpp_ranking_engine_available", cppOk);
        response.put("algorithm_version", cppVer);
        response.put("github_token_configured", properties.getGithubToken() != null && !properties.getGithubToken().isBlank());
        response.put("ingestion_enabled", properties.isIngestionEnabled());
        response.put("scheduler_active", isSchedulerActive());

        return response;
    }
}
