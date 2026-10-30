package com.issuepilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.model.UserIdentity;

import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnboardingStatusResponse {

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("onboarding_completed")
    private boolean onboardingCompleted;

    private UserIdentity identity;

    private Map<String, ContributorSkill> skills;

    @JsonProperty("inferred_skills")
    private List<InferredSkill> inferredSkills;

    @JsonProperty("dismissed_skills")
    private Set<String> dismissedSkills;

    private Map<String, Object> preferences;

    private double capability;

    @JsonProperty("updated_at")
    private String updatedAt;

    public OnboardingStatusResponse() {}

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public void setOnboardingCompleted(boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
    }

    public UserIdentity getIdentity() {
        return identity;
    }

    public void setIdentity(UserIdentity identity) {
        this.identity = identity;
    }

    public Map<String, ContributorSkill> getSkills() {
        return skills;
    }

    public void setSkills(Map<String, ContributorSkill> skills) {
        this.skills = skills;
    }

    public List<InferredSkill> getInferredSkills() {
        return inferredSkills;
    }

    public void setInferredSkills(List<InferredSkill> inferredSkills) {
        this.inferredSkills = inferredSkills;
    }

    public Set<String> getDismissedSkills() {
        return dismissedSkills;
    }

    public void setDismissedSkills(Set<String> dismissedSkills) {
        this.dismissedSkills = dismissedSkills;
    }

    public Map<String, Object> getPreferences() {
        return preferences;
    }

    public void setPreferences(Map<String, Object> preferences) {
        this.preferences = preferences;
    }

    public double getCapability() {
        return capability;
    }

    public void setCapability(double capability) {
        this.capability = capability;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}

