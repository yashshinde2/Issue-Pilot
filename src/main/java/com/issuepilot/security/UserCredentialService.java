package com.issuepilot.security;

import com.issuepilot.model.UserCredential;
import com.issuepilot.repository.UserCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * High-level service managing the lifecycle of third-party OAuth credentials.
 *
 * Requirements:
 * - Coordinates AES-256-GCM encryption with repository persistence.
 * - Enforces provider binding and identity verification.
 * - Handles token replacement, revocation, and validation.
 * - Never logs plaintext credentials.
 */
@Service
public class UserCredentialService {

    private static final Logger logger = LoggerFactory.getLogger(UserCredentialService.class);

    private final CredentialEncryptionService encryptionService;
    private final UserCredentialRepository credentialRepository;

    public UserCredentialService(
            CredentialEncryptionService encryptionService,
            UserCredentialRepository credentialRepository
    ) {
        this.encryptionService = encryptionService;
        this.credentialRepository = credentialRepository;
    }

    /**
     * Encrypts and persists a new or updated third-party OAuth access token.
     *
     * @param canonicalUserId Target canonical user identity (e.g., "github:8888801")
     * @param provider OAuth provider (e.g., "github")
     * @param providerUserId Stable numeric provider user ID
     * @param plaintextToken Plaintext access token received from provider
     * @param scope Permitted OAuth scope
     */
    public void storeCredential(
            String canonicalUserId,
            String provider,
            String providerUserId,
            String plaintextToken,
            String scope
    ) {
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            throw new IllegalArgumentException("canonicalUserId is required to store credentials.");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required to store credentials.");
        }
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new IllegalArgumentException("plaintextToken cannot be empty.");
        }

        // Encrypt with fresh 96-bit nonce and identity-bound AAD
        CredentialEncryptionService.EncryptedResult result = encryptionService.encrypt(
                plaintextToken.trim(),
                provider.trim().toLowerCase(),
                canonicalUserId.trim()
        );

        UserCredential credential = new UserCredential(canonicalUserId.trim(), provider.trim().toLowerCase(), providerUserId != null ? providerUserId.trim() : "");
        credential.setTokenType("bearer");
        credential.setScope(scope != null ? scope.trim() : "read:user");
        credential.setEncryptedAccessToken(result.getCiphertext());
        credential.setNonce(result.getNonce());
        credential.setKeyVersion("v1");

        credentialRepository.save(credential);
        logger.info("Successfully encrypted and stored OAuth credential for user '{}' provider '{}'.", canonicalUserId, provider);
    }

    /**
     * Retrieves and decrypts the access token for the given user and provider.
     *
     * @param canonicalUserId Canonical user ID derived from validated application principal
     * @param provider Expected provider (e.g., "github")
     * @return Decrypted plaintext access token, or empty if not found
     */
    public Optional<String> getDecryptedToken(String canonicalUserId, String provider) {
        if (canonicalUserId == null || canonicalUserId.isBlank() || provider == null || provider.isBlank()) {
            return Optional.empty();
        }

        Optional<UserCredential> credOpt = credentialRepository.findByCanonicalUserId(canonicalUserId.trim());
        if (credOpt.isEmpty()) {
            return Optional.empty();
        }

        UserCredential cred = credOpt.get();
        if (!provider.trim().equalsIgnoreCase(cred.getProvider())) {
            logger.warn("Credential provider mismatch for user '{}': expected '{}', found '{}'", canonicalUserId, provider, cred.getProvider());
            return Optional.empty();
        }

        String decrypted = encryptionService.decrypt(
                cred.getNonce(),
                cred.getEncryptedAccessToken(),
                cred.getProvider(),
                canonicalUserId.trim()
        );

        return Optional.of(decrypted);
    }

    /**
     * Revokes (deletes) the stored credential for the user.
     */
    public boolean revokeCredential(String canonicalUserId, String provider) {
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            return false;
        }

        Optional<UserCredential> credOpt = credentialRepository.findByCanonicalUserId(canonicalUserId.trim());
        if (credOpt.isPresent()) {
            UserCredential cred = credOpt.get();
            if (provider == null || provider.trim().equalsIgnoreCase(cred.getProvider())) {
                boolean deleted = credentialRepository.deleteByCanonicalUserId(canonicalUserId.trim());
                if (deleted) {
                    logger.info("Revoked and deleted OAuth credential for user '{}'.", canonicalUserId);
                }
                return deleted;
            }
        }
        return false;
    }

    /**
     * Checks whether an encrypted credential exists for the user.
     */
    public boolean hasCredential(String canonicalUserId, String provider) {
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            return false;
        }
        return credentialRepository.findByCanonicalUserId(canonicalUserId.trim())
                .filter(c -> provider == null || provider.trim().equalsIgnoreCase(c.getProvider()))
                .isPresent();
    }
}

