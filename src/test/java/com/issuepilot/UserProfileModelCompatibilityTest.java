package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.model.SkillEvidence;
import com.issuepilot.model.UserIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 User Profile Model Compatibility & Inferred Skill Precedence Test.
 *
 * Verifies:
 * 1. Legacy profiles (without identity, onboarding_completed, or inferred_skills) deserialize cleanly.
 * 2. New profiles serialize and deserialize all new Phase 1 metadata.
 * 3. Inferred skills CANNOT overwrite confirmed skills (precedence invariant).
 * 4. Normalized technology extraction includes confirmed skills and preferences correctly.
 * 5. Serialization ignores unknown fields safely without breaking data integrity.
 */
public class UserProfileModelCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Legacy MongoDB user profile JSON must deserialize without error")
    public void testLegacyProfileDeserialization() throws Exception {
        String legacyJson = """
        {
            "user_id": "legacy-contributor-001",
            "capability": 75.0,
            "accepted_count": 5,
            "rejected_count": 1,
            "completed_count": 4,
            "similarity_to_history": 80.0,
            "has_feedback_history": true,
            "skills": {
                "Python": {
                    "technology": "Python",
                    "score": 85.0,
                    "confidence": 1.0
                },
                "Java": {
                    "technology": "Java",
                    "score": 60.0,
                    "confidence": 0.8
                }
            },
            "preferences": {
                "technologies": ["Python", "Java"],
                "preferred_difficulty": "medium"
            },
            "created_at": "2026-01-01T00:00:00Z",
            "updated_at": "2026-02-01T00:00:00Z"
        }
        """;

        ContributorProfile profile = objectMapper.readValue(legacyJson, ContributorProfile.class);

        assertNotNull(profile);
        assertEquals("legacy-contributor-001", profile.getUserId());
        assertEquals(75.0, profile.getCapability());
        assertEquals(5, profile.getAcceptedCount());
        assertTrue(profile.isHasFeedbackHistory());

        // Default Phase 1 fields must be gracefully initialized
        assertNull(profile.getIdentity());
        assertFalse(profile.isOnboardingCompleted());
        assertNotNull(profile.getInferredSkills());
        assertTrue(profile.getInferredSkills().isEmpty());

        // Existing skills preserved
        assertEquals(2, profile.getSkills().size());
        assertTrue(profile.getSkills().containsKey("Python"));
        assertEquals(85.0, profile.getSkills().get("Python").getScore());
        assertEquals("user_confirmed", profile.getSkills().get("Python").getSource());

        // Normalized technology preferences still include existing skills
        Set<String> normalizedTechs = profile.getNormalizedTechnologyPreferences();
        assertTrue(normalizedTechs.contains("Python"));
        assertTrue(normalizedTechs.contains("Java"));
    }

    @Test
    @DisplayName("New profile with UserIdentity, InferredSkills, and Onboarding status roundtrips cleanly")
    public void testNewProfileFullSerializationRoundtrip() throws Exception {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:12345678");
        profile.setOnboardingCompleted(true);
        profile.setCapability(80.0);

        UserIdentity identity = new UserIdentity("github", "12345678", "octocat");
        identity.setDisplayName("Monalisa Octocat");
        identity.setAvatarUrl("https://avatars.githubusercontent.com/u/12345678?v=4");
        identity.setProfileUrl("https://github.com/octocat");
        identity.setEmail("octocat@github.com");
        profile.setIdentity(identity);

        // Confirmed skills
        profile.confirmSkill("Python", 90.0, 1.0, "advanced");
        profile.confirmSkill("React", 70.0, 0.9, "intermediate");

        // Inferred skill with concrete evidence
        InferredSkill inferredFastApi = new InferredSkill("FastAPI", "framework", 0.85);
        inferredFastApi.addEvidence(new SkillEvidence("dependency_manifest", "octocat/backend-service", "requirements.txt", "fastapi>=0.100.0"));
        inferredFastApi.addEvidence(new SkillEvidence("commit_activity", "octocat/backend-service", null, "18 commits in last 60 days"));
        profile.addInferredSkillSafely(inferredFastApi);

        String json = objectMapper.writeValueAsString(profile);
        assertNotNull(json);
        assertTrue(json.contains("\"onboarding_completed\":true"));
        assertTrue(json.contains("\"provider_user_id\":\"12345678\""));
        assertTrue(json.contains("\"fastapi>=0.100.0\""));

        // Deserialize back
        ContributorProfile deserialized = objectMapper.readValue(json, ContributorProfile.class);
        assertEquals("github:12345678", deserialized.getUserId());
        assertTrue(deserialized.isOnboardingCompleted());
        assertNotNull(deserialized.getIdentity());
        assertEquals("github", deserialized.getIdentity().getProvider());
        assertEquals("12345678", deserialized.getIdentity().getProviderUserId());
        assertEquals("octocat", deserialized.getIdentity().getUsername());

        // Check confirmed skills
        assertEquals(2, deserialized.getSkills().size());
        assertEquals("advanced", deserialized.getSkills().get("Python").getLevel());
        assertEquals("user_confirmed", deserialized.getSkills().get("Python").getSource());

        // Check inferred skills
        assertEquals(1, deserialized.getInferredSkills().size());
        InferredSkill loadedInferred = deserialized.getInferredSkills().get(0);
        assertEquals("FastAPI", loadedInferred.getTechnology());
        assertEquals(0.85, loadedInferred.getConfidence());
        assertEquals(2, loadedInferred.getEvidence().size());
        assertEquals("dependency_manifest", loadedInferred.getEvidence().get(0).getType());
    }

    @Test
    @DisplayName("Inferred skills must NEVER overwrite confirmed skills (Precedence Invariant)")
    public void testInferredSkillsCannotOverwriteConfirmedSkills() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:test-user");

        // User confirmed Python with score 95.0
        profile.confirmSkill("Python", 95.0, 1.0, "expert");

        // Attempt to add inferred Python with lower score or confidence
        InferredSkill inferredPython = new InferredSkill("Python", "language", 0.60);
        inferredPython.addEvidence(new SkillEvidence("language_statistics", "user/repo", null, "30% Python"));

        boolean added = profile.addInferredSkillSafely(inferredPython);
        assertFalse(added, "addInferredSkillSafely must reject adding an inferred skill if the technology is already confirmed");

        // Verify confirmed skill remained completely untouched
        assertEquals(95.0, profile.getSkills().get("Python").getScore());
        assertEquals("user_confirmed", profile.getSkills().get("Python").getSource());
        assertEquals("expert", profile.getSkills().get("Python").getLevel());

        // Attempting case-insensitive collision
        InferredSkill lowerPython = new InferredSkill("python", "language", 0.50);
        boolean addedCaseInsensitive = profile.addInferredSkillSafely(lowerPython);
        assertFalse(addedCaseInsensitive, "addInferredSkillSafely must reject case-insensitive matches to confirmed skills");
    }

    @Test
    @DisplayName("Confirming an inferred skill marks its status as confirmed and registers it in skills map")
    public void testConfirmingInferredSkillUpdatesStatus() {
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:test-user");

        InferredSkill inferredRust = new InferredSkill("Rust", "language", 0.80);
        inferredRust.addEvidence(new SkillEvidence("dependency_manifest", "user/repo", "Cargo.toml", "Cargo manifest present"));
        profile.addInferredSkillSafely(inferredRust);

        assertEquals(1, profile.getInferredSkills().size());
        assertEquals("pending_confirmation", profile.getInferredSkills().get(0).getStatus());
        assertFalse(profile.getSkills().containsKey("Rust"));

        // User confirms Rust
        profile.confirmSkill("Rust", 75.0, 0.9, "intermediate");

        assertTrue(profile.getSkills().containsKey("Rust"));
        assertEquals("confirmed", profile.getInferredSkills().get(0).getStatus());
        assertEquals("user_confirmed", profile.getSkills().get("Rust").getSource());
        assertEquals("intermediate", profile.getSkills().get("Rust").getLevel());
    }

    @Test
    @DisplayName("Unknown future JSON fields are ignored gracefully")
    public void testUnknownFieldsIgnoredGracefully() throws Exception {
        String forwardCompatibleJson = """
        {
            "user_id": "test-user-forward",
            "future_field_xyz": "value123",
            "identity": {
                "provider": "github",
                "provider_user_id": "999",
                "future_token_claim": "unknown"
            },
            "onboarding_completed": false
        }
        """;

        ContributorProfile profile = objectMapper.readValue(forwardCompatibleJson, ContributorProfile.class);
        assertNotNull(profile);
        assertEquals("test-user-forward", profile.getUserId());
        assertNotNull(profile.getIdentity());
        assertEquals("999", profile.getIdentity().getProviderUserId());
    }
}

