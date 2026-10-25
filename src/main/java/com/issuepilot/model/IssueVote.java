package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IssueVote {

    private String id;

    @JsonProperty("user_id")
    private String userId;

    private String source; // e.g. "github", "gitlab", "bugzilla", "codeberg"

    @JsonProperty("platform_issue_id")
    private String platformIssueId;

    private int vote; // +1 for like, -1 for dislike

    @JsonProperty("recommendation_run_id")
    private String recommendationRunId;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("feature_snapshot")
    private Map<String, Object> featureSnapshot = new LinkedHashMap<>();

    public IssueVote() {}

    public IssueVote(String userId, String source, String platformIssueId, int vote) {
        this.userId = userId;
        this.source = source;
        this.platformIssueId = platformIssueId;
        this.vote = vote;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getPlatformIssueId() { return platformIssueId; }
    public void setPlatformIssueId(String platformIssueId) { this.platformIssueId = platformIssueId; }

    public int getVote() { return vote; }
    public void setVote(int vote) { this.vote = vote; }

    public String getRecommendationRunId() { return recommendationRunId; }
    public void setRecommendationRunId(String recommendationRunId) { this.recommendationRunId = recommendationRunId; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, Object> getFeatureSnapshot() { return featureSnapshot; }
    public void setFeatureSnapshot(Map<String, Object> featureSnapshot) {
        this.featureSnapshot = featureSnapshot != null ? featureSnapshot : new LinkedHashMap<>();
    }
}
