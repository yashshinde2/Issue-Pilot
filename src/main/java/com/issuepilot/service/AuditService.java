package com.issuepilot.service;

import com.issuepilot.config.MongoConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    private final MongoConfig mongoConfig;
    private final MongoTemplate mongoTemplate;

    public AuditService(MongoConfig mongoConfig, MongoTemplate mongoTemplate) {
        this.mongoConfig = mongoConfig;
        this.mongoTemplate = mongoTemplate;
    }

    public Map<String, Object> auditDatabase() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        report.put("connected", mongoConfig.isConnected());

        if (!mongoConfig.isConnected() || mongoTemplate == null) {
            report.put("status", "disconnected");
            report.put("message", "MongoDB Atlas connection unavailable.");
            return report;
        }

        var database = mongoTemplate.getDb();
        report.put("database_name", database.getName());

        // Collection counts
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String col : List.of("issues", "users", "recommendations", "feedback", "jobs")) {
            try {
                counts.put(col, database.getCollection(col).countDocuments());
            } catch (Exception e) {
                counts.put(col, -1L);
            }
        }
        report.put("collection_counts", counts);

        // Inspect indexes on issues
        List<Map<String, Object>> issueIndexes = new ArrayList<>();
        try {
            for (Document idx : database.getCollection("issues").listIndexes()) {
                Map<String, Object> idxInfo = new LinkedHashMap<>();
                idxInfo.put("name", idx.get("name"));
                idxInfo.put("key", idx.get("key"));
                idxInfo.put("unique", idx.getOrDefault("unique", false));
                idxInfo.put("sparse", idx.getOrDefault("sparse", false));
                issueIndexes.add(idxInfo);
            }
        } catch (Exception ignored) {}
        report.put("issue_indexes", issueIndexes);

        // Quality check on issues
        Map<String, Integer> bySource = new HashMap<>();
        Map<String, Integer> byState = new HashMap<>();
        Map<String, Integer> byStaleStatus = new HashMap<>();
        int missingRequiredFields = 0;
        int invalidUrls = 0;

        try {
            var cursor = database.getCollection("issues").find();
            for (Document d : cursor) {
                String src = d.getString("source");
                if (src != null) bySource.put(src, bySource.getOrDefault(src, 0) + 1);

                String state = d.getString("state");
                if (state != null) byState.put(state, byState.getOrDefault(state, 0) + 1);

                String stale = d.getString("stale_status");
                if (stale != null) byStaleStatus.put(stale, byStaleStatus.getOrDefault(stale, 0) + 1);

                if (d.get("id") == null || d.get("issue_number") == null || d.get("repository_full_name") == null) {
                    missingRequiredFields++;
                }

                String url = d.getString("issue_url");
                if (url == null || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                    invalidUrls++;
                }
            }
        } catch (Exception ignored) {}

        report.put("breakdown_by_source", bySource);
        report.put("breakdown_by_state", byState);
        report.put("breakdown_by_stale_status", byStaleStatus);

        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("missing_required_fields", missingRequiredFields);
        integrity.put("invalid_urls", invalidUrls);
        integrity.put("healthy", missingRequiredFields == 0 && invalidUrls == 0);
        report.put("integrity_check", integrity);

        return report;
    }
}
