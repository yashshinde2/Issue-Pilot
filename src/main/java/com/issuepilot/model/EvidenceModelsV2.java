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

        /**
         * Computes the V2.3 aggregated repository health score H conforming to Section 8:
         * H = 0.30 * C90 + 0.25 * PR30 + 0.25 * AU90 + 0.20 * MA90.
         * Renormalizes across available sub-signals if any are missing.
         */
        public Double computeHealthScore() {
            double sumWeights = 0.0;
            double weightedSum = 0.0;
            if (c90Score != null && !Double.isNaN(c90Score)) {
                weightedSum += 0.30 * Math.max(0.0, Math.min(100.0, c90Score));
                sumWeights += 0.30;
            }
            if (pr30Score != null && !Double.isNaN(pr30Score)) {
                weightedSum += 0.25 * Math.max(0.0, Math.min(100.0, pr30Score));
                sumWeights += 0.25;
            }
            if (au90Score != null && !Double.isNaN(au90Score)) {
                weightedSum += 0.25 * Math.max(0.0, Math.min(100.0, au90Score));
                sumWeights += 0.25;
            }
            if (ma90Score != null && !Double.isNaN(ma90Score)) {
                weightedSum += 0.20 * Math.max(0.0, Math.min(100.0, ma90Score));
                sumWeights += 0.20;
            }
            if (sumWeights <= 0.0) return null;
            return weightedSum / sumWeights;
        }

        public static RepositoryHealthEvidenceV2 fromMeasurements(
                Integer humanCommits90d,
                Integer mergedPrs30d,
                Integer distinctAuthors90d,
                Double daysSinceCodeActivity
        ) {
            RepositoryHealthEvidenceV2 h = new RepositoryHealthEvidenceV2();
            if (humanCommits90d != null) {
                h.setHumanCommits90d(humanCommits90d);
                h.setC90Score(100.0 * Math.min(1.0, Math.log(1.0 + Math.max(0, humanCommits90d)) / Math.log(61.0)));
            }
            if (mergedPrs30d != null) {
                h.setMergedPrs30d(mergedPrs30d);
                h.setPr30Score(100.0 * Math.min(1.0, Math.max(0, mergedPrs30d) / 8.0));
            }
            if (distinctAuthors90d != null) {
                h.setDistinctAuthors90d(distinctAuthors90d);
                h.setAu90Score(100.0 * Math.min(1.0, Math.max(0, distinctAuthors90d) / 6.0));
            }
            if (daysSinceCodeActivity != null) {
                h.setMa90Score(100.0 * Math.pow(0.5, Math.max(0.0, daysSinceCodeActivity) / 90.0));
            }
            return h;
        }
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

    /**
     * V2.3 Contributor Skill Evidence per Technology conforming strictly to Sections 5.2 and 5.3.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContributorSkillEvidenceV2 {
        @JsonProperty("technology")
        private String technology;

        @JsonProperty("unique_contributed_repositories")
        private Integer uniqueContributedRepositories;

        @JsonProperty("repo_available")
        private Boolean repoAvailable = false;

        @JsonProperty("standalone_commits")
        private Integer standaloneCommits;

        @JsonProperty("standalone_commits_available")
        private Boolean standaloneCommitsAvailable = false;

        @JsonProperty("unique_authored_prs")
        private Integer uniqueAuthoredPrs;

        @JsonProperty("pr_volume_available")
        private Boolean prVolumeAvailable = false;

        @JsonProperty("merged_decided_prs")
        private Integer mergedDecidedPrs;

        @JsonProperty("decided_prs")
        private Integer decidedPrs;

        @JsonProperty("merge_rate_available")
        private Boolean mergeRateAvailable = false;

        @JsonProperty("months_since_last_activity")
        private Double monthsSinceLastActivity;

        @JsonProperty("recency_available")
        private Boolean recencyAvailable = false;

        @JsonProperty("profile_coverage")
        private Double profileCoverage = 1.0;

        @JsonProperty("source_quality")
        private Double sourceQuality = 1.0;

        @JsonProperty("profile_coverage_known")
        private Boolean profileCoverageKnown = true;

        @JsonProperty("source_quality_known")
        private Boolean sourceQualityKnown = true;

        // Questionnaire self-report
        @JsonProperty("has_questionnaire")
        private Boolean hasQuestionnaire = false;

        @JsonProperty("questionnaire_level")
        private String questionnaireLevel;

        @JsonProperty("questionnaire_score")
        private Double questionnaireScore;

        public ContributorSkillEvidenceV2() {}

        public static double sat(double x, double k) {
            if (x <= 0.0) return 0.0;
            return 100.0 * (1.0 - Math.exp(-x / k));
        }

        public static double recencyDecay(double months) {
            if (months < 0.0) months = 0.0;
            return 100.0 * Math.exp(-months / 12.0);
        }

        public static double recencyHalfLife(double months) {
            if (months < 0.0) months = 0.0;
            return 100.0 * Math.pow(0.5, months / 12.0);
        }

        public static double mergeRate(int merged, int decided) {
            return 100.0 * ((double) merged + 2.0) / ((double) decided + 4.0);
        }

        public static Double questionnaireLevelToScore(String level) {
            if (level == null) return null;
            return switch (level.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "beginner" -> 20.0;
                case "basic" -> 40.0;
                case "intermediate" -> 60.0;
                case "advanced" -> 75.0;
                case "expert" -> 90.0;
                default -> null;
            };
        }

        public record SkillComputationResult(
                boolean available,
                double score,
                double confidence,
                String source,
                double nominalCoverageAt,
                int effectiveEpisodes,
                String explanation
        ) {
            public static SkillComputationResult unavailable(double nominalCoverageAt, String reason) {
                return new SkillComputationResult(false, 0.0, 0.0, "unavailable", nominalCoverageAt, 0, reason);
            }

            public static SkillComputationResult unavailable(String reason) {
                return unavailable(0.0, reason);
            }
        }

        public SkillComputationResult computeSkill() {
            // Check questionnaire score
            Double qScore = null;
            if (Boolean.TRUE.equals(hasQuestionnaire)) {
                if (questionnaireScore != null && !Double.isNaN(questionnaireScore)) {
                    qScore = questionnaireScore;
                } else if (questionnaireLevel != null) {
                    qScore = questionnaireLevelToScore(questionnaireLevel);
                }
            }

            // Observed skill components
            double sumAvailableWeights = 0.0;
            double weightedSum = 0.0;

            if (Boolean.TRUE.equals(repoAvailable) && uniqueContributedRepositories != null) {
                double rScore = sat(uniqueContributedRepositories, 4.0);
                weightedSum += 0.15 * rScore;
                sumAvailableWeights += 0.15;
            }

            if (Boolean.TRUE.equals(standaloneCommitsAvailable) && standaloneCommits != null) {
                double cScore = sat(standaloneCommits, 80.0);
                weightedSum += 0.20 * cScore;
                sumAvailableWeights += 0.20;
            }

            if (Boolean.TRUE.equals(prVolumeAvailable) && uniqueAuthoredPrs != null) {
                double prScore = sat(uniqueAuthoredPrs, 8.0);
                weightedSum += 0.20 * prScore;
                sumAvailableWeights += 0.20;
            }

            if (Boolean.TRUE.equals(mergeRateAvailable) && decidedPrs != null && decidedPrs >= 5 && mergedDecidedPrs != null) {
                double mrScore = mergeRate(mergedDecidedPrs, decidedPrs);
                weightedSum += 0.30 * mrScore;
                sumAvailableWeights += 0.30;
            }

            if (Boolean.TRUE.equals(recencyAvailable) && monthsSinceLastActivity != null) {
                double recScore = recencyDecay(monthsSinceLastActivity);
                weightedSum += 0.15 * recScore;
                sumAvailableWeights += 0.15;
            }

            boolean observedAvailable = false;
            double skillObs = 0.0;
            double cObs = 0.0;
            int nEff = 0;

            if (Boolean.TRUE.equals(profileCoverageKnown) && Boolean.TRUE.equals(sourceQualityKnown) && sumAvailableWeights >= 0.40) {
                skillObs = weightedSum / sumAvailableWeights;
                observedAvailable = true;

                int prCount = (Boolean.TRUE.equals(prVolumeAvailable) && uniqueAuthoredPrs != null) ? Math.max(0, uniqueAuthoredPrs) : 0;
                int commitCount = (Boolean.TRUE.equals(standaloneCommitsAvailable) && standaloneCommits != null) ? Math.max(0, standaloneCommits) : 0;
                nEff = prCount + commitCount;

                double pCov = profileCoverage != null ? Math.max(0.0, Math.min(1.0, profileCoverage)) : 1.0;
                double sQual = sourceQuality != null ? Math.max(0.0, Math.min(1.0, sourceQuality)) : 1.0;
                cObs = Math.min(1.0, (double) nEff / 10.0) * pCov * sQual;
            }

            // Case 1: Pure questionnaire
            if (!observedAvailable && qScore != null) {
                return new SkillComputationResult(
                        true, qScore, 0.40, "self_reported", sumAvailableWeights, nEff,
                        "Observed evidence below coverage threshold; scored via self-reported questionnaire."
                );
            }

            // Case 2: Blended observed + questionnaire
            if (observedAvailable && qScore != null) {
                double qConf = 0.40;
                double qWeight = Math.min(0.20, qConf / (cObs + qConf));
                double finalScore = (1.0 - qWeight) * skillObs + qWeight * qScore;
                double finalConf = (1.0 - qWeight) * cObs + qWeight * qConf;
                return new SkillComputationResult(
                        true, finalScore, finalConf, "blended", sumAvailableWeights, nEff,
                        String.format(java.util.Locale.ROOT, "Blended observed (%.2f) and questionnaire (%.2f) with q_weight=%.4f", skillObs, qScore, qWeight)
                );
            }

            // Case 3: Pure observed
            if (observedAvailable) {
                return new SkillComputationResult(
                        true, skillObs, cObs, "observed", sumAvailableWeights, nEff,
                        String.format(java.util.Locale.ROOT, "Observed skill score %.2f with confidence %.4f over %d episodes.", skillObs, cObs, nEff)
                );
            }

            // Case 4: Neither available
            return SkillComputationResult.unavailable(
                    sumAvailableWeights,
                    String.format(java.util.Locale.ROOT, "Nominal coverage A_t = %.2f < 0.40 and no questionnaire report available.", sumAvailableWeights)
            );
        }

        // Getters and setters
        public String getTechnology() { return technology; }
        public void setTechnology(String technology) { this.technology = technology; }

        public Integer getUniqueContributedRepositories() { return uniqueContributedRepositories; }
        public void setUniqueContributedRepositories(Integer uniqueContributedRepositories) { this.uniqueContributedRepositories = uniqueContributedRepositories; }

        public Boolean getRepoAvailable() { return repoAvailable; }
        public void setRepoAvailable(Boolean repoAvailable) { this.repoAvailable = repoAvailable; }

        public Integer getStandaloneCommits() { return standaloneCommits; }
        public void setStandaloneCommits(Integer standaloneCommits) { this.standaloneCommits = standaloneCommits; }

        public Boolean getStandaloneCommitsAvailable() { return standaloneCommitsAvailable; }
        public void setStandaloneCommitsAvailable(Boolean standaloneCommitsAvailable) { this.standaloneCommitsAvailable = standaloneCommitsAvailable; }

        public Integer getUniqueAuthoredPrs() { return uniqueAuthoredPrs; }
        public void setUniqueAuthoredPrs(Integer uniqueAuthoredPrs) { this.uniqueAuthoredPrs = uniqueAuthoredPrs; }

        public Boolean getPrVolumeAvailable() { return prVolumeAvailable; }
        public void setPrVolumeAvailable(Boolean prVolumeAvailable) { this.prVolumeAvailable = prVolumeAvailable; }

        public Integer getMergedDecidedPrs() { return mergedDecidedPrs; }
        public void setMergedDecidedPrs(Integer mergedDecidedPrs) { this.mergedDecidedPrs = mergedDecidedPrs; }

        public Integer getDecidedPrs() { return decidedPrs; }
        public void setDecidedPrs(Integer decidedPrs) { this.decidedPrs = decidedPrs; }

        public Boolean getMergeRateAvailable() { return mergeRateAvailable; }
        public void setMergeRateAvailable(Boolean mergeRateAvailable) { this.mergeRateAvailable = mergeRateAvailable; }

        public Double getMonthsSinceLastActivity() { return monthsSinceLastActivity; }
        public void setMonthsSinceLastActivity(Double monthsSinceLastActivity) { this.monthsSinceLastActivity = monthsSinceLastActivity; }

        public Boolean getRecencyAvailable() { return recencyAvailable; }
        public void setRecencyAvailable(Boolean recencyAvailable) { this.recencyAvailable = recencyAvailable; }

        public Double getProfileCoverage() { return profileCoverage; }
        public void setProfileCoverage(Double profileCoverage) { this.profileCoverage = profileCoverage; }

        public Double getSourceQuality() { return sourceQuality; }
        public void setSourceQuality(Double sourceQuality) { this.sourceQuality = sourceQuality; }

        public Boolean getProfileCoverageKnown() { return profileCoverageKnown; }
        public void setProfileCoverageKnown(Boolean profileCoverageKnown) { this.profileCoverageKnown = profileCoverageKnown; }

        public Boolean getSourceQualityKnown() { return sourceQualityKnown; }
        public void setSourceQualityKnown(Boolean sourceQualityKnown) { this.sourceQualityKnown = sourceQualityKnown; }

        public Boolean getHasQuestionnaire() { return hasQuestionnaire; }
        public void setHasQuestionnaire(Boolean hasQuestionnaire) { this.hasQuestionnaire = hasQuestionnaire; }

        public String getQuestionnaireLevel() { return questionnaireLevel; }
        public void setQuestionnaireLevel(String questionnaireLevel) { this.questionnaireLevel = questionnaireLevel; }

        public Double getQuestionnaireScore() { return questionnaireScore; }
        public void setQuestionnaireScore(Double questionnaireScore) { this.questionnaireScore = questionnaireScore; }
    }
}

