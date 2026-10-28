package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Encrypted OAuth credential stored in the dedicated 'user_tokens' MongoDB collection.
 *
 * Security Invariants:
 * - Never stored in the user profile document or returned via public APIs.
 * - Bound cryptographically to the canonical user ID (e.g., "github:12345678").
 * - Encrypted with AES-256-GCM using authenticated additional data (AAD).
 * - Sensitive fields are explicitly annotated with @JsonIgnore.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserCredential {

    @JsonProperty("canonical_user_id")
    private String canonicalUserId;

    @JsonProperty("provider")
    private String provider = "github";

    @JsonProperty("provider_user_id")
    private String providerUserId;

    @JsonProperty("token_type")
    private String tokenType = "bearer";

    @JsonProperty("scope")
    private String scope = "read:user";

    @JsonIgnore
    @JsonProperty("encrypted_access_token")
    private String encryptedAccessToken;

    @JsonIgnore
    @JsonProperty("nonce")
    private String nonce;

    @JsonProperty("key_version")
    private String keyVersion = "v1";

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    public UserCredential() {
    }

    public UserCredential(String canonicalUserId, String provider, String providerUserId) {
        this.canonicalUserId = canonicalUserId;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }

    public String getCanonicalUserId() {
        return canonicalUserId;
    }

    public void setCanonicalUserId(String canonicalUserId) {
        this.canonicalUserId = canonicalUserId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public void setProviderUserId(String providerUserId) {
        this.providerUserId = providerUserId;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    @JsonIgnore
    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    @JsonProperty("encrypted_access_token")
    public void setEncryptedAccessToken(String encryptedAccessToken) {
        this.encryptedAccessToken = encryptedAccessToken;
    }

    @JsonIgnore
    public String getNonce() {
        return nonce;
    }

    @JsonProperty("nonce")
    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(String keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserCredential that = (UserCredential) o;
        return Objects.equals(canonicalUserId, that.canonicalUserId) &&
                Objects.equals(provider, that.provider);
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonicalUserId, provider);
    }

    @Override
    public String toString() {
        return "UserCredential{" +
                "canonicalUserId='" + canonicalUserId + '\'' +
                ", provider='" + provider + '\'' +
                ", providerUserId='" + providerUserId + '\'' +
                ", scope='" + scope + '\'' +
                ", keyVersion='" + keyVersion + '\'' +
                ", updatedAt='" + updatedAt + '\'' +
                '}';
    }
}

