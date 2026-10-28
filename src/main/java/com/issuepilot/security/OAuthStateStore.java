package com.issuepilot.security;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages cryptographically random OAuth state tokens and short-lived authorization exchange codes.
 *
 * Requirements enforced:
 * - Cryptographically secure random generation (SecureRandom).
 * - Single-use enforcement (invalidated immediately upon validation).
 * - Automatic expiration (OAuth state: 10 minutes; exchange codes: 2 minutes).
 * - Thread-safe in-memory cache with eviction to prevent memory leaks.
 */
@Service
public class OAuthStateStore {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long STATE_EXPIRATION_SECONDS = 600; // 10 minutes
    private static final long CODE_EXPIRATION_SECONDS = 120; // 2 minutes

    private record StateEntry(String state, Instant expiresAt) {}
    private record ExchangeCodeEntry(String token, String userId, Instant expiresAt) {}

    private final Map<String, StateEntry> states = new ConcurrentHashMap<>();
    private final Map<String, ExchangeCodeEntry> exchangeCodes = new ConcurrentHashMap<>();

    /**
     * Generates a 256-bit cryptographically secure URL-safe random state token.
     */
    public String generateState() {
        cleanExpiredEntries();
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        states.put(state, new StateEntry(state, Instant.now().plusSeconds(STATE_EXPIRATION_SECONDS)));
        return state;
    }

    /**
     * Validates and invalidates an OAuth state token. Returns true only once for a valid non-expired state.
     */
    public boolean validateAndRemoveState(String state) {
        cleanExpiredEntries();
        if (state == null || state.isBlank()) {
            return false;
        }
        StateEntry entry = states.remove(state.trim());
        if (entry == null) {
            return false;
        }
        return Instant.now().isBefore(entry.expiresAt());
    }

    /**
     * Creates a short-lived single-use exchange code mapping to an issued application JWT.
     */
    public String createExchangeCode(String applicationJwt, String userId) {
        cleanExpiredEntries();
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        exchangeCodes.put(code, new ExchangeCodeEntry(applicationJwt, userId, Instant.now().plusSeconds(CODE_EXPIRATION_SECONDS)));
        return code;
    }

    /**
     * Redeems a short-lived exchange code for the application session JWT.
     * Single-use: The code is immediately removed from the store.
     */
    public String redeemExchangeCode(String code) {
        cleanExpiredEntries();
        if (code == null || code.isBlank()) {
            return null;
        }
        ExchangeCodeEntry entry = exchangeCodes.remove(code.trim());
        if (entry == null) {
            return null;
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            return null;
        }
        return entry.token();
    }

    private void cleanExpiredEntries() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, StateEntry>> stateIt = states.entrySet().iterator();
        while (stateIt.hasNext()) {
            if (now.isAfter(stateIt.next().getValue().expiresAt())) {
                stateIt.remove();
            }
        }

        Iterator<Map.Entry<String, ExchangeCodeEntry>> codeIt = exchangeCodes.entrySet().iterator();
        while (codeIt.hasNext()) {
            if (now.isAfter(codeIt.next().getValue().expiresAt())) {
                codeIt.remove();
            }
        }
    }
}

