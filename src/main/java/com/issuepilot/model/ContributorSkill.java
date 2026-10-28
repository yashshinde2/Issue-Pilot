package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContributorSkill {

    private String technology;
    private double score = 50.0;
    private double confidence = 1.0;
    private String level; // e.g. "beginner", "intermediate", "advanced", "expert"
    private String source = "user_confirmed"; // "user_confirmed" or "inferred"

    public ContributorSkill() {
    }

    public ContributorSkill(String technology, double score, double confidence) {
        this.technology = technology;
        this.score = score;
        this.confidence = confidence;
    }

    public ContributorSkill(String technology, double score, double confidence, String level, String source) {
        this.technology = technology;
        this.score = score;
        this.confidence = confidence;
        this.level = level;
        this.source = source != null ? source : "user_confirmed";
    }

    public String getTechnology() {
        return technology;
    }

    public void setTechnology(String technology) {
        this.technology = technology;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
