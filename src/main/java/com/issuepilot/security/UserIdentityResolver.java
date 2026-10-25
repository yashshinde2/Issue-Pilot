package com.issuepilot.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves acting user identities from authenticated security principals
 * and enforces cross-user ownership boundaries.
 *
 * Implements Stage 1 Identity Contract:
 * - Acting identity is derived exclusively from the validated Principal.
 * - Mapping precedence:
 *   1. "user_id" claim
 *   2. "preferred_username" claim
 *   3. "sub" (Subject) claim
 *   4. Authentication.getName() fallback (e.g. for mock authentication)
 * - Spoofed user IDs and unauthorized cross-user operations throw SecurityException.
 */
@Component
public class UserIdentityResolver {

    /**
     * Resolves the canonical Issue Pilot user ID from the authenticated security principal.
     */
    public String resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            return resolveFromJwt(jwt).orElseGet(authentication::getName);
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return resolveFromJwt(jwt).orElseGet(authentication::getName);
        }

        String name = authentication.getName();
        if (name != null && !"anonymousUser".equals(name)) {
            return name.trim();
        }

        return null;
    }

    private Optional<String> resolveFromJwt(Jwt jwt) {
        if (jwt == null) return Optional.empty();

        String userIdClaim = jwt.getClaimAsString("user_id");
        if (userIdClaim != null && !userIdClaim.isBlank()) {
            return Optional.of(userIdClaim.trim());
        }

        String usernameClaim = jwt.getClaimAsString("preferred_username");
        if (usernameClaim != null && !usernameClaim.isBlank()) {
            return Optional.of(usernameClaim.trim());
        }

        String subClaim = jwt.getSubject();
        if (subClaim != null && !subClaim.isBlank()) {
            return Optional.of(subClaim.trim());
        }

        return Optional.empty();
    }

    /**
     * Asserts that the authenticated principal is authorized to perform operations
     * on behalf of the target user ID. Throws SecurityException (HTTP 403) on mismatch.
     */
    public void assertUserOwnership(Authentication authentication, String targetUserId) {
        String actingUserId = resolveUserId(authentication);
        if (actingUserId == null || actingUserId.isBlank()) {
            throw new SecurityException("Unauthenticated: Active security principal is required.");
        }
        if (targetUserId != null && !targetUserId.isBlank() && !actingUserId.equals(targetUserId)) {
            throw new SecurityException("Access denied: Authenticated user '" + actingUserId
                    + "' is not authorized to access or modify resources belonging to '" + targetUserId + "'.");
        }
    }
}
