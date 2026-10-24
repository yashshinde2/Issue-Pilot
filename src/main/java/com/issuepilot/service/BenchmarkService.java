package com.issuepilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.controller.SimulatorController;
import com.issuepilot.model.RankingWeights;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class BenchmarkService {

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final CppRankingClient cppRankingClient;
    private final SimulatorController simulatorController;
    private final ObjectMapper objectMapper;

    public BenchmarkService(
            MongoConfig mongoConfig,
            AppProperties properties,
            CppRankingClient cppRankingClient,
            SimulatorController simulatorController,
            ObjectMapper objectMapper
    ) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.cppRankingClient = cppRankingClient;
        this.simulatorController = simulatorController;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getEnvironmentInfo() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        env.put("java_version", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        env.put("available_processors", Runtime.getRuntime().availableProcessors());
        env.put("max_memory_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        env.put("database_connected", mongoConfig.isConnected());
        env.put("database_name", properties.getMongodbDatabase());
        env.put("cpp_engine_path", properties.getCppRankingEnginePath());
        env.put("cpp_engine_exists", new File(properties.getCppRankingEnginePath()).exists());
        return env;
    }

    public List<Map<String, Object>> generateSyntheticCandidates(int count) {
        List<Map<String, Object>> pool = simulatorController.getSampleIssues();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> base = new HashMap<>(pool.get(i % pool.size()));
            base.put("id", String.format("benchmark_issue_%04d", i + 1));
            base.put("native_id", String.valueOf(1000 + i));
            base.put("issue_number", 1000 + i);
            base.put("title", "Benchmark candidate issue #" + (i + 1) + " - " + base.getOrDefault("technology", "Java"));
            result.add(base);
        }
        return result;
    }

    @SuppressWarnings("null")
    public Map<String, Object> benchmarkCppEngine(List<Integer> batchSizes, int runsPerBatch) {
        Map<String, Object> profile = simulatorController.getSampleProfiles().get(0);
        Map<String, Object> weights = objectMapper.convertValue(new RankingWeights(), new TypeReference<Map<String, Object>>() {});

        List<Map<String, Object>> batchResults = new ArrayList<>();

        for (int size : batchSizes) {
            List<Map<String, Object>> candidates = generateSyntheticCandidates(size);
            Map<String, Object> payload = Map.of(
                    "profile", profile,
                    "weights", weights,
                    "candidates", candidates
            );

            List<Double> durationsMs = new ArrayList<>();
            for (int r = 0; r < runsPerBatch; r++) {
                long t0 = System.nanoTime();
                cppRankingClient.executeRanking(payload);
                long t1 = System.nanoTime();
                durationsMs.add((t1 - t0) / 1_000_000.0);
            }

            double meanMs = durationsMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double minMs = durationsMs.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double maxMs = durationsMs.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double msPerCandidate = meanMs / size;

            Map<String, Object> bData = new LinkedHashMap<>();
            bData.put("candidate_count", size);
            bData.put("runs", runsPerBatch);
            bData.put("mean_ms", Math.round(meanMs * 1000.0) / 1000.0);
            bData.put("min_ms", Math.round(minMs * 1000.0) / 1000.0);
            bData.put("max_ms", Math.round(maxMs * 1000.0) / 1000.0);
            bData.put("ms_per_candidate", Math.round(msPerCandidate * 10000.0) / 10000.0);
            batchResults.add(bData);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("title", "C++ Ranking Engine Performance Benchmark");
        res.put("batches", batchResults);
        return res;
    }

    public Map<String, Object> runFullBenchmark() {
        Map<String, Object> benchmarkReport = new LinkedHashMap<>();
        benchmarkReport.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        benchmarkReport.put("environment", getEnvironmentInfo());
        benchmarkReport.put("cpp_engine_benchmark", benchmarkCppEngine(List.of(10, 50, 100, 200, 500), 5));
        return benchmarkReport;
    }
}
