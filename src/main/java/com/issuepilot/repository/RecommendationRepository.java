package com.issuepilot.repository;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Repository
public class RecommendationRepository {

    private final MongoTemplate mongoTemplate;

    public RecommendationRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private Document cleanDoc(Document doc) {
        if (doc == null) return null;
        Document copy = new Document(doc);
        copy.remove("_id");
        return copy;
    }

    public List<Map<String, Object>> saveRecommendations(
            String userId,
            List<Map<String, Object>> recommendations,
            String algorithmVersion,
            String runId,
            List<Map<String, Object>> auditedCandidates
    ) {
        if (mongoTemplate == null) return Collections.emptyList();

        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        List<Document> docsToInsert = new ArrayList<>();

        List<Map<String, Object>> candidatesToSave = (auditedCandidates != null && !auditedCandidates.isEmpty())
                ? auditedCandidates
                : recommendations;

        if (candidatesToSave != null) {
            for (Map<String, Object> c : candidatesToSave) {
                Document doc = new Document();
                doc.append("user_id", userId);
                doc.append("issue_id", c.get("id"));
                doc.append("run_id", runId);
                doc.append("native_id", c.get("native_id"));
                doc.append("repository_full_name", c.get("repository_full_name"));
                doc.append("rank", c.get("rank"));
                doc.append("original_rank", c.get("original_rank"));
                doc.append("original_score", c.get("original_score"));
                doc.append("delivered_rank", c.get("delivered_rank"));
                doc.append("final_score", c.get("final_score"));
                doc.append("scores", c.get("scores"));
                doc.append("effective_weights", c.get("effective_weights"));
                doc.append("weighted_contributions", c.get("weighted_contributions"));
                doc.append("missing_components", c.get("missing_components"));
                doc.append("explanation", c.get("explanation"));
                doc.append("verification_status", c.getOrDefault("verification_status", "unverified"));
                doc.append("verified_at", c.get("verified_at"));
                doc.append("verification_notes", c.get("verification_notes"));
                doc.append("decision", c.getOrDefault("decision", "included"));
                doc.append("exclusion_reason", c.get("exclusion_reason"));
                doc.append("response_metadata", c.get("response_metadata"));
                doc.append("algorithm_version", algorithmVersion);
                doc.append("generated_at", nowIso);

                // Preserve original candidate metadata
                for (Map.Entry<String, Object> entry : c.entrySet()) {
                    if (!doc.containsKey(entry.getKey())) {
                        doc.append(entry.getKey(), entry.getValue());
                    }
                }
                docsToInsert.add(doc);
            }
        }

        if (!docsToInsert.isEmpty()) {
            // Delete any existing records for the same run_id to avoid duplication
            mongoTemplate.getCollection("recommendations").deleteMany(new Document("run_id", runId));
            mongoTemplate.getCollection("recommendations").insertMany(docsToInsert);
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        for (Document d : docsToInsert) {
            saved.add(cleanDoc(d));
        }
        return saved;
    }

    public List<Map<String, Object>> getUserRecommendations(String userId, int limit) {
        if (mongoTemplate == null || userId == null) return Collections.emptyList();
        Document query = new Document("user_id", userId).append("decision", "included");
        List<Map<String, Object>> results = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("recommendations")
                .find(query)
                .sort(new Document("generated_at", -1))
                .limit(limit);

        for (Document d : cursor) {
            results.add(cleanDoc(d));
        }
        return results;
    }

    public List<Map<String, Object>> getRunRecommendations(String runId) {
        if (mongoTemplate == null || runId == null) return Collections.emptyList();
        List<Map<String, Object>> results = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("recommendations")
                .find(new Document("run_id", runId))
                .sort(new Document("rank", 1));

        for (Document d : cursor) {
            results.add(cleanDoc(d));
        }
        return results;
    }
}
