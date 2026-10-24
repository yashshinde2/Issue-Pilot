package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackEvent {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("issue_id")
    private String issueId;

    @JsonProperty("event_type")
    private String eventType; // accepted, rejected, completed

    @JsonProperty("event_timestamp")
    private String eventTimestamp;

    @JsonProperty("recommendation_snapshot")
    private Map<String, Object> recommendationSnapshot;

    public FeedbackEvent() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getIssueId() { return issueId; }
    public void setIssueId(String issueId) { this.issueId = issueId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(String eventTimestamp) { this.eventTimestamp = eventTimestamp; }
    public Map<String, Object> getRecommendationSnapshot() { return recommendationSnapshot; }
    public void setRecommendationSnapshot(Map<String, Object> recommendationSnapshot) { this.recommendationSnapshot = recommendationSnapshot; }
}
