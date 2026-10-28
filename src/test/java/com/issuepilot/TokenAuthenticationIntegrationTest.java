package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.security.TokenService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Token Service & Security Integration Test Suite.
 *
 * Verifies:
 * 1. Valid application token authentication via Authorization: Bearer <token>.
 * 2. Missing, malformed, tampered, expired, and wrong-issuer tokens are rejected (401).
 * 3. Tokens missing required identity claims are rejected (401).
 * 4. Cross-user operations with real tokens return 403 Forbidden.
 * 5. Unauthenticated requests to protected endpoints return 401 Unauthorized.
 * 6. Public endpoints (/api/health, /api/issues) remain accessible without credentials.
 * 7. Ingestion endpoints are protected.
 * 8. Missing signing key fails closed (cannot issue tokens).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "APP_JWT_SECRET=test_very_secure_secret_key_with_at_least_32_bytes_of_entropy_12345",
        "APP_JWT_ISSUER=https://issuepilot-test.com",
        "APP_JWT_EXPIRATION_SECONDS=3600"
})
public class TokenAuthenticationIntegrationTest {

    static {
        EnvLoader.loadEnv();
    }

    private static final String TEST_SECRET = "test_very_secure_secret_key_with_at_least_32_bytes_of_entropy_12345";
    private static final String TEST_ISSUER = "https://issuepilot-test.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AppProperties appProperties;

    @Test
    @DisplayName("TokenService issues valid JWT and authenticates against protected endpoint")
    public void testValidApplicationTokenAuthenticates() throws Exception {
        String token = tokenService.issueToken("github:45291034", "octocat", List.of("ROLE_USER"));
        assertNotNull(token);
        assertFalse(token.isBlank());

        mockMvc.perform(get("/api/votes/github:45291034")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value("github:45291034"));
    }

    @Test
    @DisplayName("Unauthenticated request to protected endpoint returns 401")
    public void testUnauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/votes/github:12345"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("Malformed token returns 401 Unauthorized")
    public void testMalformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/votes/github:12345")
                        .header("Authorization", "Bearer not.a.valid.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Tampered token signature is rejected with 401")
    public void testTamperedSignatureReturns401() throws Exception {
        String validToken = tokenService.issueToken("github:45291034", "octocat", List.of("ROLE_USER"));
        // Tamper signature by altering the last character
        String tampered = validToken.substring(0, validToken.length() - 2) + "aa";

        mockMvc.perform(get("/api/votes/github:45291034")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Expired token is rejected with 401")
    public void testExpiredTokenReturns401() throws Exception {
        // Manually construct an expired token signed with the correct key
        Instant now = Instant.now().minusSeconds(7200); // 2 hours ago
        Instant expiredAt = now.plusSeconds(3600); // expired 1 hour ago

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("github:45291034")
                .issuer(TEST_ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiredAt))
                .claim("user_id", "github:45291034")
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        String expiredToken = signedJWT.serialize();

        mockMvc.perform(get("/api/votes/github:45291034")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Token with wrong issuer is rejected with 401")
    public void testWrongIssuerTokenReturns401() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("github:45291034")
                .issuer("https://rogue-issuer-not-matching.com")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("user_id", "github:45291034")
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        String wrongIssuerToken = signedJWT.serialize();

        mockMvc.perform(get("/api/votes/github:45291034")
                        .header("Authorization", "Bearer " + wrongIssuerToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Token missing identity claims ('user_id' and 'sub') is rejected with 401")
    public void testMissingIdentityClaimsReturns401() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(TEST_ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3600)))
                .claim("arbitrary", "value")
                // intentionally omitted sub and user_id
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        String noIdentityToken = signedJWT.serialize();

        mockMvc.perform(get("/api/votes/github:45291034")
                        .header("Authorization", "Bearer " + noIdentityToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("Cross-user operation returns 403 Forbidden with application token")
    public void testCrossUserAccessReturns403() throws Exception {
        // Token for Alice
        String aliceToken = tokenService.issueToken("github:alice", "alice", List.of("ROLE_USER"));

        // Attempting to access Bob's votes with Alice's token
        mockMvc.perform(get("/api/votes/github:bob")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Access denied")));
    }

    @Test
    @DisplayName("Public endpoints remain accessible without tokens")
    public void testPublicEndpointsRemainOpen() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/issues"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/simulator/sample-data"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Ingestion endpoints are protected and reject unauthenticated requests")
    public void testIngestionEndpointsProtected() throws Exception {
        mockMvc.perform(post("/api/ingestion/discover"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Missing signing configuration fails closed")
    public void testMissingSigningKeyFailsClosed() {
        AppProperties emptyProps = new AppProperties();
        TokenService unconfiguredService = new TokenService(emptyProps);

        assertFalse(unconfiguredService.isSigningConfigured());
        assertThrows(IllegalStateException.class, () ->
                unconfiguredService.issueToken("user1", "user1", List.of("ROLE_USER")));
    }
}
