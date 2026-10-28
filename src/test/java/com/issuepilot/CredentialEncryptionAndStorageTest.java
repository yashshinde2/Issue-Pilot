package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.UserCredential;
import com.issuepilot.model.UserIdentity;
import com.issuepilot.repository.UserCredentialRepository;
import com.issuepilot.security.CredentialEncryptionService;
import com.issuepilot.security.UserCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4A: Test suite for AES-256-GCM Credential Encryption and Secure Storage.
 */
public class CredentialEncryptionAndStorageTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
        EnvLoader.loadEnv();
    }

    private static final String VALID_HEX_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SECOND_HEX_KEY = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    private AppProperties properties;
    private CredentialEncryptionService encryptionService;
    private UserCredentialRepository credentialRepository;
    private UserCredentialService credentialService;
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setup() {
        properties = new AppProperties();
        ReflectionTestUtils.setField(properties, "appEncryptionKey", VALID_HEX_KEY);

        encryptionService = new CredentialEncryptionService(properties);
        credentialRepository = new UserCredentialRepository(null); // Isolated in-memory mode
        credentialRepository.clearInMemoryStore();
        credentialService = new UserCredentialService(encryptionService, credentialRepository);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Encrypt and decrypt round-trip restores exact plaintext")
    public void testEncryptDecryptRoundTrip() {
        String token = "gho_test_sample_token_xyz_987654321";
        String canonicalUserId = "github:10001";
        String provider = "github";

        CredentialEncryptionService.EncryptedResult result = encryptionService.encrypt(token, provider, canonicalUserId);

        assertNotNull(result.getNonce());
        assertNotNull(result.getCiphertext());
        assertNotEquals(token, result.getCiphertext(), "Ciphertext must not be plaintext");

        String decrypted = encryptionService.decrypt(result.getNonce(), result.getCiphertext(), provider, canonicalUserId);
        assertEquals(token, decrypted, "Decrypted token must match original plaintext");
    }

    @Test
    @DisplayName("Separate encryptions of the same plaintext generate distinct nonces and ciphertexts")
    public void testUniqueNoncesAcrossEncryptions() {
        String token = "gho_identical_token_across_calls";
        String canonicalUserId = "github:10002";
        String provider = "github";

        CredentialEncryptionService.EncryptedResult enc1 = encryptionService.encrypt(token, provider, canonicalUserId);
        CredentialEncryptionService.EncryptedResult enc2 = encryptionService.encrypt(token, provider, canonicalUserId);

        assertNotEquals(enc1.getNonce(), enc2.getNonce(), "Nonces must be fresh per encryption");
        assertNotEquals(enc1.getCiphertext(), enc2.getCiphertext(), "Ciphertexts must differ due to distinct nonces");

        // Both decrypt to the same original token
        assertEquals(token, encryptionService.decrypt(enc1.getNonce(), enc1.getCiphertext(), provider, canonicalUserId));
        assertEquals(token, encryptionService.decrypt(enc2.getNonce(), enc2.getCiphertext(), provider, canonicalUserId));
    }

    @Test
    @DisplayName("Tampered ciphertext or authentication tag is rejected with SecurityException")
    public void testTamperedCiphertextRejected() {
        String token = "gho_sensitive_token_to_tamper";
        String canonicalUserId = "github:10003";
        String provider = "github";

        CredentialEncryptionService.EncryptedResult result = encryptionService.encrypt(token, provider, canonicalUserId);

        byte[] rawCiphertext = Base64.getDecoder().decode(result.getCiphertext());
        // Flip one bit in ciphertext
        rawCiphertext[rawCiphertext.length - 1] ^= 0x01;
        String tamperedCiphertext = Base64.getEncoder().encodeToString(rawCiphertext);

        assertThrows(SecurityException.class, () ->
                encryptionService.decrypt(result.getNonce(), tamperedCiphertext, provider, canonicalUserId)
        );
    }

    @Test
    @DisplayName("Tampered nonce is rejected with SecurityException")
    public void testTamperedNonceRejected() {
        String token = "gho_token_test_tampered_nonce";
        String canonicalUserId = "github:10004";
        String provider = "github";

        CredentialEncryptionService.EncryptedResult result = encryptionService.encrypt(token, provider, canonicalUserId);

        byte[] rawNonce = Base64.getDecoder().decode(result.getNonce());
        rawNonce[0] ^= 0x01;
        String tamperedNonce = Base64.getEncoder().encodeToString(rawNonce);

        assertThrows(SecurityException.class, () ->
                encryptionService.decrypt(tamperedNonce, result.getCiphertext(), provider, canonicalUserId)
        );
    }

    @Test
    @DisplayName("AAD mismatch prevents retrieving or decrypting credentials under a different user identity")
    public void testAadIdentityMismatchRejected() {
        String token = "gho_userA_token";
        String canonicalUserA = "github:10005";
        String canonicalUserB = "github:10006";
        String provider = "github";

        CredentialEncryptionService.EncryptedResult result = encryptionService.encrypt(token, provider, canonicalUserA);

        // Attempting to decrypt with User B's canonical identity must fail GCM tag verification
        assertThrows(SecurityException.class, () ->
                encryptionService.decrypt(result.getNonce(), result.getCiphertext(), provider, canonicalUserB)
        );
    }

    @Test
    @DisplayName("Decryption with wrong encryption key fails closed")
    public void testWrongKeyFailsClosed() {
        String token = "gho_token_wrong_key";
        String canonicalUserId = "github:10007";
        String provider = "github";

        CredentialEncryptionService.EncryptedResult result = encryptionService.encrypt(token, provider, canonicalUserId);

        // Switch key to another valid 256-bit key
        ReflectionTestUtils.setField(properties, "appEncryptionKey", SECOND_HEX_KEY);

        assertThrows(SecurityException.class, () ->
                encryptionService.decrypt(result.getNonce(), result.getCiphertext(), provider, canonicalUserId)
        );
    }

    @Test
    @DisplayName("Missing, blank, or improperly sized encryption key fails closed")
    public void testMissingOrInvalidKeyFailsClosed() {
        ReflectionTestUtils.setField(properties, "appEncryptionKey", null);
        assertThrows(IllegalStateException.class, () ->
                encryptionService.encrypt("gho_token", "github", "github:10008")
        );

        ReflectionTestUtils.setField(properties, "appEncryptionKey", "too_short_key");
        assertThrows(IllegalStateException.class, () ->
                encryptionService.encrypt("gho_token", "github", "github:10008")
        );
    }

    @Test
    @DisplayName("Base64 encoded 256-bit key is properly supported")
    public void testBase64KeySupport() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) keyBytes[i] = (byte) (i + 1);
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        ReflectionTestUtils.setField(properties, "appEncryptionKey", base64Key);

        String token = "gho_token_base64_key";
        String canonicalUserId = "github:10009";
        String provider = "github";

        CredentialEncryptionService.EncryptedResult result = encryptionService.encrypt(token, provider, canonicalUserId);
        String decrypted = encryptionService.decrypt(result.getNonce(), result.getCiphertext(), provider, canonicalUserId);
        assertEquals(token, decrypted);
    }

    @Test
    @DisplayName("UserCredentialService stores, retrieves, and revokes credentials correctly")
    public void testCredentialServiceLifecycle() {
        String canonicalUserId = "github:10010";
        String providerUserId = "10010";
        String token = "gho_full_lifecycle_token";

        // 1. Store
        credentialService.storeCredential(canonicalUserId, "github", providerUserId, token, "read:user");
        assertTrue(credentialService.hasCredential(canonicalUserId, "github"));

        // 2. Retrieve & Decrypt
        Optional<String> retrieved = credentialService.getDecryptedToken(canonicalUserId, "github");
        assertTrue(retrieved.isPresent());
        assertEquals(token, retrieved.get());

        // 3. Replace token
        String newToken = "gho_new_rotated_token";
        credentialService.storeCredential(canonicalUserId, "github", providerUserId, newToken, "read:user");
        Optional<String> updated = credentialService.getDecryptedToken(canonicalUserId, "github");
        assertTrue(updated.isPresent());
        assertEquals(newToken, updated.get());

        // 4. Revoke
        boolean revoked = credentialService.revokeCredential(canonicalUserId, "github");
        assertTrue(revoked);
        assertFalse(credentialService.hasCredential(canonicalUserId, "github"));
        assertTrue(credentialService.getDecryptedToken(canonicalUserId, "github").isEmpty());
    }

    @Test
    @DisplayName("Sensitive credential fields are excluded from JSON serialization and ContributorProfile")
    public void testSensitiveFieldsExcludedFromSerialization() throws Exception {
        UserCredential cred = new UserCredential("github:10011", "github", "10011");
        cred.setEncryptedAccessToken("enc_token_value_xyz");
        cred.setNonce("nonce_value_123");
        cred.setScope("read:user");

        String credJson = objectMapper.writeValueAsString(cred);
        assertFalse(credJson.contains("enc_token_value_xyz"), "Encrypted access token must not be in JSON output");
        assertFalse(credJson.contains("encrypted_access_token"), "Field encrypted_access_token must be ignored");
        assertFalse(credJson.contains("nonce_value_123"), "Nonce must not be in JSON output");
        assertFalse(credJson.contains("\"nonce\""), "Field nonce must be ignored");

        // Verify ContributorProfile has no token attributes
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId("github:10011");
        profile.setIdentity(new UserIdentity("github", "10011", "octocat"));
        String profileJson = objectMapper.writeValueAsString(profile);
        assertFalse(profileJson.contains("token"), "Profile must never serialize credential tokens");
        assertFalse(profileJson.contains("encrypted"), "Profile must not contain encrypted fields");
    }
}
