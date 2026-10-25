package com.issuepilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.service.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class IssuePilotCli implements CommandLineRunner, ExitCodeGenerator {

    private final EvaluationService evaluationService;
    private final BenchmarkService benchmarkService;
    private final AuditService auditService;
    private final DiscoveryService discoveryService;
    private final RefreshService refreshService;
    private final LifecycleService lifecycleService;
    private final StagingPreflightService stagingPreflightService;
    private final MongoConfig mongoConfig;
    private final ObjectMapper objectMapper;

    private int exitCode = 0;

    public IssuePilotCli(
            EvaluationService evaluationService,
            BenchmarkService benchmarkService,
            AuditService auditService,
            DiscoveryService discoveryService,
            RefreshService refreshService,
            LifecycleService lifecycleService,
            StagingPreflightService stagingPreflightService,
            MongoConfig mongoConfig,
            ObjectMapper objectMapper
    ) {
        this.evaluationService = evaluationService;
        this.benchmarkService = benchmarkService;
        this.auditService = auditService;
        this.discoveryService = discoveryService;
        this.refreshService = refreshService;
        this.lifecycleService = lifecycleService;
        this.stagingPreflightService = stagingPreflightService;
        this.mongoConfig = mongoConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(String... args) {
        if (args == null || args.length == 0) {
            return;
        }

        String command = args[0].toLowerCase().trim();
        Map<String, String> parsedArgs = parseOptions(args);

        try {
            switch (command) {
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
                case "--preflight-staging": {
                    System.out.println("=================================================");
                    System.out.println(" Issue Pilot Staging Environment Preflight Audit");
                    System.out.println("=================================================");
                    boolean requireIsolated = "true".equalsIgnoreCase(parsedArgs.getOrDefault("require-isolated-cluster",
                            System.getenv().getOrDefault("REQUIRE_ISOLATED_CLUSTER", "false")));
                    String expectedHost = parsedArgs.getOrDefault("expected-host",
                            System.getenv().getOrDefault("EXPECTED_STAGING_HOST", null));

                    StagingPreflightService.PreflightResult preflight = stagingPreflightService.validateStagingEnvironment(requireIsolated, expectedHost);
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(preflight.toMap()));

                    if (!preflight.isPassed()) {
                        System.err.println("\n[!] PREFLIGHT AUDIT FAILED: Environment is NOT verified for staging isolation.");
                        this.exitCode = 1;
                    } else {
                        System.out.println("\n[+] PREFLIGHT AUDIT PASSED: Environment positively verified for staging.");
                    }
                    break;
                }
                case "--discover": {
                    if (!verifyEnvironmentSafety(parsedArgs)) {
                        return;
                    }
                    int days = getIntParam(parsedArgs, "days", 30);
                    String source = parsedArgs.getOrDefault("source", "all");
                    int pages = getIntParam(parsedArgs, "pages", 2);
                    int perPage = getIntParam(parsedArgs, "per-page", 15);

                    System.out.println("Triggering candidate issue discovery (source=" + source + ", days=" + days + ")...");
                    Map<String, Object> res = discoveryService.runDiscovery(null, pages, perPage, true, true, source, days);
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(res));
                    String status = (String) res.getOrDefault("status", "completed");
                    if ("failed".equals(status) || "partial_failure".equals(status)) {
                        this.exitCode = 1;
                    }
                    break;
                }
                case "--refresh": {
                    int batch = getIntParam(parsedArgs, "batch", 50);
                    System.out.println("Triggering candidate issue refresh (batch=" + batch + ")...");
                    Map<String, Object> res = refreshService.runRefresh(batch, null, true);
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(res));
                    break;
                }
                case "--cleanup": {
                    System.out.println("Triggering stale candidate cleanup...");
                    Map<String, Object> res = lifecycleService.runCleanup(null, true);
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(res));
                    break;
                }
                case "--daily-ingestion": {
                    System.out.println("=================================================");
                    System.out.println(" Issue Pilot Daily Multi-Platform Issue Ingestion");
                    System.out.println("=================================================");

                    if (!verifyEnvironmentSafety(parsedArgs)) {
                        return;
                    }

                    int days = getIntParam(parsedArgs, "days", 30);
                    String source = parsedArgs.getOrDefault("source", "all");
                    int pages = getIntParam(parsedArgs, "pages", 2);
                    int perPage = getIntParam(parsedArgs, "per-page", 15);
                    int refreshBatch = getIntParam(parsedArgs, "refresh-batch", 50);

                    System.out.println("1. Running stack-aware discovery (source=" + source + ", creationAgeWindow=" + days + "d)...");
                    Map<String, Object> discRes = discoveryService.runDiscovery(null, pages, perPage, true, true, source, days);

                    System.out.println("2. Refreshing existing open candidate pool (batch=" + refreshBatch + ")...");
                    Map<String, Object> refRes = refreshService.runRefresh(refreshBatch, null, true);

                    String discStatus = (String) discRes.getOrDefault("status", "completed");
                    String refStatus = (String) refRes.getOrDefault("status", "completed");

                    String overall = "completed";
                    if ("failed".equals(discStatus) || "failed".equals(refStatus) || "partial_failure".equals(discStatus)) {
                        overall = "failed";
                        this.exitCode = 1;
                    } else if ("completed_with_errors".equals(discStatus)) {
                        overall = "completed_with_errors";
                    }

                    Map<String, Object> consolidated = new LinkedHashMap<>();
                    consolidated.put("job_type", "daily_ingestion");
                    consolidated.put("overall_status", overall);
                    consolidated.put("creation_age_days", days);
                    consolidated.put("source_filter", source);
                    consolidated.put("discovery", discRes);
                    consolidated.put("refresh", refRes);

                    System.out.println("\n--- DAILY INGESTION COMPLETE ---");
                    System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(consolidated));
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
            this.exitCode = 2;
        }
    }

    private Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && a.contains("=")) {
                String[] parts = a.substring(2).split("=", 2);
                options.put(parts[0].toLowerCase().trim(), parts[1].trim());
            } else if (a.startsWith("--")) {
                options.put(a.substring(2).toLowerCase().trim(), "true");
            }
        }
        return options;
    }

    private int getIntParam(Map<String, String> options, String key, int defaultValue) {
        String val = options.get(key);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private boolean verifyEnvironmentSafety(Map<String, String> parsedArgs) {
        String targetEnv = parsedArgs.getOrDefault("target-env", System.getenv().getOrDefault("TARGET_ENV", ""));
        boolean requireIsolated = "true".equalsIgnoreCase(parsedArgs.getOrDefault("require-isolated-cluster",
                System.getenv().getOrDefault("REQUIRE_ISOLATED_CLUSTER", "false")));
        String expectedHost = parsedArgs.getOrDefault("expected-host",
                System.getenv().getOrDefault("EXPECTED_STAGING_HOST", null));

        if ("staging".equalsIgnoreCase(targetEnv)) {
            System.out.println("Validating staging environment isolation before proceeding...");
            StagingPreflightService.PreflightResult preflight = stagingPreflightService.validateStagingEnvironment(requireIsolated, expectedHost);
            if (!preflight.isPassed()) {
                System.err.println("\n[!] STAGING PREFLIGHT ABORT: Environment isolation checks failed:");
                for (String err : preflight.getErrors()) {
                    System.err.println("  - " + err);
                }
                this.exitCode = 1;
                return false;
            }
            System.out.println("[+] Staging environment verified (target DB: " + preflight.getTargetDatabase() +
                    ", isolation: " + preflight.getClusterIsolationLevel() + ").");
            return true;
        }

        // Safety guard for production database
        String effectiveDb = mongoConfig.getEffectiveDatabaseName();
        boolean allowProd = "true".equalsIgnoreCase(parsedArgs.getOrDefault("allow-production-write", "false"));
        if ("issue_pilot".equalsIgnoreCase(effectiveDb) && !allowProd) {
            System.err.println("\n[!] SAFETY GUARD ABORT: Target database is '" + effectiveDb + "' (PRODUCTION).");
            System.err.println("Execution halted to prevent unintended writes to production.");
            System.err.println("To run in staging, use --target-env=staging with an isolated database (e.g. 'issue_pilot_staging').");
            System.err.println("To explicitly authorize production writes, specify --allow-production-write=true.");
            this.exitCode = 1;
            return false;
        }
        return true;
    }

    private void printHelp() {
        System.out.println("Issue Pilot CLI Options:");
        System.out.println("  --preflight-staging Audit staging environment isolation without performing writes");
        System.out.println("                      Options: --require-isolated-cluster=<true|false>, --expected-host=<host>");
        System.out.println("  --daily-ingestion   Run unified daily discovery and refresh across platforms");
        System.out.println("                      Options: --target-env=<staging>, --days=<7|30>, --source=<all|github|gitlab|codeberg|bugzilla>, --pages=<N>, --per-page=<N>, --refresh-batch=<N>");
        System.out.println("  --discover          Run candidate issue discovery across configured sources");
        System.out.println("                      Options: --target-env=<staging>, --days=<7|30>, --source=<all|...>, --pages=<N>, --per-page=<N>");
        System.out.println("  --refresh           Run candidate pool refresh batch (options: --batch=<N>)");
        System.out.println("  --cleanup           Run stale issue lifecycle evaluation");
        System.out.println("  --eval, -e          Run recommendation-quality evaluation suite (NDCG@5, Precision@5)");
        System.out.println("  --benchmark, -b     Run performance and latency benchmarks against C++ ranking engine");
        System.out.println("  --audit, -a         Audit MongoDB Atlas collections, schema integrity, and counts");
        System.out.println("  --help, -h          Show this help message");
    }
}
