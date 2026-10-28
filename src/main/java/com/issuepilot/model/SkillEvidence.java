package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Concrete supporting evidence for an inferred technology or skill.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillEvidence {

    private String type; // e.g. "dependency_manifest", "language_statistics", "commit_activity", "source_file"
    private String repository; // e.g. "octocat/hello-world"

    @JsonProperty("file_path")
    private String filePath; // e.g. "requirements.txt", "package.json", "pom.xml"

    private String detail; // e.g. "fastapi>=0.100.0 found in requirements.txt"
    private double weight = 1.0;

    public SkillEvidence() {
    }

    public SkillEvidence(String type, String repository, String filePath, String detail) {
        this.type = type;
        this.repository = repository;
        this.filePath = filePath;
        this.detail = detail;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRepository() {
        return repository;
    }

    public void setRepository(String repository) {
        this.repository = repository;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SkillEvidence that = (SkillEvidence) o;
        return Objects.equals(type, that.type) &&
               Objects.equals(repository, that.repository) &&
               Objects.equals(filePath, that.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, repository, filePath);
    }
}

