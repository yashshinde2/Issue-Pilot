package com.issuepilot.security;

import com.issuepilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Provides AES-256-GCM authenticated encryption and decryption for third-party OAuth credentials.
 *
 * Requirements:
 * - Requires dedicated 256-bit APP_ENCRYPTION_KEY outside source control.
 * - Generates a fresh cryptographically secure 96-bit nonce per encryption.
 * - Authenticates ciphertext with a 128-bit authentication tag.
 * - Binds ciphertext to the canonical user identity and provider via Authenticated Additional Data (AAD).
 * - Fails closed on absent, invalid, or mismatched key/data. Never falls back to plaintext.
 * - Zero plaintext/ciphertext leakage in logs or exceptions.
 */
@Service
public class CredentialEncryptionService {

    private static final Logger logger = LoggerFactory.getLogger(CredentialEncryptionService.class);

    private static final String ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_NONCE_LENGTH_BYTES = 12; // 96 bits
    private static final int GCM_TAG_LENGTH_BITS = 128; // 128-bit authentication tag
    private static final int KEY_LENGTH_BYTES = 32; // 256-bit key

    private final SecureRandom secureRandom = new SecureRandom();
    private final AppProperties properties;

    public CredentialEncryptionService(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves and validates the 256-bit SecretKey from APP_ENCRYPTION_KEY.
     * Fails closed if key is missing, improperly encoded, or not exactly 32 bytes (256 bits).
     */
    public SecretKey getSecretKey() {
        String rawKey = properties.getAppEncryptionKey();
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY is not configured. Encrypted credential storage requires a 256-bit key.");
        }

        byte[] keyBytes = decodeKey(rawKey.trim());
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must represent exactly 256 bits (32 bytes). Found: " + (keyBytes.length * 8) + " bits.");
        }

        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Supports both 64-character hexadecimal and standard/URL-safe Base64 encodings.
     */
    private byte[] decodeKey(String key) {
        // 1. Try 64-character Hexadecimal
        if (key.length() == 64 && key.matches("^[0-9a-fA-F]{64}$")) {
            return HexFormat.of().parseHex(key);
        }

        // 2. Try Base64
        try {
            return Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException base64Ex) {
            try {
                return Base64.getUrlDecoder().decode(key);
            } catch (IllegalArgumentException urlBase64Ex) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY could not be decoded. Must be 64-character hex or 44-character Base64 string.");
            }
        }
    }

    /**
     * Generates authenticated additional data (AAD) binding the ciphertext to the provider
     * and canonical user ID to prevent cross-user ciphertext substitution attacks.
     */
    public byte[] buildAad(String provider, String canonicalUserId) {
        if (provider == null || provider.isBlank() || canonicalUserId == null || canonicalUserId.isBlank()) {
            throw new IllegalArgumentException("Provider and canonicalUserId are required to form AAD.");
        }
        String aadString = "provider:" + provider.trim().toLowerCase() + "|user:" + canonicalUserId.trim();
        return aadString.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Encrypts plaintext using AES-256-GCM with a fresh random 96-bit nonce and AAD.
     *
     * @param plaintext Plaintext credential to protect
     * @param provider Provider identifier (e.g., "github")
     * @param canonicalUserId Canonical Issue Pilot user ID (e.g., "github:12345678")
     * @return EncryptedResult containing Base64 nonce and Base64 ciphertext
     */
    public EncryptedResult encrypt(String plaintext, String provider, String canonicalUserId) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Plaintext cannot be empty.");
        }

        SecretKey key = getSecretKey();
        byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            cipher.updateAAD(buildAad(provider, canonicalUserId));

            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            String encodedNonce = Base64.getEncoder().encodeToString(nonce);
            String encodedCiphertext = Base64.getEncoder().encodeToString(ciphertextWithTag);

            return new EncryptedResult(encodedNonce, encodedCiphertext);

        } catch (Exception e) {
            logger.error("Failed to encrypt OAuth credential: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("Encryption failed.", e);
        }
    }

    /**
     * Decrypts AES-256-GCM ciphertext, verifying authentication tag and AAD against canonical identity.
     *
     * @param encodedNonce Base64-encoded 96-bit nonce
     * @param encodedCiphertext Base64-encoded ciphertext with tag
     * @param provider Expected provider identifier
     * @param canonicalUserId Expected canonical user identity
     * @return Decrypted plaintext credential
     */
    public String decrypt(String encodedNonce, String encodedCiphertext, String provider, String canonicalUserId) {
        if (encodedNonce == null || encodedNonce.isBlank() || encodedCiphertext == null || encodedCiphertext.isBlank()) {
            throw new IllegalArgumentException("Nonce and ciphertext are required for decryption.");
        }

        SecretKey key = getSecretKey();

        byte[] nonce;
        byte[] ciphertextWithTag;
        try {
            nonce = Base64.getDecoder().decode(encodedNonce);
            ciphertextWithTag = Base64.getDecoder().decode(encodedCiphertext);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Malformed Base64 payload in credential storage.", e);
        }

        if (nonce.length != GCM_NONCE_LENGTH_BYTES) {
            throw new SecurityException("Invalid GCM nonce length: expected " + GCM_NONCE_LENGTH_BYTES + " bytes.");
        }

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            cipher.updateAAD(buildAad(provider, canonicalUserId));

            byte[] plaintextBytes = cipher.doFinal(ciphertextWithTag);
            return new String(plaintextBytes, StandardCharsets.UTF_8);

        } catch (javax.crypto.AEADBadTagException e) {
            logger.warn("Credential decryption failed: AEAD authentication tag mismatch or tampered data.");
            throw new SecurityException("Credential verification failed: authentication tag mismatch or invalid key.");
        } catch (Exception e) {
            logger.error("Credential decryption error: {}", e.getClass().getSimpleName());
            throw new SecurityException("Decryption operation failed.", e);
        }
    }

    public static class EncryptedResult {
        private final String nonce;
        private final String ciphertext;

        public EncryptedResult(String nonce, String ciphertext) {
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }

        public String getNonce() {
            return nonce;
        }

        public String getCiphertext() {
            return ciphertext;
        }
    }
}

