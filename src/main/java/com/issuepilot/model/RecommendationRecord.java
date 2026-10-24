package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecommendationRecord {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("issue_id")
    private String issueId;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("native_id")
    private String nativeId;

    @JsonProperty("repository_full_name")
    private String repositoryFullName;

    private Integer rank;

    @JsonProperty("original_rank")
    private Integer originalRank;

    @JsonProperty("original_score")
    private Double originalScore;

    @JsonProperty("delivered_rank")
    private Integer deliveredRank;

    @JsonProperty("final_score")
    private double finalScore;

    private Map<String, Object> scores;

    @JsonProperty("effective_weights")
    private Map<String, Double> effectiveWeights;

    @JsonProperty("weighted_contributions")
    private Map<String, Double> weightedContributions;

    @JsonProperty("missing_components")
    private List<String> missingComponents = new ArrayList<>();

    private String explanation;

    @JsonProperty("verification_status")
    private String verificationStatus = "unverified";

    @JsonProperty("verified_at")
    private String verifiedAt;

    @JsonProperty("verification_notes")
    private String verificationNotes;

    private String decision = "included"; // included, rejected, skipped, inconclusive

    @JsonProperty("exclusion_reason")
    private String exclusionReason;

    @JsonProperty("response_metadata")
    private Map<String, Object> responseMetadata;

    @JsonProperty("algorithm_version")
    private String algorithmVersion = "1.0.0-phase1";

    @JsonProperty("generated_at")
    private String generatedAt;

    public RecommendationRecord() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getIssueId() { return issueId; }
    public void setIssueId(String issueId) { this.issueId = issueId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getNativeId() { return nativeId; }
    public void setNativeId(String nativeId) { this.nativeId = nativeId; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public void setRepositoryFullName(String repositoryFullName) { this.repositoryFullName = repositoryFullName; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public Integer getOriginalRank() { return originalRank; }
    public void setOriginalRank(Integer originalRank) { this.originalRank = originalRank; }
    public Double getOriginalScore() { return originalScore; }
    public void setOriginalScore(Double originalScore) { this.originalScore = originalScore; }
    public Integer getDeliveredRank() { return deliveredRank; }
    public void setDeliveredRank(Integer deliveredRank) { this.deliveredRank = deliveredRank; }
    public double getFinalScore() { return finalScore; }
    public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
    public Map<String, Object> getScores() { return scores; }
    public void setScores(Map<String, Object> scores) { this.scores = scores; }
    public Map<String, Double> getEffectiveWeights() { return effectiveWeights; }
    public void setEffectiveWeights(Map<String, Double> effectiveWeights) { this.effectiveWeights = effectiveWeights; }
    public Map<String, Double> getWeightedContributions() { return weightedContributions; }
    public void setWeightedContributions(Map<String, Double> weightedContributions) { this.weightedContributions = weightedContributions; }
    public List<String> getMissingComponents() { return missingComponents; }
    public void setMissingComponents(List<String> missingComponents) { this.missingComponents = missingComponents != null ? missingComponents : new ArrayList<>(); }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
    public String getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(String verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getVerificationNotes() { return verificationNotes; }
    public void setVerificationNotes(String verificationNotes) { this.verificationNotes = verificationNotes; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getExclusionReason() { return exclusionReason; }
    public void setExclusionReason(String exclusionReason) { this.exclusionReason = exclusionReason; }
    public Map<String, Object> getResponseMetadata() { return responseMetadata; }
    public void setResponseMetadata(Map<String, Object> responseMetadata) { this.responseMetadata = responseMetadata; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String algorithmVersion) { this.algorithmVersion = algorithmVersion; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
}
