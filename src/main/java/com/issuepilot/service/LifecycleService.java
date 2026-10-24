package com.issuepilot.service;

import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.JobRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class LifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(LifecycleService.class);

    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final IssueRepository issueRepository;
    private final JobRepository jobRepository;
    private final MongoTemplate mongoTemplate;

    public LifecycleService(
            MongoConfig mongoConfig,
            AppProperties properties,
            IssueRepository issueRepository,
            JobRepository jobRepository,
            MongoTemplate mongoTemplate
    ) {
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.issueRepository = issueRepository;
        this.jobRepository = jobRepository;
        this.mongoTemplate = mongoTemplate;
    }

    public Map<String, Object> runCleanup(Integer olderThanDays, boolean force) {
        String jobType = "cleanup";
        String jobId = null;

        if (mongoConfig.isConnected()) {
            jobId = jobRepository.acquireJobLock(jobType);
            if (jobId == null && !force) {
                Map<String, Object> locked = new LinkedHashMap<>();
                locked.put("status", "locked");
                locked.put("message", "Another lifecycle cleanup job is currently in progress.");
                locked.put("job_id", null);
                return locked;
            } else if (jobId == null && force) {
                jobId = "cleanup_forced_" + System.currentTimeMillis();
            }
        } else {
            jobId = "cleanup_offline_" + System.currentTimeMillis();
        }

        long t0 = System.currentTimeMillis();
        int cutoffDays = (olderThanDays != null && olderThanDays > 0) ? olderThanDays : properties.getOlderWindowDays();

        if (!mongoConfig.isConnected()) {
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("job_id", jobId);
            failed.put("status", "failed");
            failed.put("message", "MongoDB Atlas connection unavailable.");
            return failed;
        }

        String cutoffDate = DateTimeFormatter.ISO_INSTANT.format(Instant.now().minus(cutoffDays, ChronoUnit.DAYS));
        Document query = new Document("state", "open")
                .append("stale_status", new Document("$in", Arrays.asList("active", "needs_refresh")))
                .append("$or", Arrays.asList(
                        new Document("last_seen_at", new Document("$lt", cutoffDate)),
                        new Document("created_at", new Document("$lt", cutoffDate))
                ));

        List<String> staleIds = new ArrayList<>();
        if (mongoTemplate != null) {
            var cursor = mongoTemplate.getCollection("issues").find(query);
            for (Document d : cursor) {
                String id = d.getString("id");
                if (id != null) staleIds.add(id);
            }
        }

        long markedCount = 0;
        if (!staleIds.isEmpty()) {
            markedCount = issueRepository.markIssuesStale(staleIds);
            logger.info("Marked {} candidate issues as stale (> {} days old).", markedCount, cutoffDays);
        }

        long elapsedSec = (System.currentTimeMillis() - t0) / 1000;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("job_id", jobId);
        summary.put("job_type", "cleanup");
        summary.put("status", "completed");
        summary.put("issues_evaluated", staleIds.size());
        summary.put("issues_marked_stale", markedCount);
        summary.put("cutoff_days", cutoffDays);
        summary.put("duration_seconds", elapsedSec);

        jobRepository.releaseJobLock(jobId, "completed", summary);
        return summary;
    }
}
