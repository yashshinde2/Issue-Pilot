package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class EvidenceModels {

    private EvidenceModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DifficultyEvidence {
        @JsonProperty("scope_change_size")
        private double scopeChangeSize = 50.0;
        @JsonProperty("technical_complexity")
        private double technicalComplexity = 50.0;
        @JsonProperty("historical_effort")
        private double historicalEffort = 50.0;
        @JsonProperty("dependency_arch_impact")
        private double dependencyArchImpact = 50.0;
        @JsonProperty("coordination_complexity")
        private double coordinationComplexity = 50.0;

        public DifficultyEvidence() {}

        public double getScopeChangeSize() { return scopeChangeSize; }
        public void setScopeChangeSize(double scopeChangeSize) { this.scopeChangeSize = scopeChangeSize; }
        public double getTechnicalComplexity() { return technicalComplexity; }
        public void setTechnicalComplexity(double technicalComplexity) { this.technicalComplexity = technicalComplexity; }
        public double getHistoricalEffort() { return historicalEffort; }
        public void setHistoricalEffort(double historicalEffort) { this.historicalEffort = historicalEffort; }
        public double getDependencyArchImpact() { return dependencyArchImpact; }
        public void setDependencyArchImpact(double dependencyArchImpact) { this.dependencyArchImpact = dependencyArchImpact; }
        public double getCoordinationComplexity() { return coordinationComplexity; }
        public void setCoordinationComplexity(double coordinationComplexity) { this.coordinationComplexity = coordinationComplexity; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponsivenessEvidence {
        private boolean available = true;
        @JsonProperty("response_time_score")
        private double responseTimeScore = 80.0;
        @JsonProperty("response_rate_score")
        private double responseRateScore = 80.0;
        @JsonProperty("pr_review_score")
        private double prReviewScore = 80.0;
        @JsonProperty("recent_maintainer_act")
        private double recentMaintainerAct = 80.0;

        public ResponsivenessEvidence() {}

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public double getResponseTimeScore() { return responseTimeScore; }
        public void setResponseTimeScore(double responseTimeScore) { this.responseTimeScore = responseTimeScore; }
        public double getResponseRateScore() { return responseRateScore; }
        public void setResponseRateScore(double responseRateScore) { this.responseRateScore = responseRateScore; }
        public double getPrReviewScore() { return prReviewScore; }
        public void setPrReviewScore(double prReviewScore) { this.prReviewScore = prReviewScore; }
        public double getRecentMaintainerAct() { return recentMaintainerAct; }
        public void setRecentMaintainerAct(double recentMaintainerAct) { this.recentMaintainerAct = recentMaintainerAct; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RepositoryHealthEvidence {
        private boolean available = true;
        @JsonProperty("recent_commits")
        private double recentCommits = 80.0;
        @JsonProperty("pr_activity")
        private double prActivity = 80.0;
        @JsonProperty("issue_activity")
        private double issueActivity = 80.0;
        @JsonProperty("active_contributors")
        private double activeContributors = 80.0;
        @JsonProperty("recent_releases")
        private double recentReleases = 80.0;

        public RepositoryHealthEvidence() {}

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public double getRecentCommits() { return recentCommits; }
        public void setRecentCommits(double recentCommits) { this.recentCommits = recentCommits; }
        public double getPrActivity() { return prActivity; }
        public void setPrActivity(double prActivity) { this.prActivity = prActivity; }
        public double getIssueActivity() { return issueActivity; }
        public void setIssueActivity(double issueActivity) { this.issueActivity = issueActivity; }
        public double getActiveContributors() { return activeContributors; }
        public void setActiveContributors(double activeContributors) { this.activeContributors = activeContributors; }
        public double getRecentReleases() { return recentReleases; }
        public void setRecentReleases(double recentReleases) { this.recentReleases = recentReleases; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FreshnessEvidence {
        private boolean available = true;
        @JsonProperty("age_score")
        private double ageScore = 80.0;
        @JsonProperty("activity_score")
        private double activityScore = 80.0;

        public FreshnessEvidence() {}

        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        public double getAgeScore() { return ageScore; }
        public void setAgeScore(double ageScore) { this.ageScore = ageScore; }
        public double getActivityScore() { return activityScore; }
        public void setActivityScore(double activityScore) { this.activityScore = activityScore; }
    }
}
