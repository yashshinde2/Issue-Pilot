package com.issuepilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

@Component
public class IssuePilotCli implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(IssuePilotCli.class);

    private final EvaluationService evaluationService;
    private final BenchmarkService benchmarkService;
    private final AuditService auditService;
    private final DiscoveryService discoveryService;
    private final RefreshService refreshService;
    private final LifecycleService lifecycleService;
    private final ObjectMapper objectMapper;

    public IssuePilotCli(
            EvaluationService evaluationService,
            BenchmarkService benchmarkService,
            AuditService auditService,
            DiscoveryService discoveryService,
            RefreshService refreshService,
            LifecycleService lifecycleService,
            ObjectMapper objectMapper
    ) {
        this.evaluationService = evaluationService;
        this.benchmarkService = benchmarkService;
        this.auditService = auditService;
        this.discoveryService = discoveryService;
        this.refreshService = refreshService;
        this.lifecycleService = lifecycleService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        if (args == null || args.length == 0) {
            return;
        }

        String arg = args[0].toLowerCase().trim();

        try {
            switch (arg) {
                case "--eval":
                case "-e": {
                    System.out.println("=================================================");
                    System.out.println(" Issue Pilot Recommendation Quality Evaluation");
                    System.out.println("=================================================");
                    Map<String, Object> report = evaluationService.runEvaluation();
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                    break;
                }
                case "--benchmark":
                case "-b": {
                    System.out.println("=================================================");
                    System.out.println(" Issue Pilot Performance & Reliability Benchmark");
                    System.out.println("=================================================");
                    Map<String, Object> report = benchmarkService.runFullBenchmark();
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                    break;
                }
                case "--audit":
                case "-a": {
                    System.out.println("=================================================");
                    System.out.println(" Issue Pilot MongoDB Atlas Database Integrity Audit");
                    System.out.println("=================================================");
                    Map<String, Object> report = auditService.auditDatabase();
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                    break;
                }
                case "--discover": {
                    System.out.println("Triggering candidate issue discovery...");
                    Map<String, Object> res = discoveryService.runDiscovery(null, 2, 15, true, true, "all");
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(res));
                    break;
                }
                case "--refresh": {
                    System.out.println("Triggering candidate issue refresh...");
                    Map<String, Object> res = refreshService.runRefresh(50, null, true);
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(res));
                    break;
                }
                case "--cleanup": {
                    System.out.println("Triggering stale candidate cleanup...");
                    Map<String, Object> res = lifecycleService.runCleanup(null, true);
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(res));
                    break;
                }
                case "--help":
                case "-h": {
                    printHelp();
                    break;
                }
                default:
                    // Server boot, ignore other arguments
                    break;
            }
        } catch (Exception e) {
            System.err.println("CLI execution error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void printHelp() {
        System.out.println("Issue Pilot CLI Options:");
        System.out.println("  --eval, -e         Run recommendation-quality evaluation suite (NDCG@5, Precision@5)");
        System.out.println("  --benchmark, -b    Run performance and latency benchmarks against C++ ranking engine");
        System.out.println("  --audit, -a        Audit MongoDB Atlas collections, schema integrity, and counts");
        System.out.println("  --discover         Run candidate issue discovery across GitHub, GitLab, Codeberg, Bugzilla");
        System.out.println("  --refresh          Run candidate pool refresh batch");
        System.out.println("  --cleanup          Run stale issue lifecycle evaluation");
        System.out.println("  --help, -h         Show this help message");
    }
}
