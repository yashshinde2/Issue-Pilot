package com.issuepilot.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.ContributorProfile;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Repository
public class UserRepository {

    private final MongoOperations mongoTemplate;
    private final ObjectMapper objectMapper;

    public UserRepository(MongoOperations mongoTemplate, ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    private Document cleanDoc(Document doc) {
        if (doc == null) {
            return null;
        }
        Document copy = new Document(doc);
        copy.remove("_id");
        return copy;
    }

    public Map<String, Object> getUser(String userId) {
        if (mongoTemplate == null) return null;
        Document doc = mongoTemplate.getCollection("users").find(new Document("user_id", userId)).first();
        return cleanDoc(doc);
    }

    public Map<String, Object> createUser(ContributorProfile profile) {
        if (mongoTemplate == null) {
            throw new IllegalStateException("MongoDB Atlas connection unavailable.");
        }
        String userId = profile.getUserId();
        if (getUser(userId) != null) {
            throw new IllegalArgumentException("User with ID '" + userId + "' already exists.");
        }

        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        profile.setCreatedAt(nowIso);
        profile.setUpdatedAt(nowIso);

        Map<String, Object> map = objectMapper.convertValue(profile, new TypeReference<>() {});
        Document doc = new Document(map);
        mongoTemplate.getCollection("users").insertOne(doc);
        return cleanDoc(doc);
    }

    public Map<String, Object> updateUser(String userId, Map<String, Object> updates) {
        if (mongoTemplate == null) {
            throw new IllegalStateException("MongoDB Atlas connection unavailable.");
        }
        if (getUser(userId) == null) {
            return null;
        }

        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        Document updateFields = new Document(updates);
        updateFields.remove("_id");
        updateFields.remove("user_id");
        updateFields.put("updated_at", nowIso);

        mongoTemplate.getCollection("users").updateOne(
                new Document("user_id", userId),
                new Document("$set", updateFields)
        );

        return getUser(userId);
    }

    public Map<String, Object> upsertUser(ContributorProfile profile) {
        if (mongoTemplate == null) {
            throw new IllegalStateException("MongoDB Atlas connection unavailable.");
        }
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        Map<String, Object> map = objectMapper.convertValue(profile, new TypeReference<>() {});
        map.remove("user_id");
        map.remove("_id");
        map.remove("created_at");
        map.put("updated_at", nowIso);

        Document setDoc = new Document(map);
        Document setOnInsert = new Document("created_at", nowIso);

        Document updateDoc = new Document("$set", setDoc).append("$setOnInsert", setOnInsert);

        mongoTemplate.getCollection("users").updateOne(
                new Document("user_id", profile.getUserId()),
                updateDoc,
                new com.mongodb.client.model.UpdateOptions().upsert(true)
        );

        return getUser(profile.getUserId());
    }

    public List<Map<String, Object>> listUsers(int limit, int skip) {
        if (mongoTemplate == null) return Collections.emptyList();
        List<Map<String, Object>> results = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("users")
                .find()
                .sort(new Document("updated_at", -1))
                .skip(skip)
                .limit(limit);

        for (Document d : cursor) {
            results.add(cleanDoc(d));
        }
        return results;
    }

    public boolean deleteUser(String userId) {
        if (mongoTemplate == null) return false;
        var res = mongoTemplate.getCollection("users").deleteOne(new Document("user_id", userId));
        return res.getDeletedCount() > 0;
    }

    public List<ContributorProfile> findActiveUsers(Instant since, int limit) {
        if (mongoTemplate == null) return Collections.emptyList();
        String sinceIso = DateTimeFormatter.ISO_INSTANT.format(since);

        Document filter = new Document("$or", List.of(
                new Document("last_active_at", new Document("$gte", sinceIso)),
                new Document("$and", List.of(
                        new Document("last_active_at", null),
                        new Document("updated_at", new Document("$gte", sinceIso))
                ))
        ));

        List<ContributorProfile> activeProfiles = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("users")
                .find(filter)
                .sort(new Document("updated_at", -1))
                .limit(limit);

        for (Document d : cursor) {
            Map<String, Object> clean = cleanDoc(d);
            if (clean != null) {
                try {
                    ContributorProfile p = objectMapper.convertValue(clean, ContributorProfile.class);
                    activeProfiles.add(p);
                } catch (Exception ignored) {}
            }
        }
        return activeProfiles;
    }

    public Set<String> getActiveTechnologyPreferences(Instant since, int limit) {
        List<ContributorProfile> users = findActiveUsers(since, limit);
        Set<String> aggregated = new LinkedHashSet<>();
        for (ContributorProfile user : users) {
            if (user != null) {
                aggregated.addAll(user.getNormalizedTechnologyPreferences());
            }
        }
        return Collections.unmodifiableSet(aggregated);
    }
}
