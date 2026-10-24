package com.issuepilot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Utility to load environment variables from .env and atlas-credentials.env
 * into Java System properties before Spring Boot startup, mirroring Python's dotenv behavior.
 */
public final class EnvLoader {

    private static final Logger logger = LoggerFactory.getLogger(EnvLoader.class);

    private EnvLoader() {
    }

    public static void loadEnv() {
        File userDir = new File(System.getProperty("user.dir", "."));
        loadFile(new File(userDir, ".env"));
        loadFile(new File(userDir, "atlas-credentials.env"));
        // Also check if running from child directory
        if (userDir.getParentFile() != null) {
            loadFile(new File(userDir.getParentFile(), ".env"));
            loadFile(new File(userDir.getParentFile(), "atlas-credentials.env"));
        }
    }

    public static void loadFile(File file) {
        if (!file.exists() || !file.isFile()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        val = val.substring(1, val.length() - 1);
                    }
                    if (System.getProperty(key) == null && System.getenv(key) == null) {
                        System.setProperty(key, val);
                    }
                }
            }
            logger.info("Loaded configuration from {}", file.getName());
        } catch (IOException e) {
            logger.warn("Could not read environment file {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    public static String maskUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        return uri.replaceAll("://([^:]+):([^@]+)@", "://$1:****@");
    }
}
