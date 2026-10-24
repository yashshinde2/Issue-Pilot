package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RankingWeights {

    @JsonProperty("skill_weight")
    private double skillWeight = 0.35;

    @JsonProperty("difficulty_weight")
    private double difficultyWeight = 0.25;

    @JsonProperty("responsiveness_weight")
    private double responsivenessWeight = 0.15;

    @JsonProperty("health_weight")
    private double healthWeight = 0.10;

    @JsonProperty("feedback_weight")
    private double feedbackWeight = 0.10;

    @JsonProperty("freshness_weight")
    private double freshnessWeight = 0.05;

    public RankingWeights() {
    }

    public RankingWeights(double skillWeight, double difficultyWeight, double responsivenessWeight,
                          double healthWeight, double feedbackWeight, double freshnessWeight) {
        this.skillWeight = skillWeight;
        this.difficultyWeight = difficultyWeight;
        this.responsivenessWeight = responsivenessWeight;
        this.healthWeight = healthWeight;
        this.feedbackWeight = feedbackWeight;
        this.freshnessWeight = freshnessWeight;
    }

    public double getSkillWeight() {
        return skillWeight;
    }

    public void setSkillWeight(double skillWeight) {
        this.skillWeight = skillWeight;
    }

    public double getDifficultyWeight() {
        return difficultyWeight;
    }

    public void setDifficultyWeight(double difficultyWeight) {
        this.difficultyWeight = difficultyWeight;
    }

    public double getResponsivenessWeight() {
        return responsivenessWeight;
    }

    public void setResponsivenessWeight(double responsivenessWeight) {
        this.responsivenessWeight = responsivenessWeight;
    }

    public double getHealthWeight() {
        return healthWeight;
    }

    public void setHealthWeight(double healthWeight) {
        this.healthWeight = healthWeight;
    }

    public double getFeedbackWeight() {
        return feedbackWeight;
    }

    public void setFeedbackWeight(double feedbackWeight) {
        this.feedbackWeight = feedbackWeight;
    }

    public double getFreshnessWeight() {
        return freshnessWeight;
    }

    public void setFreshnessWeight(double freshnessWeight) {
        this.freshnessWeight = freshnessWeight;
    }
}
