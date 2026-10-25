package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoteAffinityResult {

    private String id;
    private String source;

    @JsonProperty("platform_issue_id")
    private String platformIssueId;

    @JsonProperty("affinity_score")
    private double affinityScore;

    private double confidence;

    @JsonProperty("positive_evidence")
    private double positiveEvidence;

    @JsonProperty("negative_evidence")
    private double negativeEvidence;

    @JsonProperty("conflict_indicator")
    private boolean conflictIndicator;

    @JsonProperty("conflict_ratio")
    private double conflictRatio;

    @JsonProperty("matched_feature_groups")
    private List<String> matchedFeatureGroups = new ArrayList<>();

    private List<String> explanations = new ArrayList<>();

    public VoteAffinityResult() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getPlatformIssueId() { return platformIssueId; }
    public void setPlatformIssueId(String platformIssueId) { this.platformIssueId = platformIssueId; }

    public double getAffinityScore() { return affinityScore; }
    public void setAffinityScore(double affinityScore) { this.affinityScore = affinityScore; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public double getPositiveEvidence() { return positiveEvidence; }
    public void setPositiveEvidence(double positiveEvidence) { this.positiveEvidence = positiveEvidence; }

    public double getNegativeEvidence() { return negativeEvidence; }
    public void setNegativeEvidence(double negativeEvidence) { this.negativeEvidence = negativeEvidence; }

    public boolean isConflictIndicator() { return conflictIndicator; }
    public void setConflictIndicator(boolean conflictIndicator) { this.conflictIndicator = conflictIndicator; }

    public double getConflictRatio() { return conflictRatio; }
    public void setConflictRatio(double conflictRatio) { this.conflictRatio = conflictRatio; }

    public List<String> getMatchedFeatureGroups() { return matchedFeatureGroups; }
    public void setMatchedFeatureGroups(List<String> matchedFeatureGroups) {
        this.matchedFeatureGroups = matchedFeatureGroups != null ? matchedFeatureGroups : new ArrayList<>();
    }

    public List<String> getExplanations() { return explanations; }
    public void setExplanations(List<String> explanations) {
        this.explanations = explanations != null ? explanations : new ArrayList<>();
    }
}
