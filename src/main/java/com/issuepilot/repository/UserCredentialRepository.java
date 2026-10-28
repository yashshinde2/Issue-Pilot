package com.issuepilot.repository;

import com.issuepilot.model.UserCredential;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated repository for encrypted OAuth credentials stored in the 'user_tokens' collection.
 *
 * Requirements:
 * - Isolated from user profile documents.
 * - Upserts on canonical_user_id.
 * - Provides an in-memory fallback when MongoDB is disconnected or in test fixtures.
 * - Explicit field mapping guarantees @JsonIgnore fields are stored in MongoDB but omitted from API output.
 */
@Repository
public class UserCredentialRepository {

    private static final Logger logger = LoggerFactory.getLogger(UserCredentialRepository.class);
    private static final String COLLECTION_NAME = "user_tokens";

    private final MongoOperations mongoOperations;
    private final Map<String, Document> inMemoryStore = new ConcurrentHashMap<>();

    public UserCredentialRepository(MongoOperations mongoOperations) {
        this.mongoOperations = mongoOperations;
    }

    /**
     * Converts UserCredential model to a MongoDB Document.
     */
    private Document toDocument(UserCredential credential) {
        Document doc = new Document("canonical_user_id", credential.getCanonicalUserId())
                .append("provider", credential.getProvider())
                .append("provider_user_id", credential.getProviderUserId())
                .append("token_type", credential.getTokenType())
                .append("scope", credential.getScope())
                .append("encrypted_access_token", credential.getEncryptedAccessToken())
                .append("nonce", credential.getNonce())
                .append("key_version", credential.getKeyVersion())
                .append("created_at", credential.getCreatedAt())
                .append("updated_at", credential.getUpdatedAt());
        return doc;
    }

    /**
     * Converts a MongoDB Document back to a UserCredential model.
     */
    private UserCredential fromDocument(Document doc) {
        if (doc == null) return null;
        UserCredential cred = new UserCredential();
        cred.setCanonicalUserId(doc.getString("canonical_user_id"));
        cred.setProvider(doc.getString("provider"));
        cred.setProviderUserId(doc.getString("provider_user_id"));
        cred.setTokenType(doc.getString("token_type"));
        cred.setScope(doc.getString("scope"));
        cred.setEncryptedAccessToken(doc.getString("encrypted_access_token"));
        cred.setNonce(doc.getString("nonce"));
        cred.setKeyVersion(doc.getString("key_version"));
        cred.setCreatedAt(doc.getString("created_at"));
        cred.setUpdatedAt(doc.getString("updated_at"));
        return cred;
    }

    /**
     * Upserts an encrypted credential bound to the canonical user ID.
     */
    public void save(UserCredential credential) {
        if (credential == null || credential.getCanonicalUserId() == null || credential.getCanonicalUserId().isBlank()) {
            throw new IllegalArgumentException("Credential with valid canonicalUserId is required.");
        }

        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        if (credential.getCreatedAt() == null || credential.getCreatedAt().isBlank()) {
            credential.setCreatedAt(nowIso);
        }
        credential.setUpdatedAt(nowIso);

        Document doc = toDocument(credential);

        if (mongoOperations != null) {
            try {
                Document filter = new Document("canonical_user_id", credential.getCanonicalUserId());
                Document setDoc = new Document("provider", credential.getProvider())
                        .append("provider_user_id", credential.getProviderUserId())
                        .append("token_type", credential.getTokenType())
                        .append("scope", credential.getScope())
                        .append("encrypted_access_token", credential.getEncryptedAccessToken())
                        .append("nonce", credential.getNonce())
                        .append("key_version", credential.getKeyVersion())
                        .append("updated_at", credential.getUpdatedAt());

                Document setOnInsert = new Document("canonical_user_id", credential.getCanonicalUserId())
                        .append("created_at", credential.getCreatedAt());

                Document updateDoc = new Document("$set", setDoc).append("$setOnInsert", setOnInsert);

                mongoOperations.getCollection(COLLECTION_NAME).updateOne(
                        filter,
                        updateDoc,
                        new UpdateOptions().upsert(true)
                );
                return;
            } catch (Exception e) {
                logger.warn("MongoDB operation failed, falling back to in-memory store: {}", e.getMessage());
            }
        }

        inMemoryStore.put(credential.getCanonicalUserId(), doc);
    }

    /**
     * Retrieves an encrypted credential by canonical user ID.
     */
    public Optional<UserCredential> findByCanonicalUserId(String canonicalUserId) {
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            return Optional.empty();
        }

        if (mongoOperations != null) {
            try {
                Document doc = mongoOperations.getCollection(COLLECTION_NAME)
                        .find(new Document("canonical_user_id", canonicalUserId.trim()))
                        .first();
                if (doc != null) {
                    return Optional.of(fromDocument(doc));
                }
            } catch (Exception e) {
                logger.warn("MongoDB read failed, checking in-memory store: {}", e.getMessage());
            }
        }

        Document inMemDoc = inMemoryStore.get(canonicalUserId.trim());
        return Optional.ofNullable(fromDocument(inMemDoc));
    }

    /**
     * Deletes stored credential by canonical user ID (e.g. on user disconnect or account deletion).
     */
    public boolean deleteByCanonicalUserId(String canonicalUserId) {
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            return false;
        }

        boolean deleted = false;
        if (mongoOperations != null) {
            try {
                var res = mongoOperations.getCollection(COLLECTION_NAME)
                        .deleteOne(new Document("canonical_user_id", canonicalUserId.trim()));
                deleted = res.getDeletedCount() > 0;
            } catch (Exception e) {
                logger.warn("MongoDB delete failed: {}", e.getMessage());
            }
        }

        boolean memDeleted = inMemoryStore.remove(canonicalUserId.trim()) != null;
        return deleted || memDeleted;
    }

    /**
     * Clears in-memory test store.
     */
    public void clearInMemoryStore() {
        inMemoryStore.clear();
    }
}

