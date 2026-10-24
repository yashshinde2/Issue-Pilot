package com.issuepilot.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.FeedbackEvent;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Repository
public class FeedbackRepository {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackRepository.class);

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public FeedbackRepository(MongoTemplate mongoTemplate, ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    private Document cleanDoc(Document doc) {
        if (doc == null) return null;
        Document copy = new Document(doc);
        copy.remove("_id");
        return copy;
    }

    public Map<String, Object> recordFeedback(FeedbackEvent event) {
        if (mongoTemplate == null) {
            throw new IllegalStateException("MongoDB Atlas unavailable.");
        }
        String eventType = event.getEventType();
        if (eventType == null || (!eventType.equals("accepted") && !eventType.equals("rejected") && !eventType.equals("completed"))) {
            throw new IllegalArgumentException("Invalid event_type: " + eventType);
        }

        String nowIso = event.getEventTimestamp();
        if (nowIso == null || nowIso.isBlank()) {
            nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            event.setEventTimestamp(nowIso);
        }

        Map<String, Object> map = objectMapper.convertValue(event, new TypeReference<>() {});
        Document doc = new Document(map);
        mongoTemplate.getCollection("feedback").insertOne(doc);

        // Update contributor profile stats
        String userId = event.getUserId();
        if (userId != null && !userId.isBlank()) {
            String fieldToInc = eventType + "_count";
            Document incDoc = new Document(fieldToInc, 1);
            Document setDoc = new Document("has_feedback_history", true).append("last_active_at", nowIso);

            mongoTemplate.getCollection("users").updateOne(
                    new Document("user_id", userId),
                    new Document("$inc", incDoc).append("$set", setDoc)
            );
        }

        return cleanDoc(doc);
    }

    public Map<String, Object> getUserFeedback(String userId, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user_id", userId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("accepted", 0);
        counts.put("rejected", 0);
        counts.put("completed", 0);

        if (mongoTemplate == null || userId == null) {
            result.put("counts", counts);
            result.put("events", Collections.emptyList());
            return result;
        }

        counts.put("accepted", (int) mongoTemplate.getCollection("feedback").countDocuments(
                new Document("user_id", userId).append("event_type", "accepted")
        ));
        counts.put("rejected", (int) mongoTemplate.getCollection("feedback").countDocuments(
                new Document("user_id", userId).append("event_type", "rejected")
        ));
        counts.put("completed", (int) mongoTemplate.getCollection("feedback").countDocuments(
                new Document("user_id", userId).append("event_type", "completed")
        ));
        result.put("counts", counts);

        List<Map<String, Object>> events = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("feedback")
                .find(new Document("user_id", userId))
                .sort(new Document("event_timestamp", -1))
                .limit(limit);

        for (Document d : cursor) {
            events.add(cleanDoc(d));
        }
        result.put("events", events);

        return result;
    }
}
