package com.issuepilot.repository;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Repository
public class JobRepository {

    private final MongoTemplate mongoTemplate;

    public JobRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private Document cleanDoc(Document doc) {
        if (doc == null) return null;
        Document copy = new Document(doc);
        copy.remove("_id");
        return copy;
    }

    public String acquireJobLock(String jobType) {
        if (mongoTemplate == null) return null;

        String oneHourAgo = DateTimeFormatter.ISO_INSTANT.format(Instant.now().minus(60, ChronoUnit.MINUTES));
        Document activeLockQuery = new Document("status", "in_progress")
                .append("start_time", new Document("$gt", oneHourAgo));

        if (jobType != null && !jobType.isBlank()) {
            activeLockQuery.append("job_type", jobType);
        }

        Document runningJob = mongoTemplate.getCollection("jobs").find(activeLockQuery).first();
        if (runningJob != null) {
            return null;
        }

        String jobId = jobType + "_" + System.currentTimeMillis();
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        Document newJob = new Document("job_id", jobId)
                .append("job_type", jobType)
                .append("start_time", nowIso)
                .append("status", "in_progress")
                .append("checkpoints", new Document())
                .append("queries_executed", new ArrayList<String>())
                .append("issues_discovered", 0)
                .append("issues_inserted", 0)
                .append("issues_updated", 0)
                .append("issues_skipped", 0)
                .append("issues_marked_stale", 0)
                .append("issues_marked_closed", 0)
                .append("errors", new ArrayList<String>())
                .append("execution_metadata", new Document())
                .append("per_source_summary", new Document());

        mongoTemplate.getCollection("jobs").insertOne(newJob);
        return jobId;
    }

    public boolean releaseJobLock(String jobId, String status, Map<String, Object> updates) {
        if (mongoTemplate == null || jobId == null) return false;
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        Document setDoc = new Document("finish_time", nowIso)
                .append("status", status != null ? status : "completed");

        if (updates != null) {
            for (Map.Entry<String, Object> e : updates.entrySet()) {
                setDoc.append(e.getKey(), e.getValue());
            }
        }

        var res = mongoTemplate.getCollection("jobs").updateOne(
                new Document("job_id", jobId),
                new Document("$set", setDoc)
        );
        return res.getModifiedCount() > 0;
    }

    public boolean updateCheckpoint(String jobId, Map<String, Object> checkpointData) {
        if (mongoTemplate == null || jobId == null || checkpointData == null) return false;
        Document setDoc = new Document();
        for (Map.Entry<String, Object> e : checkpointData.entrySet()) {
            setDoc.append("checkpoints." + e.getKey(), e.getValue());
        }
        var res = mongoTemplate.getCollection("jobs").updateOne(
                new Document("job_id", jobId),
                new Document("$set", setDoc)
        );
        return res.getModifiedCount() > 0;
    }

    public Map<String, Object> getJob(String jobId) {
        if (mongoTemplate == null || jobId == null) return null;
        Document doc = mongoTemplate.getCollection("jobs").find(new Document("job_id", jobId)).first();
        return cleanDoc(doc);
    }

    public Map<String, Object> getLatestJob() {
        if (mongoTemplate == null) return null;
        Document doc = mongoTemplate.getCollection("jobs")
                .find()
                .sort(new Document("start_time", -1))
                .first();
        return cleanDoc(doc);
    }

    public List<Map<String, Object>> listJobs(int limit, int skip, String jobType) {
        if (mongoTemplate == null) return Collections.emptyList();
        Document query = new Document();
        if (jobType != null && !jobType.isBlank()) {
            query.append("job_type", jobType);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("jobs")
                .find(query)
                .sort(new Document("start_time", -1))
                .skip(skip)
                .limit(limit);

        for (Document d : cursor) {
            results.add(cleanDoc(d));
        }
        return results;
    }
}
