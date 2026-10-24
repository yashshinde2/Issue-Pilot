package com.issuepilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class CppRankingClient {

    private static final Logger logger = LoggerFactory.getLogger(CppRankingClient.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private File executableFile;

    public CppRankingClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        resolveExecutable();
    }

    private void resolveExecutable() {
        String path = properties.getCppEnginePath();
        File f = new File(path);
        if (!f.isAbsolute()) {
            File userDir = new File(System.getProperty("user.dir", "."));
            f = new File(userDir, path);
            if (!f.exists() && userDir.getParentFile() != null) {
                f = new File(userDir.getParentFile(), path);
            }
        }
        this.executableFile = f;
    }

    public boolean isAvailable() {
        return executableFile != null && executableFile.exists() && executableFile.canExecute();
    }

    public String getVersion() {
        if (!isAvailable()) {
            return "unavailable";
        }
        try {
            Process process = new ProcessBuilder(executableFile.getAbsolutePath(), "--version")
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                output = sb.toString();
            }

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                Map<String, Object> map = objectMapper.readValue(output, new TypeReference<>() {});
                Object ver = map.get("algorithm_version");
                return ver != null ? ver.toString() : "1.0.0-phase1";
            }
        } catch (Exception e) {
            logger.warn("Could not query C++ ranking engine version: {}", e.getMessage());
        }
        return "1.0.0-phase1";
    }

    public Map<String, Object> executeRanking(Map<String, Object> payload) {
        if (!isAvailable()) {
            throw new IllegalStateException("C++ Ranking Engine executable not found at: " +
                    (executableFile != null ? executableFile.getAbsolutePath() : "unknown"));
        }
        try {
            String jsonInput = objectMapper.writeValueAsString(payload);
            Process process = new ProcessBuilder(executableFile.getAbsolutePath(), "--stdin-json")
                    .start();

            try (OutputStream os = process.getOutputStream()) {
                os.write(jsonInput.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                stdout = sb.toString();
            }

            String stderr;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                stderr = sb.toString();
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("C++ ranking engine execution timed out after 15 seconds.");
            }

            if (process.exitValue() != 0) {
                logger.error("C++ engine error: {}", stderr);
                throw new RuntimeException("C++ ranking engine failed with code " + process.exitValue() + ": " + stderr);
            }

            return objectMapper.readValue(stdout, new TypeReference<>() {});
        } catch (Exception e) {
            logger.error("C++ ranking engine execution failed: {}", e.getMessage(), e);
            throw new RuntimeException("Ranking execution failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> executeExperiments() {
        if (!isAvailable()) {
            throw new IllegalStateException("C++ Ranking Engine executable not found.");
        }
        try {
            Process process = new ProcessBuilder(executableFile.getAbsolutePath(), "--test-experiments")
                    .redirectErrorStream(false)
                    .start();

            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                stdout = sb.toString();
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                return objectMapper.readValue(stdout, new TypeReference<>() {});
            }
            throw new RuntimeException("C++ engine experiments returned exit code " + process.exitValue());
        } catch (Exception e) {
            logger.error("Failed to execute C++ experiments: {}", e.getMessage(), e);
            throw new RuntimeException("Experiments failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> executeEval() {
        if (!isAvailable()) {
            throw new IllegalStateException("C++ Ranking Engine executable not found.");
        }
        try {
            Process process = new ProcessBuilder(executableFile.getAbsolutePath(), "--eval")
                    .redirectErrorStream(false)
                    .start();

            String stdout;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                stdout = sb.toString();
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                return objectMapper.readValue(stdout, new TypeReference<>() {});
            }
            throw new RuntimeException("C++ engine eval returned exit code " + process.exitValue());
        } catch (Exception e) {
            logger.error("Failed to execute C++ eval: {}", e.getMessage(), e);
            throw new RuntimeException("Evaluation failed: " + e.getMessage(), e);
        }
    }
}
