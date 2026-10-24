package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TechnologyRelevance {

    private String technology;

    @JsonProperty("raw_relevance")
    private double rawRelevance = 1.0;

    @JsonProperty("normalized_relevance")
    private Double normalizedRelevance;

    public TechnologyRelevance() {
    }

    public TechnologyRelevance(String technology, double rawRelevance) {
        this.technology = technology;
        this.rawRelevance = rawRelevance;
    }

    public String getTechnology() {
        return technology;
    }

    public void setTechnology(String technology) {
        this.technology = technology;
    }

    public double getRawRelevance() {
        return rawRelevance;
    }

    public void setRawRelevance(double rawRelevance) {
        this.rawRelevance = rawRelevance;
    }

    public Double getNormalizedRelevance() {
        return normalizedRelevance;
    }

    public void setNormalizedRelevance(Double normalizedRelevance) {
        this.normalizedRelevance = normalizedRelevance;
    }
}
