package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.issuepilot.config.AlgorithmConstants;

import java.time.Instant;

/**
 * Standard metadata tagging every feature, score, and recommendation request.
 * Conforms to Section 2: algorithm_version, feature_schema_version,
 * technology_registry_version, label_taxonomy_version, request_snapshot_time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AlgorithmMetadata {

    @JsonProperty("algorithm_version")
    private String algorithmVersion = AlgorithmConstants.ALGORITHM_VERSION;

    @JsonProperty("feature_schema_version")
    private String featureSchemaVersion = AlgorithmConstants.FEATURE_SCHEMA_VERSION;

    @JsonProperty("technology_registry_version")
    private String technologyRegistryVersion = AlgorithmConstants.TECHNOLOGY_REGISTRY_VERSION;

    @JsonProperty("label_taxonomy_version")
    private String labelTaxonomyVersion = AlgorithmConstants.LABEL_TAXONOMY_VERSION;

    @JsonProperty("request_snapshot_time")
    private String requestSnapshotTime;

    public AlgorithmMetadata() {
        this.requestSnapshotTime = Instant.now().toString();
    }

    public AlgorithmMetadata(String requestSnapshotTime) {
        this.requestSnapshotTime = (requestSnapshotTime != null) ? requestSnapshotTime : Instant.now().toString();
    }

    public static AlgorithmMetadata current() {
        return new AlgorithmMetadata();
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public void setAlgorithmVersion(String algorithmVersion) {
        this.algorithmVersion = algorithmVersion;
    }

    public String getFeatureSchemaVersion() {
        return featureSchemaVersion;
    }

    public void setFeatureSchemaVersion(String featureSchemaVersion) {
        this.featureSchemaVersion = featureSchemaVersion;
    }

    public String getTechnologyRegistryVersion() {
        return technologyRegistryVersion;
    }

    public void setTechnologyRegistryVersion(String technologyRegistryVersion) {
        this.technologyRegistryVersion = technologyRegistryVersion;
    }

    public String getLabelTaxonomyVersion() {
        return labelTaxonomyVersion;
    }

    public void setLabelTaxonomyVersion(String labelTaxonomyVersion) {
        this.labelTaxonomyVersion = labelTaxonomyVersion;
    }

    public String getRequestSnapshotTime() {
        return requestSnapshotTime;
    }

    public void setRequestSnapshotTime(String requestSnapshotTime) {
        this.requestSnapshotTime = requestSnapshotTime;
    }
}

