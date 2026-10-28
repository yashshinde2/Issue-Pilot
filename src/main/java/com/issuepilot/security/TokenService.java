package com.issuepilot.security;

import com.issuepilot.config.AppProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Issues signed HMAC-SHA256 application session tokens (JWT) for authenticated
 * Issue Pilot users.
 *
 * Requirements enforced:
 * - Distinct from any upstream provider tokens (e.g. GitHub access tokens).
 * - Fails closed: Rejects token issuance if APP_JWT_SECRET is absent or shorter than 32 bytes (256 bits).
 * - Claims: sub, user_id, preferred_username, roles, iss, iat, exp.
 * - Never logs secrets or token payloads.
 */
@Service
public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

    private final AppProperties properties;

    public TokenService(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks if the signing key is configured with adequate entropy (at least 32 bytes / 256 bits).
     */
    public boolean isSigningConfigured() {
        String secret = properties.getAppJwtSecret();
        return secret != null && secret.trim().getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    /**
     * Issues an application session token for the given canonical user identity.
     *
     * @param userId canonical user ID (e.g. "github:12345678")
     * @param username user handle/display name (e.g. "octocat")
     * @param roles granted authorities (e.g. List.of("ROLE_USER"))
     * @return signed JWT compact serialization string
     */
    public String issueToken(String userId, String username, List<String> roles) {
        return issueToken(userId, username, roles, null);
    }

    /**
     * Issues an application session token with custom attributes.
     */
    public String issueToken(String userId, String username, List<String> roles, Map<String, Object> additionalClaims) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Cannot issue token: 'userId' is required.");
        }
        if (!isSigningConfigured()) {
            throw new IllegalStateException("Cannot issue token: APP_JWT_SECRET is not configured or shorter than 32 bytes.");
        }

        try {
            byte[] secretBytes = properties.getAppJwtSecret().trim().getBytes(StandardCharsets.UTF_8);
            JWSSigner signer = new MACSigner(secretBytes);

            Instant now = Instant.now();
            long validitySeconds = properties.getAppJwtExpirationSeconds();
            if (validitySeconds <= 0) {
                validitySeconds = 86400; // 24h fallback if invalid
            }
            Instant expiry = now.plusSeconds(validitySeconds);

            JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                    .subject(userId.trim())
                    .issuer(properties.getAppJwtIssuer())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(expiry))
                    .claim("user_id", userId.trim())
                    .claim("preferred_username", (username != null && !username.isBlank()) ? username.trim() : userId.trim())
                    .claim("roles", (roles != null && !roles.isEmpty()) ? roles : List.of("ROLE_USER"));

            if (additionalClaims != null) {
                for (Map.Entry<String, Object> entry : additionalClaims.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        claimsBuilder.claim(entry.getKey(), entry.getValue());
                    }
                }
            }

            SignedJWT signedJWT = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.HS256),
                    claimsBuilder.build()
            );

            signedJWT.sign(signer);
            return signedJWT.serialize();

        } catch (Exception e) {
            logger.error("Failed to sign application token: {}", e.getMessage());
            throw new IllegalStateException("Failed to issue application session token.", e);
        }
    }
}

