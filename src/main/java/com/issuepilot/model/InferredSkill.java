package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Candidate skill or technology automatically inferred from external provider activity
 * (e.g. GitHub repos, manifests, language statistics).
 *
 * Inferred skills are recommendations presented to the user during onboarding
 * and do NOT overwrite user-confirmed skills.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InferredSkill {

    private String technology;
    private String category; // e.g. "language", "framework", "library", "tool"
    private double confidence = 0.5; // 0.0 to 1.0
    private List<SkillEvidence> evidence = new ArrayList<>();
    private String status = "pending_confirmation"; // "pending_confirmation", "confirmed", "dismissed"

    public InferredSkill() {
    }

    public InferredSkill(String technology, String category, double confidence) {
        this.technology = technology;
        this.category = category;
        this.confidence = confidence;
    }

    public String getTechnology() {
        return technology;
    }

    public void setTechnology(String technology) {
        this.technology = technology;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public List<SkillEvidence> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<SkillEvidence> evidence) {
        this.evidence = evidence != null ? evidence : new ArrayList<>();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void addEvidence(SkillEvidence item) {
        if (item != null) {
            this.evidence.add(item);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InferredSkill that = (InferredSkill) o;
        return Objects.equals(technology, that.technology);
    }

    @Override
    public int hashCode() {
        return Objects.hash(technology);
    }
}

