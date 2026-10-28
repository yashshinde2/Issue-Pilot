package com.issuepilot.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles server-side communication with GitHub OAuth and GitHub REST API.
 *
 * Requirements:
 * - Keeps GITHUB_CLIENT_SECRET server-side.
 * - Extracts stable numeric GitHub user ID.
 * - Minimum requested scopes: read:user (for user profile and public repos).
 * - Never logs tokens, authorization codes, or secrets.
 */
@Service
public class GitHubOAuthService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubOAuthService.class);

    private final AppProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubOAuthService(AppProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new RestTemplate());
    }

    public GitHubOAuthService(AppProperties properties, ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate != null ? restTemplate : new RestTemplate();
    }

    public boolean isConfigured() {
        String clientId = properties.getGithubClientId();
        String clientSecret = properties.getGithubClientSecret();
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * Builds the authoritative GitHub OAuth login URL with cryptographically secure state.
     */
    public String buildAuthorizationUrl(String state) {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub OAuth is not configured. GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET are required.");
        }
        String redirectUri = URLEncoder.encode(properties.getGithubOAuthRedirectUri(), StandardCharsets.UTF_8);
        String scope = URLEncoder.encode("read:user", StandardCharsets.UTF_8);
        String encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8);

        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + properties.getGithubClientId().trim()
                + "&redirect_uri=" + redirectUri
                + "&scope=" + scope
                + "&state=" + encodedState;
    }

    /**
     * Exchanges an authorization code for a temporary GitHub access token.
     * The GitHub access token remains strictly server-side.
     */
    public String exchangeCodeForAccessToken(String code) {
        if (!isConfigured()) {
            throw new IllegalStateException("GitHub OAuth is not configured.");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Authorization code cannot be empty.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", properties.getGithubClientId().trim());
            body.add("client_secret", properties.getGithubClientSecret().trim());
            body.add("code", code.trim());
            body.add("redirect_uri", properties.getGithubOAuthRedirectUri().trim());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    properties.getGithubOAuthTokenUrl(),
                    request,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                logger.error("GitHub token exchange returned non-success HTTP status: {}", response.getStatusCode());
                throw new IllegalStateException("Failed to exchange GitHub authorization code.");
            }

            Map<String, Object> map = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
            if (map.containsKey("error")) {
                String error = (String) map.get("error");
                String errorDesc = (String) map.getOrDefault("error_description", error);
                logger.warn("GitHub OAuth token exchange failed: {}", error);
                throw new IllegalArgumentException("GitHub OAuth error: " + errorDesc);
            }

            String accessToken = (String) map.get("access_token");
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("GitHub did not return an access token.");
            }

            return accessToken.trim();

        } catch (RestClientResponseException e) {
            logger.error("GitHub OAuth API error: HTTP {}", e.getStatusCode());
            throw new IllegalStateException("GitHub OAuth exchange failed: " + e.getResponseBodyAsString(), e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error exchanging GitHub code: {}", e.getMessage());
            throw new IllegalStateException("Unexpected error during GitHub token exchange.", e);
        }
    }

    /**
     * Retrieves the authenticated GitHub user profile using the server-side access token.
     * Extracts the stable numeric GitHub ID.
     */
    public Map<String, Object> fetchAuthenticatedUserProfile(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("GitHub access token is required.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.set("Accept", "application/vnd.github.v3+json");
            headers.set("User-Agent", "IssuePilot-Backend");

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    properties.getGithubUserApiUrl(),
                    HttpMethod.GET,
                    request,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException("Failed to retrieve user profile from GitHub API.");
            }

            Map<String, Object> profile = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
            Object idObj = profile.get("id");
            if (idObj == null) {
                throw new IllegalStateException("GitHub user profile did not contain a stable numeric 'id'.");
            }

            return profile;

        } catch (RestClientResponseException e) {
            logger.error("GitHub User API error: HTTP {}", e.getStatusCode());
            throw new IllegalStateException("Failed to query GitHub User API: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("Unexpected error retrieving GitHub user profile: {}", e.getMessage());
            throw new IllegalStateException("Unexpected error during GitHub user profile retrieval.", e);
        }
    }
}
