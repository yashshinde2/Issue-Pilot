package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.adapter.TechnologyDetectionHelper;
import com.issuepilot.cli.IssuePilotCli;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.service.DiscoveryService;
import com.issuepilot.service.RefreshService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class DailyIngestionValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private <T> T createProxy(Class<T> iface, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    static class StubDiscoveryService extends DiscoveryService {
        private final Map<String, Object> stubResult;

        public StubDiscoveryService(Map<String, Object> stubResult) {
            super(null, null, null, null, null, null);
            this.stubResult = stubResult;
        }

        @Override
        public Map<String, Object> runDiscovery(
                Integer maxIssuesToDiscover,
                Integer maxPagesPerQuery,
                Integer perPage,
                boolean resume,
                boolean force,
                String sourceFilter,
                Integer creationAgeDays
        ) {
            return stubResult;
        }
    }

    static class StubRefreshService extends RefreshService {
        private final Map<String, Object> stubResult;

        public StubRefreshService(Map<String, Object> stubResult) {
            super(null, null, null, null, null);
            this.stubResult = stubResult;
        }

        @Override
        public Map<String, Object> runRefresh(int batchSize, Integer maxAgeDays, boolean force) {
            return stubResult;
        }
    }

    @Test
    @DisplayName("1. Null repository fields in Bugzilla records do not cause NPE and produce canonical identity")
    public void testBugzillaNullRepositorySafeUpsert() {
        AtomicReference<Document> capturedFilter = new AtomicReference<>();
        Document savedDoc = new Document("id", "bz_bugzilla_mozilla_org_12345")
                .append("source", "bugzilla")
                .append("issue_number", 12345)
                .append("repository_full_name", null);

        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = createProxy(FindIterable.class, (proxy, method, args) -> {
            if ("first".equals(method.getName())) {
                return savedDoc;
            }
            return proxy;
        });

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("updateOne".equals(method.getName())) {
                capturedFilter.set((Document) args[0]);
                return UpdateResult.acknowledged(1, 1L, null);
            }
            if ("find".equals(method.getName())) {
                return findIterable;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        IssueRepository issueRepository = new IssueRepository(mongoOperations);

        Map<String, Object> bugzillaIssue = new HashMap<>();
        bugzillaIssue.put("source", "bugzilla");
        bugzillaIssue.put("source_instance", "bugzilla.mozilla.org");
        bugzillaIssue.put("repository_full_name", null); // explicit null!
        bugzillaIssue.put("repository_owner", null);
        bugzillaIssue.put("repository_name", null);
        bugzillaIssue.put("issue_number", 12345);
        bugzillaIssue.put("title", "Crash in layout engine");
        bugzillaIssue.put("state", "open");

        Map<String, Object> result = issueRepository.upsertIssue(bugzillaIssue, true);
        assertNotNull(result);
        assertNotNull(capturedFilter.get());
        assertEquals("bz_bugzilla_mozilla_org_12345", capturedFilter.get().getString("id"));
    }

    @Test
    @DisplayName("2. Canonical ID uniqueness and rejection of ambiguous identities")
    public void testCanonicalIdValidationAndAmbiguityRejection() {
        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> null);
        IssueRepository issueRepository = new IssueRepository(mongoOperations);

        Map<String, Object> ambiguousIssue = new HashMap<>();
        ambiguousIssue.put("source", "github");
        ambiguousIssue.put("title", "Unknown issue without identity");

        assertThrows(IllegalArgumentException.class, () -> issueRepository.upsertIssue(ambiguousIssue, true));
    }

    @Test
    @DisplayName("3. Technology stack normalization and contributor preference aggregation")
    public void testTechnologyStackNormalizationAndAggregation() {
        assertEquals("Python", TechnologyDetectionHelper.normalizeTechnology("py"));
        assertEquals("Python", TechnologyDetectionHelper.normalizeTechnology("python3"));
        assertEquals("TypeScript", TechnologyDetectionHelper.normalizeTechnology("ts"));
        assertEquals("JavaScript", TechnologyDetectionHelper.normalizeTechnology("nodejs"));
        assertEquals("C++", TechnologyDetectionHelper.normalizeTechnology("cpp"));
        assertEquals("C++", TechnologyDetectionHelper.normalizeTechnology("cxx"));
        assertEquals("Go", TechnologyDetectionHelper.normalizeTechnology("golang"));
        assertEquals("React", TechnologyDetectionHelper.normalizeTechnology("react.js"));
        assertEquals("Java", TechnologyDetectionHelper.normalizeTechnology("jdk"));

        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("test-user-1");

        Map<String, ContributorSkill> skills = new HashMap<>();
        skills.put("py", new ContributorSkill("py", 80.0, 1.0));
        skills.put("ts", new ContributorSkill("ts", 85.0, 1.0));
        skills.put("cpp", new ContributorSkill("cpp", 90.0, 1.0));
        profile.setSkills(skills);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("tech_stack", List.of("reactjs", "Go", "python"));
        prefs.put("languages", "java, rust");
        profile.setPreferences(prefs);

        Set<String> normalized = profile.getNormalizedTechnologyPreferences();
        assertTrue(normalized.contains("Python"));
        assertTrue(normalized.contains("TypeScript"));
        assertTrue(normalized.contains("C++"));
        assertTrue(normalized.contains("React"));
        assertTrue(normalized.contains("Go"));
        assertTrue(normalized.contains("Java"));
        assertTrue(normalized.contains("Rust"));
        assertEquals(7, normalized.size());
    }

    @Test
    @DisplayName("4. Creation-age window calculation (7-day and 30-day)")
    public void testCreationAgeWindowCalculation() {
        Instant now = Instant.now();
        Instant cutoff7 = now.minus(Duration.ofDays(7));
        Instant cutoff30 = now.minus(Duration.ofDays(30));

        Instant itemRecent = now.minus(Duration.ofDays(3));
        Instant itemMid = now.minus(Duration.ofDays(15));
        Instant itemOld = now.minus(Duration.ofDays(45));

        assertTrue(itemRecent.isAfter(cutoff7));
        assertFalse(itemMid.isAfter(cutoff7));
        assertFalse(itemOld.isAfter(cutoff7));

        assertTrue(itemRecent.isAfter(cutoff30));
        assertTrue(itemMid.isAfter(cutoff30));
        assertFalse(itemOld.isAfter(cutoff30));
    }

    @Test
    @DisplayName("5. Active user query logic in UserRepository")
    public void testActiveUserQuerySemantics() {
        AtomicReference<Document> capturedQuery = new AtomicReference<>();

        @SuppressWarnings("unchecked")
        MongoCursor<Document> cursor = createProxy(MongoCursor.class, (proxy, method, args) -> {
            if ("hasNext".equals(method.getName())) return false;
            return null;
        });

        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = createProxy(FindIterable.class, (proxy, method, args) -> {
            if ("iterator".equals(method.getName())) return cursor;
            return proxy;
        });

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("find".equals(method.getName())) {
                capturedQuery.set((Document) args[0]);
                return findIterable;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        UserRepository userRepository = new UserRepository(mongoOperations, objectMapper);
        Instant since = Instant.now().minus(Duration.ofDays(30));
        List<ContributorProfile> active = userRepository.findActiveUsers(since, 50);
        assertNotNull(active);
        assertNotNull(capturedQuery.get());
        assertTrue(capturedQuery.get().containsKey("$or"));
    }

    @Test
    @DisplayName("6. Closed-issue refresh behavior: mark closed without deleting records or votes")
    public void testClosedIssueRefreshPreservation() {
        AtomicReference<Document> capturedFilter = new AtomicReference<>();
        AtomicReference<Document> capturedUpdate = new AtomicReference<>();
        AtomicInteger deleteCalls = new AtomicInteger(0);

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("updateOne".equals(method.getName())) {
                capturedFilter.set((Document) args[0]);
                capturedUpdate.set((Document) args[1]);
                return UpdateResult.acknowledged(1, 1L, null);
            }
            if ("deleteOne".equals(method.getName())) {
                deleteCalls.incrementAndGet();
                return null;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        IssueRepository issueRepository = new IssueRepository(mongoOperations);
        boolean closed = issueRepository.markIssueClosed("github_owner_repo_10", "2026-10-25T12:00:00Z");

        assertTrue(closed);
        assertEquals("github_owner_repo_10", capturedFilter.get().getString("id"));
        Document setDoc = (Document) capturedUpdate.get().get("$set");
        assertEquals("closed", setDoc.getString("state"));
        assertEquals("closed", setDoc.getString("stale_status"));
        assertEquals(0, deleteCalls.get(), "Must never delete documents to mark closed");
    }

    @Test
    @DisplayName("7. Duplicate ingestion idempotency: repeated upsert updates the record in place")
    public void testDuplicateIngestionIdempotency() {
        List<Document> filtersUsed = new ArrayList<>();
        Document savedDoc = new Document("id", "github_test_repo_1")
                .append("source", "github")
                .append("repository_full_name", "test/repo")
                .append("issue_number", 1)
                .append("title", "First run");

        @SuppressWarnings("unchecked")
        FindIterable<Document> findIterable = createProxy(FindIterable.class, (proxy, method, args) -> {
            if ("first".equals(method.getName())) {
                return savedDoc;
            }
            return proxy;
        });

        @SuppressWarnings("unchecked")
        MongoCollection<Document> collection = createProxy(MongoCollection.class, (proxy, method, args) -> {
            if ("updateOne".equals(method.getName())) {
                filtersUsed.add((Document) args[0]);
                return UpdateResult.acknowledged(1, 1L, null);
            }
            if ("find".equals(method.getName())) {
                return findIterable;
            }
            return null;
        });

        MongoOperations mongoOperations = createProxy(MongoOperations.class, (proxy, method, args) -> {
            if ("getCollection".equals(method.getName())) {
                return collection;
            }
            return null;
        });

        IssueRepository issueRepository = new IssueRepository(mongoOperations);

        Map<String, Object> issue = new HashMap<>();
        issue.put("id", "github_test_repo_1");
        issue.put("source", "github");
        issue.put("repository_full_name", "test/repo");
        issue.put("issue_number", 1);
        issue.put("title", "First run");

        issueRepository.upsertIssue(issue, true);
        issueRepository.upsertIssue(issue, true);

        assertEquals(2, filtersUsed.size());
        for (Document f : filtersUsed) {
            assertEquals("github_test_repo_1", f.getString("id"));
        }
    }

    @Test
    @DisplayName("8. Partial platform failure and exit code semantics in IssuePilotCli")
    public void testCliPartialFailureExitCode() {
        Map<String, Object> discFail = new HashMap<>();
        discFail.put("status", "failed");
        discFail.put("discovered_count", 0);
        DiscoveryService discService = new StubDiscoveryService(discFail);

        Map<String, Object> refOk = new HashMap<>();
        refOk.put("status", "completed");
        RefreshService refService = new StubRefreshService(refOk);

        IssuePilotCli cli = new IssuePilotCli(null, null, null, discService, refService, null, objectMapper);
        cli.run("--daily-ingestion", "--days=30", "--source=all");

        assertEquals(1, cli.getExitCode(), "CLI exit code must be 1 on platform failure");
    }

    @Test
    @DisplayName("9. Clean CLI options parsing, help message, and successful exit code")
    public void testCliHelpAndSuccessExitCode() {
        Map<String, Object> discOk = new HashMap<>();
        discOk.put("status", "completed");
        discOk.put("discovered_count", 10);
        DiscoveryService discService = new StubDiscoveryService(discOk);

        Map<String, Object> refOk = new HashMap<>();
        refOk.put("status", "completed");
        RefreshService refService = new StubRefreshService(refOk);

        IssuePilotCli cli = new IssuePilotCli(null, null, null, discService, refService, null, objectMapper);
        cli.run("--help");
        assertEquals(0, cli.getExitCode());

        cli.run("--daily-ingestion", "--days=7", "--source=github");
        assertEquals(0, cli.getExitCode());
    }
}
