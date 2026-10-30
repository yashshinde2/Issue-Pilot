package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.text.Normalizer;
import java.util.Objects;

/**
 * Authoritative Canonical Four-Part Issue Key.
 * Format: source|source_instance|canonical_repository_key|native_issue_id
 * 
 * Every segment is strictly mandatory.
 * Preserves distinct instances for self-hosted GitLab, Forgejo, and Bugzilla.
 */
public final class CanonicalIssueKey implements Comparable<CanonicalIssueKey> {

    public static final String SEPARATOR = "|";
    public static final String SEPARATOR_REGEX = "\\|";

    private final String source;
    private final String sourceInstance;
    private final String canonicalRepositoryKey;
    private final String nativeIssueId;

    public CanonicalIssueKey(String source, String sourceInstance, String canonicalRepositoryKey, String nativeIssueId) {
        this.source = validateSegment("source", source).toLowerCase(java.util.Locale.ROOT);
        this.sourceInstance = validateSegment("source_instance", sourceInstance);
        this.canonicalRepositoryKey = validateSegment("canonical_repository_key", canonicalRepositoryKey);
        this.nativeIssueId = validateSegment("native_issue_id", nativeIssueId);
    }

    private static String validateSegment(String fieldName, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Canonical issue key segment '" + fieldName + "' must not be null or blank");
        }
        String trimmed = value.trim();
        if (trimmed.contains(SEPARATOR)) {
            throw new IllegalArgumentException("Canonical issue key segment '" + fieldName + "' must not contain delimiter '" + SEPARATOR + "'");
        }
        // Normalize to Unicode NFC
        return Normalizer.normalize(trimmed, Normalizer.Form.NFC);
    }

    @JsonCreator
    public static CanonicalIssueKey parse(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Canonical issue key must not be null or blank");
        }
        String[] parts = key.split(SEPARATOR_REGEX, -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Canonical issue key must have exactly 4 segments separated by '|', got " + parts.length + " segments in: '" + key + "'");
        }
        return new CanonicalIssueKey(parts[0], parts[1], parts[2], parts[3]);
    }

    public static String build(String source, String sourceInstance, String canonicalRepositoryKey, String nativeIssueId) {
        return new CanonicalIssueKey(source, sourceInstance, canonicalRepositoryKey, nativeIssueId).toString();
    }

    public String getSource() {
        return source;
    }

    public String getSourceInstance() {
        return sourceInstance;
    }

    public String getCanonicalRepositoryKey() {
        return canonicalRepositoryKey;
    }

    public String getNativeIssueId() {
        return nativeIssueId;
    }

    @JsonValue
    @Override
    public String toString() {
        return source + SEPARATOR + sourceInstance + SEPARATOR + canonicalRepositoryKey + SEPARATOR + nativeIssueId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CanonicalIssueKey)) return false;
        CanonicalIssueKey that = (CanonicalIssueKey) o;
        return Objects.equals(source, that.source) &&
               Objects.equals(sourceInstance, that.sourceInstance) &&
               Objects.equals(canonicalRepositoryKey, that.canonicalRepositoryKey) &&
               Objects.equals(nativeIssueId, that.nativeIssueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, sourceInstance, canonicalRepositoryKey, nativeIssueId);
    }

    @Override
    public int compareTo(CanonicalIssueKey o) {
        if (o == null) return 1;
        return this.toString().compareTo(o.toString());
    }
}

