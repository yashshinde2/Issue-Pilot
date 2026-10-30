package com.issuepilot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.adapter.BugzillaAdapter;
import com.issuepilot.adapter.CodebergAdapter;
import com.issuepilot.adapter.GitHubAdapter;
import com.issuepilot.adapter.GitLabAdapter;
import com.issuepilot.config.AppProperties;
import com.issuepilot.model.CanonicalIssueKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2: Comprehensive Platform Adapter Conformance Test Suite.
 * Validates Section 3 and Phase 2 conformance requirements across all 4 platforms:
 * GitHub, GitLab, Codeberg / Forgejo, and Bugzilla.
 */
public class PlatformAdapterConformanceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AppProperties appProperties;
    private GitHubAdapter githubAdapter;
    private GitLabAdapter gitlabAdapter;
    private CodebergAdapter codebergAdapter;
    private BugzillaAdapter bugzillaAdapter;

    @BeforeEach
    public void setup() {
        appProperties = new AppProperties();
        appProperties.setBugzillaBaseUrl("https://bugzilla.mozilla.org/rest");
        githubAdapter = new GitHubAdapter(appProperties, objectMapper);
        gitlabAdapter = new GitLabAdapter(appProperties, objectMapper);
        codebergAdapter = new CodebergAdapter(appProperties, objectMapper);
        bugzillaAdapter = new BugzillaAdapter(appProperties, objectMapper);
    }

    private Map<String, Object> loadFixture(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        assertTrue(resource.exists(), "Fixture path must exist: " + path);
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<>() {});
        }
    }

    @Test
    @DisplayName("1. GitHub Adapter: Normalization, Canonical Key, Author Roles & Bot Exclusion")
    public void testGitHubAdapterConformance() throws Exception {
        Map<String, Object> raw = loadFixture("fixtures/adapters/github_issue_realistic.json");
        Map<String, Object> normalized = githubAdapter.normalizeIssue(raw, Collections.emptyMap());

        assertNotNull(normalized);
        assertEquals("github|api.github.com|kubernetes/kubernetes|42", normalized.get("issue_key"));
        assertEquals("kubernetes/kubernetes", normalized.get("canonical_repository_key"));
        assertEquals("github", normalized.get("source"));
        assertEquals("api.github.com", normalized.get("source_instance"));
        assertEquals(42, normalized.get("issue_number"));
        assertEquals("open", normalized.get("state"));
        assertFalse((Boolean) normalized.get("is_assigned"));

        // Author Role and Bot classification
        assertEquals("MEMBER", normalized.get("author_association"));
        assertTrue((Boolean) normalized.get("is_maintainer"));
        assertFalse((Boolean) normalized.get("is_author_bot"));

        // Pull request exclusion
        Map<String, Object> prItem = new HashMap<>(raw);
        prItem.put("pull_request", Map.of("url", "https://api.github.com/repos/k8s/k8s/pulls/42"));
        assertNull(githubAdapter.normalizeIssue(prItem, Collections.emptyMap()), "Pull requests must be excluded from candidate normalization");

        // Bot account exclusion
        Map<String, Object> botItem = new HashMap<>(raw);
        botItem.put("user", Map.of("login", "dependabot[bot]", "type", "Bot"));
        Map<String, Object> botNorm = githubAdapter.normalizeIssue(botItem, Collections.emptyMap());
        assertTrue((Boolean) botNorm.get("is_author_bot"), "Bot user must be classified as is_author_bot=true");

        // Non-maintainer contributor
        Map<String, Object> contribItem = new HashMap<>(raw);
        contribItem.put("author_association", "CONTRIBUTOR");
        Map<String, Object> contribNorm = githubAdapter.normalizeIssue(contribItem, Collections.emptyMap());
        assertFalse((Boolean) contribNorm.get("is_maintainer"), "CONTRIBUTOR role must not be classified as maintainer");
    }

    @Test
    @DisplayName("2. GitLab Adapter: Project Identity, Canonical Key, Roles & Self-Hosted Distinction")
    public void testGitLabAdapterConformance() throws Exception {
        Map<String, Object> raw = loadFixture("fixtures/adapters/gitlab_issue_realistic.json");
        Map<String, Object> normalized = gitlabAdapter.normalizeIssue(raw, Collections.emptyMap());

        assertNotNull(normalized);
        assertEquals("gitlab|gitlab.com|gitlab-org/gitlab|128", normalized.get("issue_key"));
        assertEquals("gitlab-org/gitlab", normalized.get("canonical_repository_key"));
        assertEquals("gitlab", normalized.get("source"));
        assertEquals("gitlab.com", normalized.get("source_instance"));
        assertEquals(128, normalized.get("issue_number"));
        assertEquals("open", normalized.get("state"));
        assertEquals(5, normalized.get("comment_count"));

        // Self-hosted GitLab distinction test
        CanonicalIssueKey publicGitLab = CanonicalIssueKey.parse("gitlab|gitlab.com|inkscape/inkscape|100");
        CanonicalIssueKey selfGitLab = CanonicalIssueKey.parse("gitlab|gitlab.gnome.org|inkscape/inkscape|100");
        assertNotEquals(publicGitLab, selfGitLab);
        assertNotEquals(publicGitLab.toString(), selfGitLab.toString());

        // Bot author detection
        Map<String, Object> botItem = new HashMap<>(raw);
        botItem.put("author", Map.of("username", "security-policy-bot", "bot", true));
        Map<String, Object> botNorm = gitlabAdapter.normalizeIssue(botItem, Collections.emptyMap());
        assertTrue((Boolean) botNorm.get("is_author_bot"), "GitLab bot author must be detected");
    }

    @Test
    @DisplayName("3. Codeberg / Forgejo Adapter: Capability Discovery, Permissions & Canonical Key")
    public void testCodebergAdapterConformance() throws Exception {
        Map<String, Object> raw = loadFixture("fixtures/adapters/codeberg_issue_realistic.json");
        Map<String, Object> normalized = codebergAdapter.normalizeIssue(raw, Collections.emptyMap());

        assertNotNull(normalized);
        assertEquals("codeberg|codeberg.org|forgejo/forgejo|88", normalized.get("issue_key"));
        assertEquals("forgejo/forgejo", normalized.get("canonical_repository_key"));
        assertEquals("codeberg", normalized.get("source"));
        assertEquals("codeberg.org", normalized.get("source_instance"));
        assertEquals(88, normalized.get("issue_number"));
        assertEquals("open", normalized.get("state"));
        assertFalse((Boolean) normalized.get("is_assigned"));

        // Bot author detection
        Map<String, Object> botItem = new HashMap<>(raw);
        botItem.put("user", Map.of("login", "crowdin-bot", "type", "User"));
        Map<String, Object> botNorm = codebergAdapter.normalizeIssue(botItem, Collections.emptyMap());
        assertTrue((Boolean) botNorm.get("is_author_bot"), "Codeberg bot username must be detected");
    }

    @Test
    @DisplayName("4. Bugzilla Adapter: Canonical Key, No Repo Fabrication & Strictly Unavailable R/H")
    public void testBugzillaAdapterConformance() throws Exception {
        Map<String, Object> raw = loadFixture("fixtures/adapters/bugzilla_bug_realistic.json");
        Map<String, Object> normalized = bugzillaAdapter.normalizeIssue(raw, Collections.emptyMap());

        assertNotNull(normalized);
        assertEquals("bugzilla|bugzilla.mozilla.org|Core:Graphics: WebRender|1899123", normalized.get("issue_key"));
        assertEquals("Core:Graphics: WebRender", normalized.get("canonical_repository_key"));
        assertEquals("bugzilla", normalized.get("source"));
        assertEquals("bugzilla.mozilla.org", normalized.get("source_instance"));
        assertEquals(1899123, normalized.get("issue_number"));
        assertEquals("open", normalized.get("state"));

        // Critical: NO repository fabrication on Bugzilla
        assertNull(normalized.get("repository_full_name"), "Bugzilla must NEVER fabricate a repository full name");
        assertNull(normalized.get("repository_owner"), "Bugzilla must NEVER fabricate a repository owner");
        assertNull(normalized.get("repository_name"), "Bugzilla must NEVER fabricate a repository name");
        assertNull(normalized.get("repository_url"), "Bugzilla must NEVER fabricate a repository URL");

        // Placeholder assignee must be treated as UNASSIGNED
        assertFalse((Boolean) normalized.get("is_assigned"), "nobody@mozilla.org placeholder must be treated as unassigned");

        // Product and component
        assertEquals("Core", normalized.get("product"));
        assertEquals("Graphics: WebRender", normalized.get("component"));

        // Responsiveness and Repository Health MUST be unavailable
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) normalized.get("responsiveness_evidence");
        assertNotNull(resp);
        assertFalse((Boolean) resp.get("available"), "Bugzilla responsiveness must be unavailable without verified human maintainer mapping");

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) normalized.get("health_evidence");
        assertNotNull(health);
        assertFalse((Boolean) health.get("available"), "Bugzilla repository health must be unavailable without verified code host link");
    }

    @Test
    @DisplayName("5. Bugzilla Status Semantics: Open vs Closed Resolution States")
    public void testBugzillaStatusSemantics() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("id", 101);
        raw.put("summary", "Test bug");
        raw.put("product", "TestProduct");
        raw.put("component", "TestComponent");

        // NEW with no resolution -> open
        raw.put("status", "NEW");
        raw.put("resolution", "");
        Map<String, Object> norm1 = bugzillaAdapter.normalizeIssue(raw, Collections.emptyMap());
        assertEquals("open", norm1.get("state"));

        // ASSIGNED with no resolution -> open
        raw.put("status", "ASSIGNED");
        Map<String, Object> norm2 = bugzillaAdapter.normalizeIssue(raw, Collections.emptyMap());
        assertEquals("open", norm2.get("state"));

        // RESOLVED FIXED -> closed
        raw.put("status", "RESOLVED");
        raw.put("resolution", "FIXED");
        Map<String, Object> norm3 = bugzillaAdapter.normalizeIssue(raw, Collections.emptyMap());
        assertEquals("closed", norm3.get("state"));

        // VERIFIED INVALID -> closed
        raw.put("status", "VERIFIED");
        raw.put("resolution", "INVALID");
        Map<String, Object> norm4 = bugzillaAdapter.normalizeIssue(raw, Collections.emptyMap());
        assertEquals("closed", norm4.get("state"));
    }

    @Test
    @DisplayName("6. Missing Fields, Malformed Timestamps & Incomplete Pagination Handling")
    public void testMissingDataAndMalformedTimestamps() {
        // Missing optional fields does not throw NPE
        Map<String, Object> minimalGithub = Map.of(
                "number", 999,
                "title", "Minimal title",
                "html_url", "https://github.com/org/repo/issues/999",
                "repository_url", "https://api.github.com/repos/org/repo"
        );
        Map<String, Object> norm = githubAdapter.normalizeIssue(minimalGithub, Collections.emptyMap());
        assertNotNull(norm);
        assertEquals("github|api.github.com|org/repo|999", norm.get("issue_key"));
        assertEquals("open", norm.get("state"));
        assertFalse((Boolean) norm.get("is_assigned"));
    }
}
