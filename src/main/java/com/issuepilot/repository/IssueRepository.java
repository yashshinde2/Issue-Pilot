package com.issuepilot.repository;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Repository
public class IssueRepository {

    private final MongoOperations mongoTemplate;

    public IssueRepository(MongoOperations mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    private Document cleanDoc(Document doc) {
        if (doc == null) {
            return null;
        }
        Document copy = new Document(doc);
        copy.remove("_id");
        return copy;
    }

    public Map<String, Object> getIssueById(String id) {
        if (mongoTemplate == null || id == null) return null;
        Document doc = mongoTemplate.getCollection("issues").find(new Document("id", id)).first();
        return cleanDoc(doc);
    }

    public Map<String, Object> findIssue(String source, String platformIssueId) {
        if (mongoTemplate == null || platformIssueId == null || platformIssueId.isBlank()) return null;

        var collection = mongoTemplate.getCollection("issues");

        // 1. Direct match by id
        Document doc = collection.find(new Document("id", platformIssueId)).first();
        if (doc != null) return cleanDoc(doc);

        if (source != null && !source.isBlank()) {
            String normSource = source.toLowerCase().trim();
            // 2. Composite ID: source_platformIssueId
            doc = collection.find(new Document("id", normSource + "_" + platformIssueId)).first();
            if (doc != null) return cleanDoc(doc);

            // 3. Search by source and native_id, source_issue_id, or issue_number
            List<Document> orFilters = new ArrayList<>();
            orFilters.add(new Document("id", platformIssueId));
            orFilters.add(new Document("native_id", platformIssueId));
            orFilters.add(new Document("source_issue_id", platformIssueId));
            try {
                int issueNum = Integer.parseInt(platformIssueId);
                orFilters.add(new Document("issue_number", issueNum));
            } catch (NumberFormatException ignored) {}

            Document query = new Document("source", normSource).append("$or", orFilters);
            doc = collection.find(query).first();
            if (doc != null) return cleanDoc(doc);
        }

        return null;
    }

    public Map<String, Object> upsertIssue(Map<String, Object> issueMap, boolean preserveUnsupplied) {
        if (mongoTemplate == null) {
            throw new IllegalStateException("MongoDB Atlas connection unavailable.");
        }

        Map<String, Object> map = new HashMap<>(issueMap);
        map.remove("_id");

        String source = map.getOrDefault("source", "github").toString().toLowerCase().trim();
        map.put("source", source);

        String repoFullName = map.get("repository_full_name") != null ? map.get("repository_full_name").toString().trim() : "";
        Object issueNumObj = map.get("issue_number");
        int issueNum = issueNumObj instanceof Number ? ((Number) issueNumObj).intValue() : 0;

        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        map.put("updated_at", nowIso);
        map.put("last_seen_at", nowIso);

        String issueId = (String) map.get("id");
        if (issueId == null || issueId.isBlank()) {
            if ("bugzilla".equalsIgnoreCase(source)) {
                String sourceInstance = map.getOrDefault("source_instance", "bugzilla").toString()
                        .replace("/", "_").replace(":", "_").replace(".", "_");
                if (issueNum > 0) {
                    issueId = "bz_" + sourceInstance + "_" + issueNum;
                }
            } else if (!repoFullName.isBlank() && issueNum > 0) {
                issueId = source + "_" + repoFullName.replace("/", "_") + "_" + issueNum;
            }
            if (issueId != null && !issueId.isBlank()) {
                map.put("id", issueId);
            }
        }

        if (issueId == null || issueId.isBlank()) {
            throw new IllegalArgumentException("Cannot upsert issue without verified canonical identity (missing id/source/number).");
        }

        Document filter = new Document("id", issueId);

        Object existingCreatedAt = map.remove("created_at");
        Object existingFirstSeen = map.remove("first_seen_at");

        Document setDoc = new Document(map);
        Document setOnInsert = new Document("created_at", existingCreatedAt != null ? existingCreatedAt : nowIso)
                .append("first_seen_at", existingFirstSeen != null ? existingFirstSeen : nowIso);

        Document updateDoc = new Document("$set", setDoc).append("$setOnInsert", setOnInsert);

        mongoTemplate.getCollection("issues").updateOne(
                filter,
                updateDoc,
                new com.mongodb.client.model.UpdateOptions().upsert(true)
        );

        Document saved = mongoTemplate.getCollection("issues").find(filter).first();
        return cleanDoc(saved);
    }

    public List<Map<String, Object>> queryIssues(
            String state,
            Boolean isAssigned,
            String technology,
            Double minDifficulty,
            Double maxDifficulty,
            String search,
            String source,
            int limit,
            int skip
    ) {
        if (mongoTemplate == null) return Collections.emptyList();

        Document query = new Document();

        if (source != null && !source.isBlank() && !source.equalsIgnoreCase("all")) {
            query.append("source", source.toLowerCase().trim());
        }

        if (state != null && !state.isBlank() && !state.equalsIgnoreCase("all")) {
            query.append("state", state.toLowerCase().trim());
            if (state.equalsIgnoreCase("open")) {
                query.append("stale_status", new Document("$nin", Arrays.asList("closed", "unavailable")));
            }
        }

        if (isAssigned != null) {
            query.append("is_assigned", isAssigned);
        }

        // Eligibility rule: issue_url must not be empty
        query.append("issue_url", new Document("$exists", true).append("$ne", ""));

        if (technology != null && !technology.isBlank()) {
            Pattern pat = Pattern.compile("^" + Pattern.quote(technology.trim()) + "$", Pattern.CASE_INSENSITIVE);
            List<Document> orList = Arrays.asList(
                    new Document("detected_technologies", pat),
                    new Document("technologies.technology", pat)
            );
            query.append("$or", orList);
        }

        Document diffConditions = new Document();
        if (minDifficulty != null) {
            diffConditions.append("$gte", minDifficulty);
        }
        if (maxDifficulty != null) {
            diffConditions.append("$lte", maxDifficulty);
        }
        if (!diffConditions.isEmpty()) {
            query.append("difficulty_evidence.technical_complexity", diffConditions);
        }

        if (search != null && !search.isBlank()) {
            Pattern searchPat = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
            List<Document> searchOr = Arrays.asList(
                    new Document("title", searchPat),
                    new Document("repository_full_name", searchPat)
            );
            if (query.containsKey("$or")) {
                // If there's already an $or, wrap in $and
                query.append("$and", Arrays.asList(new Document("$or", searchOr)));
            } else {
                query.append("$or", searchOr);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("issues")
                .find(query)
                .sort(new Document("updated_at", -1))
                .skip(skip)
                .limit(limit);

        for (Document d : cursor) {
            results.add(cleanDoc(d));
        }
        return results;
    }

    public List<Map<String, Object>> queryIssuesMultiTech(
            String state,
            Boolean isAssigned,
            Collection<String> technologies,
            Double minDifficulty,
            Double maxDifficulty,
            String search,
            String source,
            int limit,
            int skip
    ) {
        if (mongoTemplate == null) return Collections.emptyList();

        Document query = new Document();

        if (source != null && !source.isBlank() && !source.equalsIgnoreCase("all")) {
            query.append("source", source.toLowerCase().trim());
        }

        if (state != null && !state.isBlank() && !state.equalsIgnoreCase("all")) {
            query.append("state", state.toLowerCase().trim());
            if (state.equalsIgnoreCase("open")) {
                query.append("stale_status", new Document("$nin", Arrays.asList("closed", "unavailable")));
            }
        }

        if (isAssigned != null) {
            query.append("is_assigned", isAssigned);
        }

        query.append("issue_url", new Document("$exists", true).append("$ne", ""));

        if (technologies != null && !technologies.isEmpty()) {
            List<Document> orList = new ArrayList<>();
            for (String tech : technologies) {
                if (tech != null && !tech.isBlank()) {
                    Pattern pat = Pattern.compile("^" + Pattern.quote(tech.trim()) + "$", Pattern.CASE_INSENSITIVE);
                    orList.add(new Document("detected_technologies", pat));
                    orList.add(new Document("technologies.technology", pat));
                }
            }
            if (!orList.isEmpty()) {
                query.append("$or", orList);
            }
        }

        Document diffConditions = new Document();
        if (minDifficulty != null) {
            diffConditions.append("$gte", minDifficulty);
        }
        if (maxDifficulty != null) {
            diffConditions.append("$lte", maxDifficulty);
        }
        if (!diffConditions.isEmpty()) {
            query.append("difficulty_evidence.technical_complexity", diffConditions);
        }

        if (search != null && !search.isBlank()) {
            Pattern searchPat = Pattern.compile(Pattern.quote(search.trim()), Pattern.CASE_INSENSITIVE);
            List<Document> searchOr = Arrays.asList(
                    new Document("title", searchPat),
                    new Document("repository_full_name", searchPat)
            );
            if (query.containsKey("$or")) {
                query.append("$and", Arrays.asList(new Document("$or", searchOr)));
            } else {
                query.append("$or", searchOr);
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("issues")
                .find(query)
                .sort(new Document("updated_at", -1))
                .skip(skip)
                .limit(limit);

        for (Document d : cursor) {
            results.add(cleanDoc(d));
        }
        return results;
    }

    public long countIssues() {
        if (mongoTemplate == null) return 0;
        return mongoTemplate.getCollection("issues").countDocuments();
    }

    public long countBySource(String source) {
        if (mongoTemplate == null || source == null) return 0;
        return mongoTemplate.getCollection("issues").countDocuments(new Document("source", source.toLowerCase().trim()));
    }

    public long countByRepository(String repoFullName) {
        if (mongoTemplate == null || repoFullName == null) return 0;
        Document query = new Document("repository_full_name", repoFullName)
                .append("state", "open")
                .append("stale_status", new Document("$nin", Arrays.asList("closed", "unavailable")));
        return mongoTemplate.getCollection("issues").countDocuments(query);
    }

    public boolean deleteIssue(String id) {
        if (mongoTemplate == null || id == null) return false;
        var res = mongoTemplate.getCollection("issues").deleteOne(new Document("id", id));
        return res.getDeletedCount() > 0;
    }

    public List<Map<String, Object>> getCandidatesForRefresh(int limit, int maxAgeDays) {
        if (mongoTemplate == null) return Collections.emptyList();
        String cutoffDate = DateTimeFormatter.ISO_INSTANT.format(Instant.now().minus(maxAgeDays, ChronoUnit.DAYS));

        Document query = new Document("state", "open")
                .append("stale_status", new Document("$in", Arrays.asList("active", "needs_refresh")))
                .append("$or", Arrays.asList(
                        new Document("stale_status", "needs_refresh"),
                        new Document("last_seen_at", new Document("$lt", cutoffDate)),
                        new Document("last_seen_at", null),
                        new Document("last_seen_at", new Document("$exists", false))
                ));

        List<Map<String, Object>> results = new ArrayList<>();
        var cursor = mongoTemplate.getCollection("issues")
                .find(query)
                .sort(new Document("stale_status", -1).append("last_seen_at", 1))
                .limit(limit);

        for (Document d : cursor) {
            results.add(cleanDoc(d));
        }
        return results;
    }

    public boolean markIssueClosed(String issueId, String closedAt) {
        if (mongoTemplate == null || issueId == null) return false;
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String effectiveClosedAt = (closedAt != null && !closedAt.isBlank()) ? closedAt : nowIso;

        Document update = new Document("$set", new Document("state", "closed")
                .append("stale_status", "closed")
                .append("refresh_status", "closed")
                .append("closed_at", effectiveClosedAt)
                .append("updated_at", nowIso));

        var res = mongoTemplate.getCollection("issues").updateOne(new Document("id", issueId), update);
        return res.getModifiedCount() > 0;
    }

    public boolean markIssueUnavailable(String issueId) {
        if (mongoTemplate == null || issueId == null) return false;
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        Document update = new Document("$set", new Document("stale_status", "unavailable")
                .append("refresh_status", "unavailable")
                .append("updated_at", nowIso));

        var res = mongoTemplate.getCollection("issues").updateOne(new Document("id", issueId), update);
        return res.getModifiedCount() > 0;
    }

    public long markIssuesStale(List<String> issueIds) {
        if (mongoTemplate == null || issueIds == null || issueIds.isEmpty()) return 0;
        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        Document update = new Document("$set", new Document("stale_status", "stale")
                .append("refresh_status", "stale")
                .append("updated_at", nowIso));

        var res = mongoTemplate.getCollection("issues").updateMany(
                new Document("id", new Document("$in", issueIds)),
                update
        );
        return res.getModifiedCount();
    }

    public Map<String, Object> getPoolMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        if (mongoTemplate == null) return metrics;

        long total = countIssues();
        long open = mongoTemplate.getCollection("issues").countDocuments(
                new Document("state", "open").append("stale_status", new Document("$nin", Arrays.asList("closed", "unavailable")))
        );
        long closed = mongoTemplate.getCollection("issues").countDocuments(new Document("state", "closed"));

        metrics.put("total_candidates", total);
        metrics.put("open_candidates", open);
        metrics.put("closed_candidates", closed);

        Map<String, Long> sourceBreakdown = new HashMap<>();
        for (String src : Arrays.asList("github", "gitlab", "codeberg", "bugzilla")) {
            sourceBreakdown.put(src, countBySource(src));
        }
        metrics.put("source_breakdown", sourceBreakdown);

        Map<String, Long> statusBreakdown = new HashMap<>();
        for (String st : Arrays.asList("active", "needs_refresh", "stale", "closed", "unavailable")) {
            long c = mongoTemplate.getCollection("issues").countDocuments(new Document("stale_status", st));
            statusBreakdown.put(st, c);
        }
        metrics.put("status_breakdown", statusBreakdown);

        var repos = mongoTemplate.getCollection("issues").distinct("repository_full_name", String.class);
        List<String> repoList = new ArrayList<>();
        for (String r : repos) {
            if (r != null && !r.isBlank()) repoList.add(r);
        }
        metrics.put("unique_repositories", repoList.size());
        metrics.put("top_repositories", repoList.subList(0, Math.min(10, repoList.size())));

        return metrics;
    }
}
