package com.issuepilot.controller;

import com.issuepilot.service.CppRankingClient;
import com.issuepilot.service.EvaluationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/validation")
public class ValidationController {

    private final CppRankingClient cppRankingClient;
    private final EvaluationService evaluationService;

    public ValidationController(CppRankingClient cppRankingClient, EvaluationService evaluationService) {
        this.cppRankingClient = cppRankingClient;
        this.evaluationService = evaluationService;
    }

    @PostMapping("/run")
    public Map<String, Object> runValidationSuite() {
        File testExe = new File("ranking_engine/build/test_ranking.exe");
        if (!testExe.exists()) {
            testExe = new File("../ranking_engine/build/test_ranking.exe");
        }

        String unitTestsOutput = "";
        boolean unitTestsPass = false;

        if (testExe.exists()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(testExe.getAbsolutePath());
                pb.redirectErrorStream(true);
                Process process = pb.start();

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }
                int exitCode = process.waitFor();
                unitTestsOutput = sb.toString();
                unitTestsPass = (exitCode == 0);
            } catch (Exception e) {
                unitTestsOutput = "Execution error: " + e.getMessage();
            }
        } else {
            unitTestsOutput = "Test executable not found at: " + testExe.getAbsolutePath();
        }

        Map<String, Object> experimentsData;
        try {
            experimentsData = cppRankingClient.executeExperiments();
        } catch (Exception e) {
            experimentsData = Map.of("error", e.getMessage(), "experiments", Collections.emptyList());
        }

        Map<String, Object> evalData;
        try {
            evalData = cppRankingClient.executeEval();
        } catch (Exception e) {
            evalData = Map.of("error", e.getMessage());
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", unitTestsPass ? "success" : "failure");
        res.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        res.put("unit_tests", Map.of(
                "passed", unitTestsPass,
                "raw_output", unitTestsOutput
        ));
        res.put("experiments", experimentsData.getOrDefault("experiments", Collections.emptyList()));
        res.put("evaluation_metrics", evalData);

        return res;
    }

    @GetMapping("/evaluation-report")
    public Map<String, Object> getEvaluationReport() {
        try {
            return evaluationService.runEvaluation();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Evaluation failed: " + e.getMessage());
        }
    }
}
