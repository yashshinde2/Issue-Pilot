package com.issuepilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AlgorithmConstants;
import com.issuepilot.model.*;
import com.issuepilot.model.EvidenceModelsV2.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1: Comprehensive Contract, Versioning, and Backward-Compatibility Test Suite.
 * Validates Section 2 and Phase 1 requirements of Issue Pilot V2.3 Specification.
 */
public class AlgorithmV23DataContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("1. Canonical 4-Part Issue Key: Construction, Formatting & Parsing")
    public void testCanonicalIssueKeyConstructionAndParsing() {
        CanonicalIssueKey key = new CanonicalIssueKey("github", "api.github.com", "torvalds/linux", "12345");
        assertEquals("github|api.github.com|torvalds/linux|12345", key.toString());
        assertEquals("github", key.getSource());
        assertEquals("api.github.com", key.getSourceInstance());
        assertEquals("torvalds/linux", key.getCanonicalRepositoryKey());
        assertEquals("12345", key.getNativeIssueId());

        CanonicalIssueKey parsed = CanonicalIssueKey.parse("gitlab|gitlab.com|inkscape/inkscape|9876");
        assertEquals("gitlab", parsed.getSource());
        assertEquals("gitlab.com", parsed.getSourceInstance());
        assertEquals("inkscape/inkscape", parsed.getCanonicalRepositoryKey());
        assertEquals("9876", parsed.getNativeIssueId());
        assertEquals("gitlab|gitlab.com|inkscape/inkscape|9876", parsed.toString());

        // Self-hosted instance distinction test
        CanonicalIssueKey gitlabPublic = CanonicalIssueKey.parse("gitlab|gitlab.com|gnome/gimp|10");
        CanonicalIssueKey gitlabSelf = CanonicalIssueKey.parse("gitlab|gitlab.gnome.org|gnome/gimp|10");
        assertNotEquals(gitlabPublic, gitlabSelf);
        assertNotEquals(gitlabPublic.toString(), gitlabSelf.toString());
    }

    @Test
    @DisplayName("2. Canonical Issue Key: Strict Validation and Rejection of Invalid Keys")
    public void testCanonicalIssueKeyValidationFailures() {
        // Less than 4 parts
        assertThrows(IllegalArgumentException.class, () -> CanonicalIssueKey.parse("github|api.github.com|repo"));
        // More than 4 parts
        assertThrows(IllegalArgumentException.class, () -> CanonicalIssueKey.parse("github|api.github.com|org|repo|123"));
        // Null or blank
        assertThrows(IllegalArgumentException.class, () -> CanonicalIssueKey.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CanonicalIssueKey.parse("   "));
        // Empty segment
        assertThrows(IllegalArgumentException.class, () -> CanonicalIssueKey.parse("github||org/repo|123"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalIssueKey.parse("github|api.github.com||123"));
        // Embedded delimiter in segment
        assertThrows(IllegalArgumentException.class, () -> new CanonicalIssueKey("git|hub", "api.github.com", "repo", "1"));
    }

    @Test
    @DisplayName("3. Evidence Envelope: Available, Known-Zero & Unavailable Semantics")
    public void testEvidenceKnownZeroVsUnavailableSemantics() {
        // 1. Defensibly measured signal
        Evidence<Double> positiveEv = Evidence.of(75.5, 0.85, "github", "api.github.com", "commit_analysis");
        assertTrue(positiveEv.isAvailable());
        assertEquals(75.5, positiveEv.getValue());
        assertEquals(0.85, positiveEv.getConfidence());
        assertTrue(positiveEv.isPaginationComplete());

        // 2. Known zero: successful query proves 0
        Evidence<Double> zeroEv = Evidence.knownZero(0.90, "github", "api.github.com", "open_pr_count", 0.0);
        assertTrue(zeroEv.isAvailable());
        assertNotNull(zeroEv.getValue());
        assertEquals(0.0, zeroEv.getValue());
        assertEquals(0.90, zeroEv.getConfidence());

        // 3. Unavailable signal: value MUST be null, confidence MUST be 0.0
        Evidence<Double> unavailEv = Evidence.unavailable("Incomplete pagination due to rate limit", "gitlab", "gitlab.com");
        assertFalse(unavailEv.isAvailable());
        assertNull(unavailEv.getValue());
        assertEquals(0.0, unavailEv.getConfidence());
        assertEquals("Incomplete pagination due to rate limit", unavailEv.getApproximationReason());
        assertFalse(unavailEv.isPaginationComplete());

        // Incomplete pagination automatically sets available=false and value=null
        Evidence<Double> incompleteEv = Evidence.of(50.0, 0.8, "github", "api.github.com", "test");
        incompleteEv.setPaginationComplete(false);
        assertFalse(incompleteEv.isAvailable());
        assertNull(incompleteEv.getValue());
        assertEquals(0.0, incompleteEv.getConfidence());
    }

    @Test
    @DisplayName("4. Evidence Confidence: Factor Calculation, Source Ceilings & Strict Rejection of Invalid Confidence")
    public void testEvidenceConfidenceFactorsAndCeilings() {
        // c = min(source_cap, c_sample * c_coverage * c_directness)
        // Case A: Factors exceed source cap
        double cA = Evidence.computeConfidence(1.0, 1.0, 1.0, AlgorithmConstants.SOURCE_CAP_GITHUB);
        assertEquals(0.90, cA, 1e-6);

        // Case B: Factors lower than source cap
        // 0.8 * 0.7 * 0.9 = 0.504 < 0.90
        double cB = Evidence.computeConfidence(0.8, 0.7, 0.9, AlgorithmConstants.SOURCE_CAP_GITHUB);
        assertEquals(0.504, cB, 1e-6);

        // Case C: Strict rejection of invalid/non-finite confidence (MUST throw, not clamp!)
        assertThrows(IllegalArgumentException.class, () -> Evidence.validateConfidence(1.5));
        assertThrows(IllegalArgumentException.class, () -> Evidence.validateConfidence(-0.1));
        assertThrows(IllegalArgumentException.class, () -> Evidence.validateConfidence(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Evidence.validateConfidence(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Evidence.validateConfidence(Double.NEGATIVE_INFINITY));

        // Ensure Evidence.of() and knownZero() reject invalid confidence
        assertThrows(IllegalArgumentException.class, () -> Evidence.of(50.0, 1.2, "github", "api.github.com", "test"));
        assertThrows(IllegalArgumentException.class, () -> Evidence.of(50.0, -0.05, "github", "api.github.com", "test"));
        assertThrows(IllegalArgumentException.class, () -> Evidence.knownZero(1.1, "github", "api.github.com", "test", 0.0));

        // Case D: Factory with calculated factors
        Evidence<Double> factorEv = Evidence.withCalculatedConfidence(88.0, 0.8, 0.8, 1.0, 0.85, "gitlab", "gitlab.com", "formula");
        assertEquals(0.64, factorEv.getConfidence(), 1e-6);
        assertNotNull(factorEv.getConfidenceFactors());
        assertEquals(0.8, factorEv.getConfidenceFactors().getCSample());
        assertEquals(0.85, factorEv.getConfidenceFactors().getSourceCap());
    }

    @Test
    @DisplayName("11. Golden JSON Fixtures Verification: Known-Zero, Unavailable, Incomplete Pagination & Structured Payloads")
    public void testGoldenJsonFixturesVerification() throws Exception {
        org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("fixtures/golden_evidence_v23_fixtures.json");
        assertTrue(resource.exists(), "golden_evidence_v23_fixtures.json must exist in test resources");

        JsonNode root = objectMapper.readTree(resource.getInputStream());
        JsonNode fixtures = root.get("golden_fixtures");
        assertNotNull(fixtures);

        // 1. Known Zero
        JsonNode kz = fixtures.get("known_zero");
        assertTrue(kz.get("available").asBoolean());
        assertEquals(0.0, kz.get("value").asDouble());
        assertEquals(0.9, kz.get("confidence").asDouble());
        assertTrue(kz.get("pagination_complete").asBoolean());

        // 2. Unavailable Unsupported Signal
        JsonNode unavail = fixtures.get("unavailable_unsupported_signal");
        assertFalse(unavail.get("available").asBoolean());
        assertTrue(unavail.get("value").isNull());
        assertEquals(0.0, unavail.get("confidence").asDouble());
        assertFalse(unavail.get("pagination_complete").asBoolean());
        assertTrue(unavail.get("approximation_reason").asText().contains("Bugzilla lacks native commit"));

        // 3. Unavailable Incomplete Pagination
        JsonNode incPag = fixtures.get("unavailable_incomplete_pagination");
        assertFalse(incPag.get("available").asBoolean());
        assertTrue(incPag.get("value").isNull());
        assertEquals(0.0, incPag.get("confidence").asDouble());
        assertFalse(incPag.get("pagination_complete").asBoolean());
        assertTrue(incPag.get("approximation_reason").asText().contains("HTTP 429"));

        // 4. Measured Positive
        JsonNode mp = fixtures.get("measured_positive");
        assertTrue(mp.get("available").asBoolean());
        assertEquals(85.0, mp.get("value").asDouble());
        assertEquals(0.81, mp.get("confidence").asDouble());

        // 5. Structured Difficulty
        JsonNode sd = fixtures.get("structured_difficulty");
        assertTrue(sd.get("available").asBoolean());
        assertEquals(25.0, sd.get("value").get("scope_change_size").asDouble());
        assertEquals(50.0, sd.get("value").get("technical_complexity").asDouble());
        assertEquals(25.0, sd.get("value").get("dependency_arch_impact").asDouble());
        assertEquals(25.0, sd.get("value").get("coordination_complexity").asDouble());
        assertFalse(sd.get("value").has("historical_effort")); // No HE
    }

    @Test
    @DisplayName("5. Timestamp Arithmetic: UTC ISO-8601 and Clock Skew Clamping")
    public void testTimestampDurationValidation() {
        String t1 = "2026-10-30T10:00:00Z";
        String t2 = "2026-10-30T10:05:00Z";
        Duration d = Evidence.computeSafeDuration(t1, t2, 500);
        assertEquals(300, d.toSeconds());

        // Tolerated minor clock skew (e.g. -200 ms with 500 ms tolerance) -> clamped to ZERO
        String tSkewStart = "2026-10-30T10:00:00.200Z";
        String tSkewEnd = "2026-10-30T10:00:00.000Z";
        Duration dSkew = Evidence.computeSafeDuration(tSkewStart, tSkewEnd, 500);
        assertEquals(Duration.ZERO, dSkew);

        // Large negative duration beyond tolerance -> rejected with IllegalArgumentException
        String tFuture = "2026-10-30T11:00:00Z";
        String tPast = "2026-10-30T10:00:00Z";
        assertThrows(IllegalArgumentException.class, () -> Evidence.computeSafeDuration(tFuture, tPast, 500));
    }

    @Test
    @DisplayName("6. Algorithm and Schema Versioning Metadata")
    public void testAlgorithmVersioningConstants() {
        assertEquals("2.3.0", AlgorithmConstants.ALGORITHM_VERSION);
        assertEquals("2.3.0", AlgorithmConstants.FEATURE_SCHEMA_VERSION);
        assertEquals("1.0.0", AlgorithmConstants.TECHNOLOGY_REGISTRY_VERSION);
        assertEquals("1.0.0", AlgorithmConstants.LABEL_TAXONOMY_VERSION);

        AlgorithmMetadata meta = AlgorithmMetadata.current();
        assertEquals("2.3.0", meta.getAlgorithmVersion());
        assertEquals("2.3.0", meta.getFeatureSchemaVersion());
        assertEquals("1.0.0", meta.getTechnologyRegistryVersion());
        assertEquals("1.0.0", meta.getLabelTaxonomyVersion());
        assertNotNull(meta.getRequestSnapshotTime());
    }

    @Test
    @DisplayName("7. IssueRecord: Canonical Fallback Issue Key Generation")
    public void testIssueRecordCanonicalFallbackIssueKey() {
        IssueRecord issue = new IssueRecord();
        issue.setSource("github");
        issue.setSourceInstance("api.github.com");
        issue.setRepositoryFullName("facebook/react");
        issue.setIssueNumber(1001);

        // Not explicitly set -> computed canonically
        assertEquals("github|api.github.com|facebook/react|1001", issue.getIssueKey());

        // Explicit override preserves custom key
        issue.setIssueKey("github|api.github.com|facebook/react|custom-key");
        assertEquals("github|api.github.com|facebook/react|custom-key", issue.getIssueKey());

        // Bugzilla fallback
        IssueRecord bz = new IssueRecord();
        bz.setSource("bugzilla");
        bz.setSourceInstance("bugzilla.mozilla.org");
        bz.setProduct("Firefox");
        bz.setComponent("General");
        bz.setNativeId("778899");
        assertEquals("bugzilla|bugzilla.mozilla.org|Firefox/General|778899", bz.getIssueKey());
    }

    @Test
    @DisplayName("8. Serialization Parity: Jackson Serialization with V1 and V2 Envelopes")
    public void testIssueRecordSerializationWithV1AndV2Envelopes() throws Exception {
        IssueRecord issue = new IssueRecord();
        issue.setId("test_rec_1");
        issue.setSource("gitlab");
        issue.setSourceInstance("gitlab.com");
        issue.setRepositoryFullName("gitlab-org/gitlab");
        issue.setIssueNumber(456);
        issue.setTitle("Add GraphQL endpoint for issue metrics");
        issue.setBody("Details of metrics implementation");
        issue.setIssueUrl("https://gitlab.com/gitlab-org/gitlab/-/issues/456");

        // V1 Difficulty Evidence (preserved)
        EvidenceModels.DifficultyEvidence diffV1 = new EvidenceModels.DifficultyEvidence();
        diffV1.setScopeChangeSize(50.0);
        diffV1.setTechnicalComplexity(60.0);
        diffV1.setHistoricalEffort(70.0);
        diffV1.setDependencyArchImpact(40.0);
        diffV1.setCoordinationComplexity(30.0);
        issue.setDifficultyEvidence(diffV1);

        // V2.3 Difficulty Evidence Envelope (no HE)
        DifficultyEvidenceV2 diffV2 = new DifficultyEvidenceV2(50.0, 75.0, 50.0, 25.0);
        Evidence<DifficultyEvidenceV2> diffEvV2 = Evidence.of(diffV2, 0.85, "gitlab", "gitlab.com", "structured_parser");
        diffEvV2.setSampleSize(4);
        diffEvV2.setCoverage(1.0);
        issue.setDifficultyEvidenceV2(diffEvV2);

        // V2.3 Freshness Evidence Envelope
        FreshnessEvidenceV2 freshV2 = new FreshnessEvidenceV2(14.0, 7.0);
        Evidence<FreshnessEvidenceV2> freshEvV2 = Evidence.of(freshV2, 0.90, "gitlab", "gitlab.com", "exponential_half_life");
        issue.setFreshnessEvidenceV2(freshEvV2);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(issue);
        assertNotNull(json);

        JsonNode root = objectMapper.readTree(json);
        assertEquals("gitlab|gitlab.com|gitlab-org/gitlab|456", root.get("issue_key").asText());
        assertEquals("gitlab", root.get("source").asText());
        assertEquals("gitlab.com", root.get("source_instance").asText());

        // Check V1 evidence preserved
        assertTrue(root.has("difficulty_evidence"));
        assertEquals(70.0, root.get("difficulty_evidence").get("historical_effort").asDouble());

        // Check V2.3 evidence present and correctly structured
        assertTrue(root.has("difficulty_evidence_v2"));
        JsonNode d2 = root.get("difficulty_evidence_v2");
        assertTrue(d2.get("available").asBoolean());
        assertEquals(0.85, d2.get("confidence").asDouble());
        assertEquals(75.0, d2.get("value").get("technical_complexity").asDouble());
        assertFalse(d2.get("value").has("historical_effort")); // HE absent in V2.3

        // Check V2.3 freshness
        assertTrue(root.has("freshness_evidence_v2"));
        JsonNode f2 = root.get("freshness_evidence_v2");
        assertEquals(50.0, f2.get("value").get("age_score").asDouble(), 1e-4);
        assertEquals(50.0, f2.get("value").get("activity_score").asDouble(), 1e-4);

        // Deserialization round-trip
        IssueRecord deserialized = objectMapper.readValue(json, IssueRecord.class);
        assertEquals("gitlab|gitlab.com|gitlab-org/gitlab|456", deserialized.getIssueKey());
        assertNotNull(deserialized.getDifficultyEvidence());
        assertEquals(70.0, deserialized.getDifficultyEvidence().getHistoricalEffort());
        assertNotNull(deserialized.getDifficultyEvidenceV2());
        assertEquals(75.0, deserialized.getDifficultyEvidenceV2().getValue().getTechnicalComplexity());
    }

    @Test
    @DisplayName("9. Backward-Compatibility: Legacy V1 Payloads Unchanged")
    public void testBackwardCompatibilityWithLegacyPayloads() throws Exception {
        String legacyJson = """
        {
            "id": "github_sample_1",
            "issue_number": 42,
            "title": "Fix bug in parser",
            "repository_full_name": "octocat/Hello-World",
            "issue_url": "https://github.com/octocat/Hello-World/issues/42",
            "state": "open",
            "difficulty_evidence": {
                "scope_change_size": 25.0,
                "technical_complexity": 50.0,
                "historical_effort": 50.0,
                "dependency_arch_impact": 25.0,
                "coordination_complexity": 25.0
            }
        }
        """;

        IssueRecord issue = objectMapper.readValue(legacyJson, IssueRecord.class);
        assertEquals("github_sample_1", issue.getId());
        assertEquals(42, issue.getIssueNumber());
        assertEquals("github|api.github.com|octocat/Hello-World|42", issue.getIssueKey());
        assertNotNull(issue.getDifficultyEvidence());
        assertEquals(25.0, issue.getDifficultyEvidence().getScopeChangeSize());
        assertNull(issue.getDifficultyEvidenceV2()); // V2 evidence gracefully null
    }

    @Test
    @DisplayName("10. Java to C++ Engine Parity: Native binary parses and returns canonical issue_key and source")
    @SuppressWarnings("unchecked")
    public void testJavaToCppBinaryContract() {
        com.issuepilot.config.AppProperties props = new com.issuepilot.config.AppProperties();
        props.setCppEnginePath("ranking_engine/build/issue_pilot_ranking_engine.exe");
        com.issuepilot.service.CppRankingClient client = new com.issuepilot.service.CppRankingClient(props, objectMapper);
        if (!client.isAvailable()) {
            return;
        }

        IssueRecord issue = new IssueRecord();
        issue.setId("cand-cpp-1");
        issue.setIssueKey("codeberg|codeberg.org|forge/project|99");
        issue.setSource("codeberg");
        issue.setSourceInstance("codeberg.org");
        issue.setIssueNumber(99);
        issue.setTitle("Support OAuth2 PKCE");
        issue.setState("open");
        issue.setIssueUrl("https://codeberg.org/forge/project/issues/99");
        issue.setTechnologies(List.of(new TechnologyRelevance("Go", 1.0)));

        java.util.Map<String, Object> profile = java.util.Map.of(
                "user_id", "u_test",
                "capability", 70.0,
                "skills", java.util.Map.of("Go", java.util.Map.of("score", 85.0, "confidence", 1.0))
        );

        java.util.Map<String, Object> payload = java.util.Map.of(
                "profile", profile,
                "candidates", List.of(issue)
        );

        java.util.Map<String, Object> result = client.executeRanking(payload);
        assertNotNull(result);
        List<java.util.Map<String, Object>> results = (List<java.util.Map<String, Object>>) result.get("results");
        assertNotNull(results);
        assertEquals(1, results.size());
        java.util.Map<String, Object> top = results.get(0);
        assertEquals("codeberg|codeberg.org|forge/project|99", top.get("issue_key"));
        assertEquals("codeberg", top.get("source"));
        assertEquals("codeberg.org", top.get("source_instance"));
    }
}
