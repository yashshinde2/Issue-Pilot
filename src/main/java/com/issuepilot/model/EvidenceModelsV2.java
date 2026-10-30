package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Version 2.3 evidence payload models conforming to Issue Pilot V2.3 specification.
 * Eliminates legacy V1 proxies (e.g. historical effort HE and comment count proxies)
 * and uses structured, traceable anchors.
 */
public final class EvidenceModelsV2 {

    private EvidenceModelsV2() {}

    /**
     * V2.3 Difficulty Evidence: 4 signals with weights SC (30%), TC (30%), DI (20%), CC (20%).
     * Anchors strictly on {0, 25, 50, 75, 100}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DifficultyEvidenceV2 {
        @JsonProperty("scope_change_size")
        private Double scopeChangeSize; // SC (30%)

        @JsonProperty("technical_complexity")
        private Double technicalComplexity; // TC (30%)

        @JsonProperty("dependency_arch_impact")
        private Double dependencyArchImpact; // DI (20%)

        @JsonProperty("coordination_complexity")
        private Double coordinationComplexity; // CC (20%)

        public DifficultyEvidenceV2() {}

        public DifficultyEvidenceV2(Double scopeChangeSize, Double technicalComplexity,
                                    Double dependencyArchImpact, Double coordinationComplexity) {
            this.scopeChangeSize = scopeChangeSize;
            this.technicalComplexity = technicalComplexity;
            this.dependencyArchImpact = dependencyArchImpact;
            this.coordinationComplexity = coordinationComplexity;
        }

        public Double getScopeChangeSize() { return scopeChangeSize; }
        public void setScopeChangeSize(Double scopeChangeSize) { this.scopeChangeSize = scopeChangeSize; }

        public Double getTechnicalComplexity() { return technicalComplexity; }
        public void setTechnicalComplexity(Double technicalComplexity) { this.technicalComplexity = technicalComplexity; }

        public Double getDependencyArchImpact() { return dependencyArchImpact; }
        public void setDependencyArchImpact(Double dependencyArchImpact) { this.dependencyArchImpact = dependencyArchImpact; }

        public Double getCoordinationComplexity() { return coordinationComplexity; }
        public void setCoordinationComplexity(Double coordinationComplexity) { this.coordinationComplexity = coordinationComplexity; }
    }

    /**
     * V2.3 Maintainer Responsiveness Evidence: RT (30%), RR (30%), PR (25%), MA (15%).
     * Measured over mature cohorts (168h complete observation) and verified human maintainer roles.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponsivenessEvidenceV2 {
        @JsonProperty("response_time_score")
        private Double responseTimeScore; // RT: 100 / (1 + median_hours/48)

        @JsonProperty("response_rate_score")
        private Double responseRateScore; // RR: 100 * mature_replied_7d / mature_observed_7d

        @JsonProperty("pr_review_score")
        private Double prReviewScore; // PR: 100 / (1 + median_review_hours/72)

        @JsonProperty("recent_maintainer_act")
        private Double recentMaintainerAct; // MA: 100 * 0.5^(days_since_act/14)

        @JsonProperty("median_first_reply_hours")
        private Double medianFirstReplyHours;

        @JsonProperty("mature_issues_replied_7d")
        private Integer matureIssuesReplied7d;

        @JsonProperty("mature_issues_total_7d")
        private Integer matureIssuesTotal7d;

        public ResponsivenessEvidenceV2() {}

        public Double getResponseTimeScore() { return responseTimeScore; }
        public void setResponseTimeScore(Double responseTimeScore) { this.responseTimeScore = responseTimeScore; }

        public Double getResponseRateScore() { return responseRateScore; }
        public void setResponseRateScore(Double responseRateScore) { this.responseRateScore = responseRateScore; }

        public Double getPrReviewScore() { return prReviewScore; }
        public void setPrReviewScore(Double prReviewScore) { this.prReviewScore = prReviewScore; }

        public Double getRecentMaintainerAct() { return recentMaintainerAct; }
        public void setRecentMaintainerAct(Double recentMaintainerAct) { this.recentMaintainerAct = recentMaintainerAct; }

        public Double getMedianFirstReplyHours() { return medianFirstReplyHours; }
        public void setMedianFirstReplyHours(Double medianFirstReplyHours) { this.medianFirstReplyHours = medianFirstReplyHours; }

        public Integer getMatureIssuesReplied7d() { return matureIssuesReplied7d; }
        public void setMatureIssuesReplied7d(Integer matureIssuesReplied7d) { this.matureIssuesReplied7d = matureIssuesReplied7d; }

        public Integer getMatureIssuesTotal7d() { return matureIssuesTotal7d; }
        public void setMatureIssuesTotal7d(Integer matureIssuesTotal7d) { this.matureIssuesTotal7d = matureIssuesTotal7d; }
    }

    /**
     * V2.3 Repository Health Evidence: direct code-host activity only.
     * C90 (30%), PR30 (25%), AU90 (25%), MA90 (20%).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RepositoryHealthEvidenceV2 {
        @JsonProperty("c90_score")
        private Double c90Score; // 100 * min(1, ln(1 + commits_90d) / ln(61))

        @JsonProperty("pr30_score")
        private Double pr30Score; // 100 * min(1, merged_prs_30d / 8)

        @JsonProperty("au90_score")
        private Double au90Score; // 100 * min(1, distinct_authors_90d / 6)

        @JsonProperty("ma90_score")
        private Double ma90Score; // 100 * 0.5^(days_since_code_activity / 90)

        @JsonProperty("human_commits_90d")
        private Integer humanCommits90d;

        @JsonProperty("merged_prs_30d")
        private Integer mergedPrs30d;

        @JsonProperty("distinct_authors_90d")
        private Integer distinctAuthors90d;

        public RepositoryHealthEvidenceV2() {}

        public Double getC90Score() { return c90Score; }
        public void setC90Score(Double c90Score) { this.c90Score = c90Score; }

        public Double getPr30Score() { return pr30Score; }
        public void setPr30Score(Double pr30Score) { this.pr30Score = pr30Score; }

        public Double getAu90Score() { return au90Score; }
        public void setAu90Score(Double au90Score) { this.au90Score = au90Score; }

        public Double getMa90Score() { return ma90Score; }
        public void setMa90Score(Double ma90Score) { this.ma90Score = ma90Score; }

        public Integer getHumanCommits90d() { return humanCommits90d; }
        public void setHumanCommits90d(Integer humanCommits90d) { this.humanCommits90d = humanCommits90d; }

        public Integer getMergedPrs30d() { return mergedPrs30d; }
        public void setMergedPrs30d(Integer mergedPrs30d) { this.mergedPrs30d = mergedPrs30d; }

        public Integer getDistinctAuthors90d() { return distinctAuthors90d; }
        public void setDistinctAuthors90d(Integer distinctAuthors90d) { this.distinctAuthors90d = distinctAuthors90d; }
    }

    /**
     * V2.3 Freshness Evidence: AgeScore (40%), ActivityScore (60%) with exponential decay.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FreshnessEvidenceV2 {
        @JsonProperty("age_score")
        private Double ageScore; // 100 * 0.5^(age_days / 14)

        @JsonProperty("activity_score")
        private Double activityScore; // 100 * 0.5^(idle_days / 7)

        @JsonProperty("age_days")
        private Double ageDays;

        @JsonProperty("idle_days")
        private Double idleDays;

        public FreshnessEvidenceV2() {}

        public FreshnessEvidenceV2(Double ageDays, Double idleDays) {
            this.ageDays = ageDays;
            this.idleDays = idleDays;
            if (ageDays != null && ageDays >= 0.0) {
                this.ageScore = 100.0 * Math.pow(0.5, ageDays / 14.0);
            }
            if (idleDays != null && idleDays >= 0.0) {
                this.activityScore = 100.0 * Math.pow(0.5, idleDays / 7.0);
            }
        }

        public Double getAgeScore() { return ageScore; }
        public void setAgeScore(Double ageScore) { this.ageScore = ageScore; }

        public Double getActivityScore() { return activityScore; }
        public void setActivityScore(Double activityScore) { this.activityScore = activityScore; }

        public Double getAgeDays() { return ageDays; }
        public void setAgeDays(Double ageDays) { this.ageDays = ageDays; }

        public Double getIdleDays() { return idleDays; }
        public void setIdleDays(Double idleDays) { this.idleDays = idleDays; }
    }

    /**
     * V2.3 Outcome Feedback Evidence: hierarchical Beta(2,2) backoff.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OutcomeFeedbackEvidenceV2 {
        @JsonProperty("p_accept")
        private Double pAccept;

        @JsonProperty("p_complete_given_accepted")
        private Double pCompleteGivenAccepted;

        @JsonProperty("hierarchy_level")
        private String hierarchyLevel; // e.g., "user_tech_diff", "user_tech", "user_global", "population"

        @JsonProperty("cohort_key")
        private String cohortKey;

        @JsonProperty("trials_accept")
        private Integer trialsAccept;

        @JsonProperty("trials_complete")
        private Integer trialsComplete;

        public OutcomeFeedbackEvidenceV2() {}

        public Double getPAccept() { return pAccept; }
        public void setPAccept(Double pAccept) { this.pAccept = pAccept; }

        public Double getPCompleteGivenAccepted() { return pCompleteGivenAccepted; }
        public void setPCompleteGivenAccepted(Double pCompleteGivenAccepted) { this.pCompleteGivenAccepted = pCompleteGivenAccepted; }

        public String getHierarchyLevel() { return hierarchyLevel; }
        public void setHierarchyLevel(String hierarchyLevel) { this.hierarchyLevel = hierarchyLevel; }

        public String getCohortKey() { return cohortKey; }
        public void setCohortKey(String cohortKey) { this.cohortKey = cohortKey; }

        public Integer getTrialsAccept() { return trialsAccept; }
        public void setTrialsAccept(Integer trialsAccept) { this.trialsAccept = trialsAccept; }

        public Integer getTrialsComplete() { return trialsComplete; }
        public void setTrialsComplete(Integer trialsComplete) { this.trialsComplete = trialsComplete; }
    }
}

