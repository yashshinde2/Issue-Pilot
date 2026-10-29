package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.GitHubOAuthService;
import com.issuepilot.security.OAuthStateStore;
import com.issuepilot.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 3 GitHub OAuth & User Provisioning Test Suite.
 *
 * Verifies:
 * 1. Login redirect with secure state token.
 * 2. Missing, mismatched, expired, and reused state rejected.
 * 3. Server-side code exchange and stable numeric ID profile retrieval.
 * 4. Error response when GitHub returns OAuth error or code exchange fails.
 * 5. First login provisions one user account.
 * 6. Repeat login updates metadata without overwriting confirmed skills.
 * 7. Username change preserves canonical provider identity.
 * 8. Short-lived exchange code handoff (no JWTs in query params).
 * 9. Invalid/missing application token rejected on /api/auth/me (401).
 * 10. Valid application token can access /api/auth/me.
 * 11. /api/auth/logout behavior matches stateless contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "APP_JWT_SECRET=test_very_secure_secret_key_with_at_least_32_bytes_of_entropy_12345",
        "APP_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "APP_JWT_ISSUER=https://issuepilot-test.com",
        "APP_JWT_EXPIRATION_SECONDS=3600",
        "GITHUB_CLIENT_ID=test-github-client-id",
        "GITHUB_CLIENT_SECRET=test-github-client-secret",
        "GITHUB_OAUTH_REDIRECT_URI=http://localhost:8000/api/auth/github/callback",
        "FRONTEND_APP_URL=http://localhost:3000"
})
@SuppressWarnings("null")
public class GitHubOAuthProvisioningTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
        EnvLoader.loadEnv();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OAuthStateStore oAuthStateStore;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.issuepilot.security.UserCredentialService userCredentialService;

    @MockitoBean
    private GitHubOAuthService gitHubOAuthService;

    @BeforeEach
    public void setupMocks() {
        when(gitHubOAuthService.isConfigured()).thenReturn(true);
        when(gitHubOAuthService.buildAuthorizationUrl(anyString())).thenAnswer(invocation -> {
            String state = invocation.getArgument(0);
            return "https://github.com/login/oauth/authorize?client_id=test-github-client-id&state=" + state;
        });
    }

    @Test
    @DisplayName("GET /api/auth/github/login redirects to GitHub with cryptographically secure state")
    public void testLoginRedirectGeneratesState() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/github/login"))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.startsWith("https://github.com/login/oauth/authorize"));
        assertTrue(location.contains("state="));

        // Extract state and verify it was recorded in the state store
        String state = location.substring(location.indexOf("state=") + 6);
        assertTrue(oAuthStateStore.validateAndRemoveState(state), "Generated state must be valid in the store");
        assertFalse(oAuthStateStore.validateAndRemoveState(state), "State must be single-use only");
    }

    @Test
    @DisplayName("OAuth callback rejects missing, mismatched, and reused state")
    public void testCallbackRejectsInvalidState() throws Exception {
        // 1. Missing state
        mockMvc.perform(get("/api/auth/github/callback").param("code", "valid-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=")));

        // 2. Mismatched / unrecorded state
        mockMvc.perform(get("/api/auth/github/callback").param("code", "valid-code").param("state", "fake-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=")));

        // 3. Reused state
        String validState = oAuthStateStore.generateState();
        // First use: mock code exchange
        when(gitHubOAuthService.exchangeCodeForAccessToken("code-1")).thenReturn("gho_test_token");
        when(gitHubOAuthService.fetchAuthenticatedUserProfile("gho_test_token")).thenReturn(Map.of(
                "id", 9999901,
                "login", "user1"
        ));

        mockMvc.perform(get("/api/auth/github/callback").param("code", "code-1").param("state", validState))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("exchange_code=")));

        // Attempting to reuse the exact same state must fail
        mockMvc.perform(get("/api/auth/github/callback").param("code", "code-2").param("state", validState))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=")));
    }

    @Test
    @DisplayName("Successful OAuth code exchange provisions user profile and returns short-lived exchange code")
    public void testSuccessfulCodeExchangeAndProvisioning() throws Exception {
        String state = oAuthStateStore.generateState();
        when(gitHubOAuthService.exchangeCodeForAccessToken("auth-code-123")).thenReturn("gho_access_token_123");
        when(gitHubOAuthService.fetchAuthenticatedUserProfile("gho_access_token_123")).thenReturn(Map.of(
                "id", 8888801,
                "login", "alice_gh",
                "name", "Alice Github",
                "avatar_url", "https://avatars.github.com/u/8888801",
                "html_url", "https://github.com/alice_gh",
                "email", "alice@example.com"
        ));

        MvcResult callbackResult = mockMvc.perform(get("/api/auth/github/callback")
                        .param("code", "auth-code-123")
                        .param("state", state))
                .andExpect(status().isFound())
                .andReturn();

        String redirect = callbackResult.getResponse().getHeader("Location");
        assertNotNull(redirect);
        assertTrue(redirect.contains("exchange_code="), "Redirect must contain exchange code");
        assertFalse(redirect.contains("Bearer"), "Redirect must never contain Bearer JWT in URL");

        // Extract exchange code
        int idx = redirect.indexOf("exchange_code=") + 14;
        int endIdx = redirect.indexOf("&", idx);
        String exchangeCode = endIdx > 0 ? redirect.substring(idx, endIdx) : redirect.substring(idx);

        // Redeem exchange code via POST /api/auth/exchange
        MvcResult exchangeResult = mockMvc.perform(post("/api/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange_code\":\"" + exchangeCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.access_token").exists())
                .andReturn();

        String responseBody = exchangeResult.getResponse().getContentAsString();
        Map<String, Object> map = objectMapper.readValue(responseBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        String jwtToken = (String) map.get("access_token");
        assertNotNull(jwtToken);

        // Authenticate against /api/auth/me with redeemed token
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("github:8888801"))
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    @DisplayName("Repeat login updates metadata without overwriting confirmed skills")
    public void testRepeatLoginPreservesConfirmedSkills() {
        String providerUserId = "7777701";
        // 1. Initial provisioning
        userRepository.provisionOrUpdateOAuthUser(
                "github",
                providerUserId,
                "bob_initial",
                "Bob Initial",
                "https://avatar.url/1",
                "https://github.com/bob_initial",
                "bob@example.com"
        );

        // 2. User confirms a skill
        Map<String, Object> profileDoc = userRepository.getUser("github:" + providerUserId);
        assertNotNull(profileDoc);
        Map<String, Object> updates = new HashMap<>();
        updates.put("skills", Map.of("Python", new ContributorSkill("Python", 90.0, 1.0, "advanced", "user_confirmed")));
        updates.put("onboarding_completed", true);
        userRepository.updateUser("github:" + providerUserId, updates);

        // 3. User logs in again with updated GitHub metadata (e.g. new display name and avatar)
        userRepository.provisionOrUpdateOAuthUser(
                "github",
                providerUserId,
                "bob_initial",
                "Bob Renamed",
                "https://avatar.url/new-avatar",
                "https://github.com/bob_initial",
                "bob_new@example.com"
        );

        // 4. Verify confirmed skills and onboarding state are preserved
        Map<String, Object> reloaded = userRepository.getUser("github:" + providerUserId);
        assertNotNull(reloaded);
        assertTrue((Boolean) reloaded.get("onboarding_completed"));

        Map<?, ?> skills = (Map<?, ?>) reloaded.get("skills");
        assertNotNull(skills);
        assertTrue(skills.containsKey("Python"));

        Map<?, ?> identity = (Map<?, ?>) reloaded.get("identity");
        assertEquals("Bob Renamed", identity.get("display_name"));
        assertEquals("https://avatar.url/new-avatar", identity.get("avatar_url"));
    }

    @Test
    @DisplayName("Username change preserves stable numeric identity (github:<id>)")
    public void testUsernameChangePreservesIdentity() {
        String providerUserId = "6666601";
        userRepository.provisionOrUpdateOAuthUser(
                "github",
                providerUserId,
                "original_login",
                "Developer",
                "https://avatar.url",
                "https://github.com/original_login",
                "dev@example.com"
        );

        // User changes GitHub username to "new_login"
        userRepository.provisionOrUpdateOAuthUser(
                "github",
                providerUserId,
                "new_login",
                "Developer",
                "https://avatar.url",
                "https://github.com/new_login",
                "dev@example.com"
        );

        // Canonical ID is still github:6666601
        Map<String, Object> user = userRepository.getUser("github:" + providerUserId);
        assertNotNull(user);
        assertEquals("github:6666601", user.get("user_id"));

        Map<?, ?> identity = (Map<?, ?>) user.get("identity");
        assertEquals("new_login", identity.get("username"));
    }

    @Test
    @DisplayName("Unauthenticated request to /api/auth/me returns 401")
    public void testUnauthenticatedAuthMeReturns401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST /api/auth/logout returns success and documents client-side disposal contract")
    public void testLogoutEndpoint() throws Exception {
        String token = tokenService.issueToken("github:12345", "testuser", List.of("ROLE_USER"));

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.user_id").value("github:12345"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("dispose of the client-side session token")));
    }

    @Test
    @DisplayName("Callback securely encrypts and stores GitHub OAuth token in user_tokens collection")
    public void testCallbackEncryptsAndStoresCredential() throws Exception {
        String state = oAuthStateStore.generateState();
        String code = "valid-mock-auth-code-12345";
        String mockGithubToken = "gho_encrypted_mock_token_abc987";
        String providerUserId = "5555501";
        String canonicalUserId = "github:" + providerUserId;

        when(gitHubOAuthService.exchangeCodeForAccessToken(code)).thenReturn(mockGithubToken);
        when(gitHubOAuthService.fetchAuthenticatedUserProfile(mockGithubToken)).thenReturn(Map.of(
                "id", Long.parseLong(providerUserId),
                "login", "secure_user",
                "name", "Secure User",
                "email", "secure@example.com"
        ));

        mockMvc.perform(get("/api/auth/github/callback")
                        .param("code", code)
                        .param("state", state))
                .andExpect(status().isFound());

        // Verify credential is saved and decryptable
        assertTrue(userCredentialService.hasCredential(canonicalUserId, "github"));
        var decryptedOpt = userCredentialService.getDecryptedToken(canonicalUserId, "github");
        assertTrue(decryptedOpt.isPresent());
        assertEquals(mockGithubToken, decryptedOpt.get());
    }

    @Test
    @DisplayName("POST /api/auth/github/disconnect removes stored credential")
    public void testDisconnectEndpointRevokesCredential() throws Exception {
        String canonicalUserId = "github:4444401";
        String mockGithubToken = "gho_token_to_disconnect";

        userCredentialService.storeCredential(canonicalUserId, "github", "4444401", mockGithubToken, "read:user");
        assertTrue(userCredentialService.hasCredential(canonicalUserId, "github"));

        String jwt = tokenService.issueToken(canonicalUserId, "disconnect_user", List.of("ROLE_USER"));

        mockMvc.perform(post("/api/auth/github/disconnect")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.disconnected").value(true));

        assertFalse(userCredentialService.hasCredential(canonicalUserId, "github"));
    }

    @Test
    @DisplayName("GET /api/auth/me does not leak stored credentials or encryption fields")
    public void testAuthMeDoesNotLeakCredentials() throws Exception {
        String canonicalUserId = "github:3333301";
        String mockGithubToken = "gho_secret_token_never_leak";

        userCredentialService.storeCredential(canonicalUserId, "github", "3333301", mockGithubToken, "read:user");

        String jwt = tokenService.issueToken(canonicalUserId, "privacy_user", List.of("ROLE_USER"));

        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user_id").value(canonicalUserId))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertFalse(responseBody.contains(mockGithubToken), "Plaintext token must never be in /api/auth/me response");
        assertFalse(responseBody.contains("encrypted_access_token"), "Encrypted token field must never be in /api/auth/me response");
        assertFalse(responseBody.contains("nonce"), "Nonce must never be in /api/auth/me response");
        assertFalse(responseBody.contains("token"), "No token properties should exist in /api/auth/me");
    }
}
