package com.issuepilot.controller;

import com.issuepilot.config.AppProperties;
import com.issuepilot.config.MongoConfig;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.GitHubOAuthService;
import com.issuepilot.security.OAuthStateStore;
import com.issuepilot.security.TokenService;
import com.issuepilot.security.UserCredentialService;
import com.issuepilot.security.UserIdentityResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller handling GitHub OAuth 2.0 authentication, session handoff, and identity introspection.
 *
 * Endpoints:
 * - GET /api/auth/github/login (Public) -> Starts flow, redirects to GitHub with secure state.
 * - GET /api/auth/github/callback (Public) -> Exchanges code, provisions user, redirects to frontend with short-lived exchange code.
 * - POST /api/auth/exchange (Public) -> Redeems short-lived exchange code for application Bearer JWT (no tokens in URLs).
 * - GET /api/auth/me (Protected) -> Returns authenticated Issue Pilot identity and profile summary.
 * - POST /api/auth/logout (Protected) -> Informs client to discard stateless session token.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final GitHubOAuthService gitHubOAuthService;
    private final OAuthStateStore oAuthStateStore;
    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final UserCredentialService userCredentialService;
    private final MongoConfig mongoConfig;
    private final AppProperties properties;
    private final UserIdentityResolver userIdentityResolver;

    public AuthController(
            GitHubOAuthService gitHubOAuthService,
            OAuthStateStore oAuthStateStore,
            TokenService tokenService,
            UserRepository userRepository,
            UserCredentialService userCredentialService,
            MongoConfig mongoConfig,
            AppProperties properties,
            UserIdentityResolver userIdentityResolver
    ) {
        this.gitHubOAuthService = gitHubOAuthService;
        this.oAuthStateStore = oAuthStateStore;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.userCredentialService = userCredentialService;
        this.mongoConfig = mongoConfig;
        this.properties = properties;
        this.userIdentityResolver = userIdentityResolver;
    }

    /**
     * Initiates the GitHub OAuth 2.0 flow. Generates a cryptographically random single-use state
     * and redirects the browser to GitHub.
     */
    @GetMapping("/github/login")
    public ResponseEntity<Void> loginWithGitHub() {
        if (!gitHubOAuthService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub OAuth is not configured on this server.");
        }
        String state = oAuthStateStore.generateState();
        String authorizationUrl = gitHubOAuthService.buildAuthorizationUrl(state);

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(authorizationUrl));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    /**
     * OAuth callback invoked by GitHub redirect.
     * Validates state, exchanges code for access token server-side, provisions user profile,
     * and redirects to frontend with a short-lived exchange code.
     */
    @GetMapping("/github/callback")
    public ResponseEntity<Void> gitHubCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "error_description", required = false) String errorDescription
    ) {
        if (error != null && !error.isBlank()) {
            logger.warn("GitHub OAuth access denied or error: {}", error);
            String redirectUrl = properties.getFrontendAppUrl() + "/auth/callback?error="
                    + URLEncoder.encode(errorDescription != null ? errorDescription : error, StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        if (state == null || !oAuthStateStore.validateAndRemoveState(state)) {
            logger.warn("OAuth state validation failed: missing, mismatched, expired, or reused state token.");
            String redirectUrl = properties.getFrontendAppUrl() + "/auth/callback?error="
                    + URLEncoder.encode("Invalid or expired OAuth state session. Please try logging in again.", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        if (code == null || code.isBlank()) {
            String redirectUrl = properties.getFrontendAppUrl() + "/auth/callback?error="
                    + URLEncoder.encode("Missing GitHub authorization code.", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }

        try {
            // 1. Exchange code for access token server-side
            String githubAccessToken = gitHubOAuthService.exchangeCodeForAccessToken(code);

            // 2. Query GitHub user profile for stable numeric ID
            Map<String, Object> ghUser = gitHubOAuthService.fetchAuthenticatedUserProfile(githubAccessToken);
            Object numericId = ghUser.get("id");
            String username = (String) ghUser.getOrDefault("login", "");
            String displayName = (String) ghUser.getOrDefault("name", username);
            String avatarUrl = (String) ghUser.getOrDefault("avatar_url", "");
            String profileUrl = (String) ghUser.getOrDefault("html_url", "");
            String email = (String) ghUser.getOrDefault("email", "");

            String providerUserId = String.valueOf(numericId);
            String canonicalUserId = "github:" + providerUserId;

            // 3. Atomically find or create user profile in MongoDB (or sample mode if offline)
            boolean isNewUser = false;
            if (mongoConfig.isConnected()) {
                Map<String, Object> existing = userRepository.findByProviderIdentity("github", providerUserId);
                isNewUser = (existing == null);
                userRepository.provisionOrUpdateOAuthUser(
                        "github",
                        providerUserId,
                        username,
                        displayName,
                        avatarUrl,
                        profileUrl,
                        email
                );
            }

            // 4. Securely encrypt and store GitHub OAuth token in user_tokens collection
            userCredentialService.storeCredential(
                    canonicalUserId,
                    "github",
                    providerUserId,
                    githubAccessToken,
                    "read:user"
            );

            // 5. Issue Issue Pilot Application Session JWT
            String applicationJwt = tokenService.issueToken(
                    canonicalUserId,
                    username,
                    List.of("ROLE_USER"),
                    Map.of("provider", "github", "provider_user_id", providerUserId)
            );

            // 5. Create short-lived single-use exchange code to hand off to frontend securely
            String exchangeCode = oAuthStateStore.createExchangeCode(applicationJwt, canonicalUserId);

            String redirectUrl = properties.getFrontendAppUrl() + "/auth/callback?exchange_code="
                    + URLEncoder.encode(exchangeCode, StandardCharsets.UTF_8)
                    + "&new_user=" + isNewUser;

            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();

        } catch (Exception e) {
            logger.error("GitHub OAuth authentication failed: {}", e.getMessage());
            String redirectUrl = properties.getFrontendAppUrl() + "/auth/callback?error="
                    + URLEncoder.encode("Authentication failed: " + e.getMessage(), StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }
    }

    public static class ExchangeRequest {
        public String exchange_code;
    }

    /**
     * Redeems a short-lived authorization exchange code for the application session JWT.
     * Prevents tokens from ever being sent in URL parameters or browser history.
     */
    @PostMapping("/exchange")
    public Map<String, Object> exchangeToken(@RequestBody ExchangeRequest req) {
        if (req == null || req.exchange_code == null || req.exchange_code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'exchange_code' is required.");
        }

        String applicationJwt = oAuthStateStore.redeemExchangeCode(req.exchange_code);
        if (applicationJwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid, expired, or already-redeemed exchange code.");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("token_type", "Bearer");
        response.put("access_token", applicationJwt);
        response.put("expires_in", properties.getAppJwtExpirationSeconds());
        return response;
    }

    /**
     * Returns the authenticated user's Issue Pilot identity, profile, and onboarding status.
     * Requires valid Bearer application session token.
     */
    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(Authentication authentication) {
        String userId = userIdentityResolver.resolveUserId(authentication);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Active security principal is required.");
        }

        Map<String, Object> userDoc = null;
        if (mongoConfig.isConnected()) {
            userDoc = userRepository.getUser(userId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user_id", userId);
        response.put("authenticated", true);

        if (userDoc != null) {
            response.put("identity", userDoc.get("identity"));
            response.put("onboarding_completed", userDoc.getOrDefault("onboarding_completed", false));
            response.put("capability", userDoc.getOrDefault("capability", 70.0));
            response.put("skills_count", userDoc.get("skills") instanceof Map ? ((Map<?, ?>) userDoc.get("skills")).size() : 0);
            response.put("created_at", userDoc.get("created_at"));
            response.put("updated_at", userDoc.get("updated_at"));
        } else {
            response.put("onboarding_completed", false);
            response.put("note", "Profile not yet persisted or running in memory mode.");
        }

        return response;
    }

    /**
     * Logs out the user.
     *
     * Note on Stateless JWT Tokens:
     * In a stateless Bearer token architecture, logging out is accomplished by the client
     * discarding the stored token. The server does not maintain an active revocation blacklist
     * in this phase, so client-side token disposal is required.
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(Authentication authentication) {
        String userId = userIdentityResolver.resolveUserId(authentication);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("user_id", userId);
        response.put("message", "Logged out successfully. Please dispose of the client-side session token.");
        return response;
    }

    /**
     * Disconnects the third-party OAuth account and securely revokes stored credentials.
     * Requires valid Bearer application session token.
     */
    @PostMapping("/github/disconnect")
    public Map<String, Object> disconnectGitHub(Authentication authentication) {
        String userId = userIdentityResolver.resolveUserId(authentication);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Active security principal is required.");
        }
        boolean revoked = userCredentialService.revokeCredential(userId, "github");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("user_id", userId);
        response.put("provider", "github");
        response.put("disconnected", revoked);
        response.put("message", revoked ? "GitHub credentials removed successfully." : "No active GitHub credentials found.");
        return response;
    }
}

