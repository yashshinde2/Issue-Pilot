package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.issuepilot.adapter.TechnologyDetectionHelper;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContributorProfile {

    @JsonProperty("user_id")
    private String userId = "demo-contributor";

    private Map<String, ContributorSkill> skills = new HashMap<>();

    private double capability = 70.0;

    @JsonProperty("accepted_count")
    private int acceptedCount = 0;

    @JsonProperty("rejected_count")
    private int rejectedCount = 0;

    @JsonProperty("completed_count")
    private int completedCount = 0;

    @JsonProperty("similarity_to_history")
    private double similarityToHistory = 75.0;

    @JsonProperty("has_feedback_history")
    private boolean hasFeedbackHistory = false;

    private Map<String, Object> preferences = new HashMap<>();

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("last_active_at")
    private String lastActiveAt;

    public ContributorProfile() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Map<String, ContributorSkill> getSkills() {
        return skills;
    }

    public void setSkills(Map<String, ContributorSkill> skills) {
        this.skills = skills != null ? skills : new HashMap<>();
    }

    public double getCapability() {
        return capability;
    }

    public void setCapability(double capability) {
        this.capability = capability;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public void setAcceptedCount(int acceptedCount) {
        this.acceptedCount = acceptedCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(int rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public int getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(int completedCount) {
        this.completedCount = completedCount;
    }

    public double getSimilarityToHistory() {
        return similarityToHistory;
    }

    public void setSimilarityToHistory(double similarityToHistory) {
        this.similarityToHistory = similarityToHistory;
    }

    public boolean isHasFeedbackHistory() {
        return hasFeedbackHistory;
    }

    public void setHasFeedbackHistory(boolean hasFeedbackHistory) {
        this.hasFeedbackHistory = hasFeedbackHistory;
    }

    public Map<String, Object> getPreferences() {
        return preferences;
    }

    public void setPreferences(Map<String, Object> preferences) {
        this.preferences = preferences != null ? preferences : new HashMap<>();
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

    public String getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(String lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Set<String> getNormalizedTechnologyPreferences() {
        Set<String> result = new LinkedHashSet<>();
        if (skills != null) {
            for (String s : skills.keySet()) {
                String norm = TechnologyDetectionHelper.normalizeTechnology(s);
                if (norm != null) result.add(norm);
            }
            for (ContributorSkill skill : skills.values()) {
                if (skill != null && skill.getTechnology() != null) {
                    String norm = TechnologyDetectionHelper.normalizeTechnology(skill.getTechnology());
                    if (norm != null) result.add(norm);
                }
            }
        }
        if (preferences != null) {
            List<String> prefKeys = List.of("technologies", "tech_stack", "preferred_technologies", "languages", "preferred_languages");
            for (String key : prefKeys) {
                Object val = preferences.get(key);
                if (val instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null) {
                            String norm = TechnologyDetectionHelper.normalizeTechnology(item.toString());
                            if (norm != null) result.add(norm);
                        }
                    }
                } else if (val instanceof String str) {
                    for (String token : str.split("[,; ]+")) {
                        String norm = TechnologyDetectionHelper.normalizeTechnology(token);
                        if (norm != null) result.add(norm);
                    }
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
