package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Version 2.3 evidence payload models conforming to Issue Pilot V2.3
 * specification.
 * Eliminates legacy V1 proxies (e.g. historical effort HE and comment count
 * proxies)
 * and uses structured, traceable anchors.
 */
public final class EvidenceModelsV2 {

    private EvidenceModelsV2() {
    }

    /**
     * V2.3 Difficulty Evidence: 4 signals with weights SC (30%), TC (30%), DI
     * (20%), CC (20%).
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

        @JsonProperty("scope_available")
        private Boolean scopeAvailable;

        @JsonProperty("technical_available")
        private Boolean technicalAvailable;

        @JsonProperty("dependency_available")
        private Boolean dependencyAvailable;

        @JsonProperty("coordination_available")
        private Boolean coordinationAvailable;

        @JsonProperty("scope_confidence")
        private Double scopeConfidence = 1.0;

        @JsonProperty("technical_confidence")
        private Double technicalConfidence = 1.0;

        @JsonProperty("dependency_confidence")
        private Double dependencyConfidence = 1.0;

        @JsonProperty("coordination_confidence")
        private Double coordinationConfidence = 1.0;

        @JsonProperty("scope_directness")
        private String scopeDirectness = "explicit_structured";

        @JsonProperty("technical_directness")
        private String technicalDirectness = "explicit_structured";

        @JsonProperty("dependency_directness")
        private String dependencyDirectness = "explicit_structured";

        @JsonProperty("coordination_directness")
        private String coordinationDirectness = "explicit_structured";

        @JsonProperty("label_adjustment")
        private Double labelAdjustment = 0.0;

        public DifficultyEvidenceV2() {
        }

        public DifficultyEvidenceV2(Double scopeChangeSize, Double technicalComplexity,
                Double dependencyArchImpact, Double coordinationComplexity) {
            this.scopeChangeSize = scopeChangeSize;
            this.technicalComplexity = technicalComplexity;
            this.dependencyArchImpact = dependencyArchImpact;
            this.coordinationComplexity = coordinationComplexity;
            if (scopeChangeSize != null)
                this.scopeAvailable = isValidAnchor(scopeChangeSize);
            if (technicalComplexity != null)
                this.technicalAvailable = isValidAnchor(technicalComplexity);
            if (dependencyArchImpact != null)
                this.dependencyAvailable = isValidAnchor(dependencyArchImpact);
            if (coordinationComplexity != null)
                this.coordinationAvailable = isValidAnchor(coordinationComplexity);
        }

        public static boolean isValidAnchor(Double val) {
            if (val == null || !Double.isFinite(val))
                return false;
            double v = val;
            return Math.abs(v - 0.0) < 1e-6 ||
                    Math.abs(v - 25.0) < 1e-6 ||
                    Math.abs(v - 50.0) < 1e-6 ||
                    Math.abs(v - 75.0) < 1e-6 ||
                    Math.abs(v - 100.0) < 1e-6;
        }

        public static double directnessToConfidence(String directness) {
            if (directness == null)
                return 0.55;
            return switch (directness.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "structured", "explicit_structured" -> 1.00;
                case "unambiguous_prose", "explicit_prose" -> 0.80;
                case "lexical_context", "contextual_lexical" -> 0.55;
                default -> 0.55;
            };
        }

        public static double computeLabelCorroboration(java.util.List<String> labels) {
            if (labels == null || labels.isEmpty())
                return 0.0;
            double adj = 0.0;
            for (String l : labels) {
                if (l == null)
                    continue;
                String norm = l.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                if (norm.contains("good-first-issue") || norm.contains("good first issue") ||
                        norm.contains("easy") || norm.contains("beginner") ||
                        norm.contains("starter") || norm.contains("trivial") ||
                        norm.contains("low-hanging-fruit") || norm.contains("first-timers-only") ||
                        norm.contains("quick-win")) {
                    adj -= 1.5;
                } else if (norm.contains("hard") || norm.contains("complex") ||
                        norm.contains("advanced") || norm.contains("expert") ||
                        norm.contains("epic") || norm.contains("high-complexity") ||
                        norm.contains("difficult")) {
                    adj += 1.5;
                }
            }
            return Math.max(-3.0, Math.min(3.0, adj));
        }

        public record DifficultyComputationResult(
                boolean available,
                double issueDifficulty,
                double rawDifficulty,
                double confidence,
                double coverageAid,
                double labelAdjustment,
                String explanation) {
            public static DifficultyComputationResult unavailable(double coverageAid, String reason) {
                return new DifficultyComputationResult(false, 0.0, 0.0, 0.0, coverageAid, 0.0, reason);
            }
        }

        public record FitComputationResult(
                boolean available,
                double difficultyMatchScore,
                double confidence,
                double fitGap,
                double penalty,
                String explanation) {
            public static FitComputationResult unavailable(String reason) {
                return new FitComputationResult(false, 0.0, 0.0, 0.0, 0.0, reason);
            }
        }

        public DifficultyComputationResult computeDifficulty(java.util.List<String> labels) {
            double aId = 0.0;
            double weightedSum = 0.0;
            double confSum = 0.0;

            // SC: 0.30
            boolean scOk = Boolean.TRUE.equals(scopeAvailable) || (scopeAvailable == null && scopeChangeSize != null);
            if (scOk && isValidAnchor(scopeChangeSize)) {
                double cSc = scopeConfidence != null ? scopeConfidence : directnessToConfidence(scopeDirectness);
                if (cSc < 0.0 || cSc > 1.0 || !Double.isFinite(cSc))
                    throw new IllegalArgumentException("Invalid SC confidence: " + cSc);
                aId += 0.30;
                weightedSum += 0.30 * scopeChangeSize;
                confSum += 0.30 * cSc;
            }

            // TC: 0.30
            boolean tcOk = Boolean.TRUE.equals(technicalAvailable)
                    || (technicalAvailable == null && technicalComplexity != null);
            if (tcOk && isValidAnchor(technicalComplexity)) {
                double cTc = technicalConfidence != null ? technicalConfidence
                        : directnessToConfidence(technicalDirectness);
                if (cTc < 0.0 || cTc > 1.0 || !Double.isFinite(cTc))
                    throw new IllegalArgumentException("Invalid TC confidence: " + cTc);
                aId += 0.30;
                weightedSum += 0.30 * technicalComplexity;
                confSum += 0.30 * cTc;
            }

            // DI: 0.20
            boolean diOk = Boolean.TRUE.equals(dependencyAvailable)
                    || (dependencyAvailable == null && dependencyArchImpact != null);
            if (diOk && isValidAnchor(dependencyArchImpact)) {
                double cDi = dependencyConfidence != null ? dependencyConfidence
                        : directnessToConfidence(dependencyDirectness);
                if (cDi < 0.0 || cDi > 1.0 || !Double.isFinite(cDi))
                    throw new IllegalArgumentException("Invalid DI confidence: " + cDi);
                aId += 0.20;
                weightedSum += 0.20 * dependencyArchImpact;
                confSum += 0.20 * cDi;
            }

            // CC: 0.20
            boolean ccOk = Boolean.TRUE.equals(coordinationAvailable)
                    || (coordinationAvailable == null && coordinationComplexity != null);
            if (ccOk && isValidAnchor(coordinationComplexity)) {
                double cCc = coordinationConfidence != null ? coordinationConfidence
                        : directnessToConfidence(coordinationDirectness);
                if (cCc < 0.0 || cCc > 1.0 || !Double.isFinite(cCc))
                    throw new IllegalArgumentException("Invalid CC confidence: " + cCc);
                aId += 0.20;
                weightedSum += 0.20 * coordinationComplexity;
                confSum += 0.20 * cCc;
            }

            if (aId < 0.50 - 1e-9) {
                return DifficultyComputationResult.unavailable(aId,
                        String.format(java.util.Locale.ROOT,
                                "Coverage A_ID = %.4f < 0.50 gate; difficulty is unavailable.", aId));
            }

            double idRaw = weightedSum / aId;
            double cId = confSum; // C_ID = sum_j (q_j * a_j * c_j)

            double lAdj = 0.0;
            if (labelAdjustment != null && Math.abs(labelAdjustment) > 1e-6) {
                lAdj = Math.max(-3.0, Math.min(3.0, labelAdjustment));
            } else if (labels != null && !labels.isEmpty()) {
                lAdj = computeLabelCorroboration(labels);
            }

            double finalId = Math.max(0.0, Math.min(100.0, idRaw + lAdj));
            return new DifficultyComputationResult(
                    true, finalId, idRaw, cId, aId, lAdj,
                    String.format(java.util.Locale.ROOT, "ID=%.2f (raw=%.2f, label_adj=%.2f, A_ID=%.2f, C_ID=%.4f)",
                            finalId, idRaw, lAdj, aId, cId));
        }

        public static FitComputationResult computeFit(
                Double issueDifficulty, Double confidenceId,
                Double contributorCapability, Double confidenceCap) {
            if (issueDifficulty == null || !Double.isFinite(issueDifficulty) ||
                    contributorCapability == null || !Double.isFinite(contributorCapability)) {
                return FitComputationResult.unavailable("Missing issue difficulty or contributor capability.");
            }
            double cId = confidenceId != null ? Math.max(0.0, Math.min(1.0, confidenceId)) : 0.0;
            double cCap = confidenceCap != null ? Math.max(0.0, Math.min(1.0, confidenceCap)) : 0.0;

            double g = issueDifficulty - contributorCapability;
            double penalty;
            if (Math.abs(g) <= 5.0) {
                penalty = 0.0;
            } else if (g > 5.0) {
                penalty = 1.5 * (g - 5.0);
            } else {
                penalty = -g - 5.0; // (-g - 5) == (|g| - 5)
            }

            double d = Math.max(0.0, Math.min(100.0, 100.0 - penalty));
            double cD = Math.min(cId, cCap);

            return new FitComputationResult(
                    true, d, cD, g, penalty,
                    String.format(java.util.Locale.ROOT, "Fit gap g=%.2f, penalty=%.2f, D=%.2f, C_D=%.4f", g, penalty,
                            d, cD));
        }

        public Double getScopeChangeSize() {
            return scopeChangeSize;
        }

        public void setScopeChangeSize(Double scopeChangeSize) {
            this.scopeChangeSize = scopeChangeSize;
        }

        public Double getTechnicalComplexity() {
            return technicalComplexity;
        }

        public void setTechnicalComplexity(Double technicalComplexity) {
            this.technicalComplexity = technicalComplexity;
        }

        public Double getDependencyArchImpact() {
            return dependencyArchImpact;
        }

        public void setDependencyArchImpact(Double dependencyArchImpact) {
            this.dependencyArchImpact = dependencyArchImpact;
        }

        public Double getCoordinationComplexity() {
            return coordinationComplexity;
        }

        public void setCoordinationComplexity(Double coordinationComplexity) {
            this.coordinationComplexity = coordinationComplexity;
        }

        public Boolean getScopeAvailable() {
            return scopeAvailable;
        }

        public void setScopeAvailable(Boolean scopeAvailable) {
            this.scopeAvailable = scopeAvailable;
        }

        public Boolean getTechnicalAvailable() {
            return technicalAvailable;
        }

        public void setTechnicalAvailable(Boolean technicalAvailable) {
            this.technicalAvailable = technicalAvailable;
        }

        public Boolean getDependencyAvailable() {
            return dependencyAvailable;
        }

        public void setDependencyAvailable(Boolean dependencyAvailable) {
            this.dependencyAvailable = dependencyAvailable;
        }

        public Boolean getCoordinationAvailable() {
            return coordinationAvailable;
        }

        public void setCoordinationAvailable(Boolean coordinationAvailable) {
            this.coordinationAvailable = coordinationAvailable;
        }

        public Double getScopeConfidence() {
            return scopeConfidence;
        }

        public void setScopeConfidence(Double scopeConfidence) {
            this.scopeConfidence = scopeConfidence;
        }

        public Double getTechnicalConfidence() {
            return technicalConfidence;
        }

        public void setTechnicalConfidence(Double technicalConfidence) {
            this.technicalConfidence = technicalConfidence;
        }

        public Double getDependencyConfidence() {
            return dependencyConfidence;
        }

        public void setDependencyConfidence(Double dependencyConfidence) {
            this.dependencyConfidence = dependencyConfidence;
        }

        public Double getCoordinationConfidence() {
            return coordinationConfidence;
        }

        public void setCoordinationConfidence(Double coordinationConfidence) {
            this.coordinationConfidence = coordinationConfidence;
        }

        public String getScopeDirectness() {
            return scopeDirectness;
        }

        public void setScopeDirectness(String scopeDirectness) {
            this.scopeDirectness = scopeDirectness;
        }

        public String getTechnicalDirectness() {
            return technicalDirectness;
        }

        public void setTechnicalDirectness(String technicalDirectness) {
            this.technicalDirectness = technicalDirectness;
        }

        public String getDependencyDirectness() {
            return dependencyDirectness;
        }

        public void setDependencyDirectness(String dependencyDirectness) {
            this.dependencyDirectness = dependencyDirectness;
        }

        public String getCoordinationDirectness() {
            return coordinationDirectness;
        }

        public void setCoordinationDirectness(String coordinationDirectness) {
            this.coordinationDirectness = coordinationDirectness;
        }

        public Double getLabelAdjustment() {
            return labelAdjustment;
        }

        public void setLabelAdjustment(Double labelAdjustment) {
            this.labelAdjustment = labelAdjustment;
        }
    }

    /**
     * V2.3 Maintainer Responsiveness Evidence: RT (30%), RR (30%), PR (25%), MA
     * (15%).
     * Measured over mature cohorts (168h complete observation) and verified human
     * maintainer roles.
     * Enforces minimum 5 samples per signal, at least 2 available signals, and
     * coverage A_R >= 0.45.
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

        @JsonProperty("rt_available")
        private Boolean rtAvailable;

        @JsonProperty("rr_available")
        private Boolean rrAvailable;

        @JsonProperty("pr_available")
        private Boolean prAvailable;

        @JsonProperty("ma_available")
        private Boolean maAvailable;

        @JsonProperty("rt_samples")
        private Integer rtSamples;

        @JsonProperty("rr_samples")
        private Integer rrSamples;

        @JsonProperty("pr_samples")
        private Integer prSamples;

        @JsonProperty("ma_samples")
        private Integer maSamples;

        @JsonProperty("rt_confidence")
        private Double rtConfidence = 1.0;

        @JsonProperty("rr_confidence")
        private Double rrConfidence = 1.0;

        @JsonProperty("pr_confidence")
        private Double prConfidence = 1.0;

        @JsonProperty("ma_confidence")
        private Double maConfidence = 1.0;

        @JsonProperty("median_first_reply_hours")
        private Double medianFirstReplyHours;

        @JsonProperty("mature_issues_replied_7d")
        private Integer matureIssuesReplied7d;

        @JsonProperty("mature_issues_total_7d")
        private Integer matureIssuesTotal7d;

        @JsonProperty("pagination_complete")
        private Boolean paginationComplete = true;

        @JsonProperty("role_mapping_verified")
        private Boolean roleMappingVerified = true;

        @JsonProperty("source")
        private String source;

        @JsonProperty("source_cap")
        private Double sourceCap;

        public static double resolveSourceCap(String source, Double explicitCap) {
            if (explicitCap != null) {
                if (explicitCap < 0.0 || explicitCap > 1.0 || !Double.isFinite(explicitCap)) {
                    throw new IllegalArgumentException("Invalid explicit source cap: " + explicitCap);
                }
                return explicitCap;
            }
            if (source == null) return 1.0;
            String s = source.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (s) {
                case "github" -> 0.90;
                case "gitlab" -> 0.85;
                case "forgejo", "codeberg", "gitea" -> 0.80;
                case "bugzilla" -> 0.70;
                case "inferred", "inferred_proxy", "proxy" -> 0.50;
                default -> 1.0;
            };
        }

        public static double calculateSignalConfidence(String source, Double explicitCap, double cSample, double cCoverage, double cDirectness) {
            double cap = resolveSourceCap(source, explicitCap);
            double raw = cSample * cCoverage * cDirectness;
            return Math.min(cap, Math.max(0.0, raw));
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Double getSourceCap() {
            return sourceCap;
        }

        public void setSourceCap(Double sourceCap) {
            this.sourceCap = sourceCap;
        }

        public static final double CLOCK_SKEW_TOLERANCE_DAYS = 0.001; // ~86.4 seconds

        public record IssueResponseEvent(double ageHours, Double firstReplyHours, boolean completeObservation) {
            public boolean isMature() {
                return ageHours >= 168.0 && completeObservation;
            }
            public boolean isTimelyReplied() {
                return isMature() && firstReplyHours != null && firstReplyHours >= 0.0 && firstReplyHours <= 168.0;
            }
        }

        public static double evaluateRrCohort(java.util.List<IssueResponseEvent> issues) {
            int matureTotal = 0;
            int repliedWithin7d = 0;
            if (issues != null) {
                for (IssueResponseEvent e : issues) {
                    if (e.isMature()) {
                        matureTotal++;
                        if (e.isTimelyReplied()) {
                            repliedWithin7d++;
                        }
                    }
                }
            }
            return calculateRrScore(repliedWithin7d, matureTotal);
        }

        public ResponsivenessEvidenceV2() {
        }

        public static double calculateRtScore(double medianHours) {
            if (!Double.isFinite(medianHours) || medianHours < 0.0)
                throw new IllegalArgumentException("Negative or non-finite reply duration: " + medianHours);
            return 100.0 / (1.0 + medianHours / 48.0);
        }

        public static double calculateRrScore(int replied7d, int matureTotal7d) {
            if (matureTotal7d <= 0)
                return 0.0;
            return 100.0 * ((double) Math.max(0, replied7d) / (double) matureTotal7d);
        }

        public static double calculatePrScore(double medianReviewHours) {
            if (!Double.isFinite(medianReviewHours) || medianReviewHours < 0.0)
                throw new IllegalArgumentException("Negative or non-finite review duration: " + medianReviewHours);
            return 100.0 / (1.0 + medianReviewHours / 72.0);
        }

        public static double calculateMaScore(double daysSinceAct) {
            if (!Double.isFinite(daysSinceAct))
                throw new IllegalArgumentException("Non-finite daysSinceAct: " + daysSinceAct);
            if (daysSinceAct < -CLOCK_SKEW_TOLERANCE_DAYS)
                throw new IllegalArgumentException("Future timestamp exceeds clock skew tolerance: " + daysSinceAct);
            if (daysSinceAct < 0.0)
                daysSinceAct = 0.0;
            return 100.0 * Math.pow(0.5, daysSinceAct / 14.0);
        }

        public record ResponsivenessComputationResult(
                boolean available,
                double score,
                double confidence,
                double coverageAr,
                int signalCount,
                String explanation) {
            public static ResponsivenessComputationResult unavailable(double coverageAr, int signalCount,
                    String reason) {
                return new ResponsivenessComputationResult(false, 0.0, 0.0, coverageAr, signalCount, reason);
            }
        }

        public ResponsivenessComputationResult computeResponsiveness() {
            if (!Boolean.TRUE.equals(roleMappingVerified)) {
                return ResponsivenessComputationResult.unavailable(0.0, 0,
                        "Maintainer role mapping unverified (e.g. Bugzilla without explicit maintainer role).");
            }
            if (!Boolean.TRUE.equals(paginationComplete)) {
                return ResponsivenessComputationResult.unavailable(0.0, 0, "Comment pagination incomplete.");
            }

            double cap = resolveSourceCap(source, sourceCap);
            double aR = 0.0;
            double weightedSum = 0.0;
            double confSum = 0.0;
            int count = 0;

            // RT: 0.30, needs samples >= 5
            boolean rtOk = (rtAvailable == null || Boolean.TRUE.equals(rtAvailable)) && responseTimeScore != null
                    && (rtSamples == null || rtSamples >= 5);
            if (rtOk) {
                double rawC = rtConfidence != null ? rtConfidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid RT confidence: " + rawC);
                double cRt = Math.min(cap, rawC);
                aR += 0.30;
                weightedSum += 0.30 * Math.max(0.0, Math.min(100.0, responseTimeScore));
                confSum += 0.30 * cRt;
                count++;
            }

            // RR: 0.30, needs samples >= 5
            boolean rrOk = (rrAvailable == null || Boolean.TRUE.equals(rrAvailable)) && responseRateScore != null
                    && (rrSamples == null || rrSamples >= 5);
            if (rrOk) {
                double rawC = rrConfidence != null ? rrConfidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid RR confidence: " + rawC);
                double cRr = Math.min(cap, rawC);
                aR += 0.30;
                weightedSum += 0.30 * Math.max(0.0, Math.min(100.0, responseRateScore));
                confSum += 0.30 * cRr;
                count++;
            }

            // PR: 0.25, needs samples >= 5
            boolean prOk = (prAvailable == null || Boolean.TRUE.equals(prAvailable)) && prReviewScore != null
                    && (prSamples == null || prSamples >= 5);
            if (prOk) {
                double rawC = prConfidence != null ? prConfidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid PR confidence: " + rawC);
                double cPr = Math.min(cap, rawC);
                aR += 0.25;
                weightedSum += 0.25 * Math.max(0.0, Math.min(100.0, prReviewScore));
                confSum += 0.25 * cPr;
                count++;
            }

            // MA: 0.15, needs samples >= 5 (if samples specified)
            boolean maOk = (maAvailable == null || Boolean.TRUE.equals(maAvailable)) && recentMaintainerAct != null
                    && (maSamples == null || maSamples >= 5);
            if (maOk) {
                double rawC = maConfidence != null ? maConfidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid MA confidence: " + rawC);
                double cMa = Math.min(cap, rawC);
                aR += 0.15;
                weightedSum += 0.15 * Math.max(0.0, Math.min(100.0, recentMaintainerAct));
                confSum += 0.15 * cMa;
                count++;
            }

            if (count < 2 || aR < 0.45 - 1e-9) {
                return ResponsivenessComputationResult.unavailable(aR, count,
                        String.format(java.util.Locale.ROOT,
                                "R requires >= 2 available signals and A_R >= 0.45 (found count=%d, A_R=%.4f).", count,
                                aR));
            }

            double rScore = weightedSum / aR;
            double cScore = confSum;
            return new ResponsivenessComputationResult(
                    true, rScore, cScore, aR, count,
                    String.format(java.util.Locale.ROOT, "R=%.2f (A_R=%.2f, C_R=%.4f, count=%d)", rScore, aR, cScore,
                            count));
        }

        public Double getResponseTimeScore() {
            return responseTimeScore;
        }

        public void setResponseTimeScore(Double responseTimeScore) {
            this.responseTimeScore = responseTimeScore;
        }

        public Double getResponseRateScore() {
            return responseRateScore;
        }

        public void setResponseRateScore(Double responseRateScore) {
            this.responseRateScore = responseRateScore;
        }

        public Double getPrReviewScore() {
            return prReviewScore;
        }

        public void setPrReviewScore(Double prReviewScore) {
            this.prReviewScore = prReviewScore;
        }

        public Double getRecentMaintainerAct() {
            return recentMaintainerAct;
        }

        public void setRecentMaintainerAct(Double recentMaintainerAct) {
            this.recentMaintainerAct = recentMaintainerAct;
        }

        public Boolean getRtAvailable() {
            return rtAvailable;
        }

        public void setRtAvailable(Boolean rtAvailable) {
            this.rtAvailable = rtAvailable;
        }

        public Boolean getRrAvailable() {
            return rrAvailable;
        }

        public void setRrAvailable(Boolean rrAvailable) {
            this.rrAvailable = rrAvailable;
        }

        public Boolean getPrAvailable() {
            return prAvailable;
        }

        public void setPrAvailable(Boolean prAvailable) {
            this.prAvailable = prAvailable;
        }

        public Boolean getMaAvailable() {
            return maAvailable;
        }

        public void setMaAvailable(Boolean maAvailable) {
            this.maAvailable = maAvailable;
        }

        public Integer getRtSamples() {
            return rtSamples;
        }

        public void setRtSamples(Integer rtSamples) {
            this.rtSamples = rtSamples;
        }

        public Integer getRrSamples() {
            return rrSamples;
        }

        public void setRrSamples(Integer rrSamples) {
            this.rrSamples = rrSamples;
        }

        public Integer getPrSamples() {
            return prSamples;
        }

        public void setPrSamples(Integer prSamples) {
            this.prSamples = prSamples;
        }

        public Integer getMaSamples() {
            return maSamples;
        }

        public void setMaSamples(Integer maSamples) {
            this.maSamples = maSamples;
        }

        public Double getRtConfidence() {
            return rtConfidence;
        }

        public void setRtConfidence(Double rtConfidence) {
            this.rtConfidence = rtConfidence;
        }

        public Double getRrConfidence() {
            return rrConfidence;
        }

        public void setRrConfidence(Double rrConfidence) {
            this.rrConfidence = rrConfidence;
        }

        public Double getPrConfidence() {
            return prConfidence;
        }

        public void setPrConfidence(Double prConfidence) {
            this.prConfidence = prConfidence;
        }

        public Double getMaConfidence() {
            return maConfidence;
        }

        public void setMaConfidence(Double maConfidence) {
            this.maConfidence = maConfidence;
        }

        public Double getMedianFirstReplyHours() {
            return medianFirstReplyHours;
        }

        public void setMedianFirstReplyHours(Double medianFirstReplyHours) {
            this.medianFirstReplyHours = medianFirstReplyHours;
        }

        public Integer getMatureIssuesReplied7d() {
            return matureIssuesReplied7d;
        }

        public void setMatureIssuesReplied7d(Integer matureIssuesReplied7d) {
            this.matureIssuesReplied7d = matureIssuesReplied7d;
        }

        public Integer getMatureIssuesTotal7d() {
            return matureIssuesTotal7d;
        }

        public void setMatureIssuesTotal7d(Integer matureIssuesTotal7d) {
            this.matureIssuesTotal7d = matureIssuesTotal7d;
        }

        public Boolean getPaginationComplete() {
            return paginationComplete;
        }

        public void setPaginationComplete(Boolean paginationComplete) {
            this.paginationComplete = paginationComplete;
        }

        public Boolean getRoleMappingVerified() {
            return roleMappingVerified;
        }

        public void setRoleMappingVerified(Boolean roleMappingVerified) {
            this.roleMappingVerified = roleMappingVerified;
        }
    }

    /**
     * V2.3 Repository Health Evidence: direct code-host activity only.
     * C90 (30%), PR30 (25%), AU90 (25%), MA90 (20%).
     * Enforces at least 2 direct signals and available nominal weight A_H >= 0.40.
     * Bugzilla H is strictly unavailable.
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

        @JsonProperty("c90_available")
        private Boolean c90Available;

        @JsonProperty("pr30_available")
        private Boolean pr30Available;

        @JsonProperty("au90_available")
        private Boolean au90Available;

        @JsonProperty("ma90_available")
        private Boolean ma90Available;

        @JsonProperty("c90_confidence")
        private Double c90Confidence = 1.0;

        @JsonProperty("pr30_confidence")
        private Double pr30Confidence = 1.0;

        @JsonProperty("au90_confidence")
        private Double au90Confidence = 1.0;

        @JsonProperty("ma90_confidence")
        private Double ma90Confidence = 1.0;

        @JsonProperty("human_commits_90d")
        private Integer humanCommits90d;

        @JsonProperty("merged_prs_30d")
        private Integer mergedPrs30d;

        @JsonProperty("distinct_authors_90d")
        private Integer distinctAuthors90d;

        @JsonProperty("days_since_code_activity")
        private Double daysSinceCodeActivity;

        @JsonProperty("code_host_available")
        private Boolean codeHostAvailable = true;

        @JsonProperty("source")
        private String source;

        @JsonProperty("source_cap")
        private Double sourceCap;

        public static double resolveSourceCap(String source, Double explicitCap) {
            return ResponsivenessEvidenceV2.resolveSourceCap(source, explicitCap);
        }

        public static double calculateSignalConfidence(String source, Double explicitCap, double cSample, double cCoverage, double cDirectness) {
            return ResponsivenessEvidenceV2.calculateSignalConfidence(source, explicitCap, cSample, cCoverage, cDirectness);
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Double getSourceCap() {
            return sourceCap;
        }

        public void setSourceCap(Double sourceCap) {
            this.sourceCap = sourceCap;
        }

        public RepositoryHealthEvidenceV2() {
        }

        public static double calculateC90Score(int humanCommits90d) {
            return 100.0 * Math.min(1.0, Math.log(1.0 + Math.max(0, humanCommits90d)) / Math.log(61.0));
        }

        public static double calculatePr30Score(int mergedPrs30d) {
            return 100.0 * Math.min(1.0, Math.max(0, mergedPrs30d) / 8.0);
        }

        public static double calculateAu90Score(int distinctAuthors90d) {
            return 100.0 * Math.min(1.0, Math.max(0, distinctAuthors90d) / 6.0);
        }

        public static final double CLOCK_SKEW_TOLERANCE_DAYS = 0.001; // ~86.4 seconds

        public static double calculateMa90Score(double daysSinceCodeActivity) {
            if (!Double.isFinite(daysSinceCodeActivity))
                throw new IllegalArgumentException("Non-finite daysSinceCodeActivity: " + daysSinceCodeActivity);
            if (daysSinceCodeActivity < -CLOCK_SKEW_TOLERANCE_DAYS)
                throw new IllegalArgumentException("Future timestamp exceeds clock skew tolerance: " + daysSinceCodeActivity);
            if (daysSinceCodeActivity < 0.0)
                daysSinceCodeActivity = 0.0;
            return 100.0 * Math.pow(0.5, daysSinceCodeActivity / 90.0);
        }

        public record HealthComputationResult(
                boolean available,
                double score,
                double confidence,
                double coverageAh,
                int signalCount,
                String explanation) {
            public static HealthComputationResult unavailable(double coverageAh, int signalCount, String reason) {
                return new HealthComputationResult(false, 0.0, 0.0, coverageAh, signalCount, reason);
            }
        }

        public HealthComputationResult computeHealth() {
            if (!Boolean.TRUE.equals(codeHostAvailable)) {
                return HealthComputationResult.unavailable(0.0, 0,
                        "Direct code-host evidence unavailable (Bugzilla H unavailable).");
            }

            double cap = resolveSourceCap(source, sourceCap);
            double aH = 0.0;
            double weightedSum = 0.0;
            double confSum = 0.0;
            int count = 0;

            // C90: 0.30
            boolean c90Ok = (c90Available == null || Boolean.TRUE.equals(c90Available)) && c90Score != null
                    && !Double.isNaN(c90Score);
            if (c90Ok) {
                double rawC = c90Confidence != null ? c90Confidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid C90 confidence: " + rawC);
                double c = Math.min(cap, rawC);
                aH += 0.30;
                weightedSum += 0.30 * Math.max(0.0, Math.min(100.0, c90Score));
                confSum += 0.30 * c;
                count++;
            }

            // PR30: 0.25
            boolean pr30Ok = (pr30Available == null || Boolean.TRUE.equals(pr30Available)) && pr30Score != null
                    && !Double.isNaN(pr30Score);
            if (pr30Ok) {
                double rawC = pr30Confidence != null ? pr30Confidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid PR30 confidence: " + rawC);
                double c = Math.min(cap, rawC);
                aH += 0.25;
                weightedSum += 0.25 * Math.max(0.0, Math.min(100.0, pr30Score));
                confSum += 0.25 * c;
                count++;
            }

            // AU90: 0.25
            boolean au90Ok = (au90Available == null || Boolean.TRUE.equals(au90Available)) && au90Score != null
                    && !Double.isNaN(au90Score);
            if (au90Ok) {
                double rawC = au90Confidence != null ? au90Confidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid AU90 confidence: " + rawC);
                double c = Math.min(cap, rawC);
                aH += 0.25;
                weightedSum += 0.25 * Math.max(0.0, Math.min(100.0, au90Score));
                confSum += 0.25 * c;
                count++;
            }

            // MA90: 0.20
            boolean ma90Ok = (ma90Available == null || Boolean.TRUE.equals(ma90Available)) && ma90Score != null
                    && !Double.isNaN(ma90Score);
            if (ma90Ok) {
                double rawC = ma90Confidence != null ? ma90Confidence : 1.0;
                if (rawC < 0.0 || rawC > 1.0 || !Double.isFinite(rawC))
                    throw new IllegalArgumentException("Invalid MA90 confidence: " + rawC);
                double c = Math.min(cap, rawC);
                aH += 0.20;
                weightedSum += 0.20 * Math.max(0.0, Math.min(100.0, ma90Score));
                confSum += 0.20 * c;
                count++;
            }

            if (count < 2 || aH < 0.40 - 1e-9) {
                return HealthComputationResult.unavailable(aH, count,
                        String.format(java.util.Locale.ROOT,
                                "H requires >= 2 direct signals and A_H >= 0.40 (found count=%d, A_H=%.4f).", count,
                                aH));
            }

            double hScore = weightedSum / aH;
            double cScore = confSum;
            return new HealthComputationResult(
                    true, hScore, cScore, aH, count,
                    String.format(java.util.Locale.ROOT, "H=%.2f (A_H=%.2f, C_H=%.4f, count=%d)", hScore, aH, cScore,
                            count));
        }

        public Double computeHealthScore() {
            var res = computeHealth();
            return res.available() ? res.score() : null;
        }

        public static RepositoryHealthEvidenceV2 fromMeasurements(
                Integer humanCommits90d,
                Integer mergedPrs30d,
                Integer distinctAuthors90d,
                Double daysSinceCodeActivity) {
            RepositoryHealthEvidenceV2 h = new RepositoryHealthEvidenceV2();
            if (humanCommits90d != null) {
                h.setHumanCommits90d(humanCommits90d);
                h.setC90Score(calculateC90Score(humanCommits90d));
                h.setC90Available(true);
            }
            if (mergedPrs30d != null) {
                h.setMergedPrs30d(mergedPrs30d);
                h.setPr30Score(calculatePr30Score(mergedPrs30d));
                h.setPr30Available(true);
            }
            if (distinctAuthors90d != null) {
                h.setDistinctAuthors90d(distinctAuthors90d);
                h.setAu90Score(calculateAu90Score(distinctAuthors90d));
                h.setAu90Available(true);
            }
            if (daysSinceCodeActivity != null) {
                h.setDaysSinceCodeActivity(daysSinceCodeActivity);
                h.setMa90Score(calculateMa90Score(daysSinceCodeActivity));
                h.setMa90Available(true);
            }
            return h;
        }

        public Double getC90Score() {
            return c90Score;
        }

        public void setC90Score(Double c90Score) {
            this.c90Score = c90Score;
        }

        public Double getPr30Score() {
            return pr30Score;
        }

        public void setPr30Score(Double pr30Score) {
            this.pr30Score = pr30Score;
        }

        public Double getAu90Score() {
            return au90Score;
        }

        public void setAu90Score(Double au90Score) {
            this.au90Score = au90Score;
        }

        public Double getMa90Score() {
            return ma90Score;
        }

        public void setMa90Score(Double ma90Score) {
            this.ma90Score = ma90Score;
        }

        public Boolean getC90Available() {
            return c90Available;
        }

        public void setC90Available(Boolean c90Available) {
            this.c90Available = c90Available;
        }

        public Boolean getPr30Available() {
            return pr30Available;
        }

        public void setPr30Available(Boolean pr30Available) {
            this.pr30Available = pr30Available;
        }

        public Boolean getAu90Available() {
            return au90Available;
        }

        public void setAu90Available(Boolean au90Available) {
            this.au90Available = au90Available;
        }

        public Boolean getMa90Available() {
            return ma90Available;
        }

        public void setMa90Available(Boolean ma90Available) {
            this.ma90Available = ma90Available;
        }

        public Double getC90Confidence() {
            return c90Confidence;
        }

        public void setC90Confidence(Double c90Confidence) {
            this.c90Confidence = c90Confidence;
        }

        public Double getPr30Confidence() {
            return pr30Confidence;
        }

        public void setPr30Confidence(Double pr30Confidence) {
            this.pr30Confidence = pr30Confidence;
        }

        public Double getAu90Confidence() {
            return au90Confidence;
        }

        public void setAu90Confidence(Double au90Confidence) {
            this.au90Confidence = au90Confidence;
        }

        public Double getMa90Confidence() {
            return ma90Confidence;
        }

        public void setMa90Confidence(Double ma90Confidence) {
            this.ma90Confidence = ma90Confidence;
        }

        public Integer getHumanCommits90d() {
            return humanCommits90d;
        }

        public void setHumanCommits90d(Integer humanCommits90d) {
            this.humanCommits90d = humanCommits90d;
        }

        public Integer getMergedPrs30d() {
            return mergedPrs30d;
        }

        public void setMergedPrs30d(Integer mergedPrs30d) {
            this.mergedPrs30d = mergedPrs30d;
        }

        public Integer getDistinctAuthors90d() {
            return distinctAuthors90d;
        }

        public void setDistinctAuthors90d(Integer distinctAuthors90d) {
            this.distinctAuthors90d = distinctAuthors90d;
        }

        public Double getDaysSinceCodeActivity() {
            return daysSinceCodeActivity;
        }

        public void setDaysSinceCodeActivity(Double daysSinceCodeActivity) {
            this.daysSinceCodeActivity = daysSinceCodeActivity;
        }

        public Boolean getCodeHostAvailable() {
            return codeHostAvailable;
        }

        public void setCodeHostAvailable(Boolean codeHostAvailable) {
            this.codeHostAvailable = codeHostAvailable;
        }
    }

    /**
     * V2.3 Freshness Evidence: AgeScore (40%), ActivityScore (60%) with exponential decay.
     * Conforms strictly to Section 10 of V2.3 Specification.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FreshnessEvidenceV2 {
        public static final double CLOCK_SKEW_TOLERANCE_DAYS = 0.001; // ~86.4 seconds
        public static final double AGE_WEIGHT = 0.40;
        public static final double ACTIVITY_WEIGHT = 0.60;

        @JsonProperty("age_days")
        private Double ageDays;

        @JsonProperty("idle_days")
        private Double idleDays;

        @JsonProperty("age_score")
        private Double ageScore;

        @JsonProperty("activity_score")
        private Double activityScore;

        @JsonProperty("age_available")
        private Boolean ageAvailable = true;

        @JsonProperty("activity_available")
        private Boolean activityAvailable = true;

        @JsonProperty("is_bot_activity")
        private Boolean isBotActivity = false;

        @JsonProperty("source")
        private String source = "github";

        @JsonProperty("source_cap")
        private Double sourceCap;

        public FreshnessEvidenceV2() {
        }

        public FreshnessEvidenceV2(Double ageDays, Double idleDays) {
            this.ageDays = ageDays;
            this.idleDays = idleDays;
            if (ageDays != null) {
                this.ageScore = calculateAgeScore(ageDays);
            }
            if (idleDays != null) {
                this.activityScore = calculateActivityScore(idleDays);
            }
        }

        public static double calculateAgeScore(double ageDays) {
            if (Double.isNaN(ageDays) || !Double.isFinite(ageDays)) {
                throw new IllegalArgumentException("Non-finite age days: " + ageDays);
            }
            if (ageDays < -CLOCK_SKEW_TOLERANCE_DAYS) {
                throw new IllegalArgumentException("Creation timestamp materially in future: " + ageDays);
            }
            double clamped = Math.max(0.0, ageDays);
            return 100.0 * Math.pow(0.5, clamped / 14.0);
        }

        public static double calculateActivityScore(double idleDays) {
            if (Double.isNaN(idleDays) || !Double.isFinite(idleDays)) {
                throw new IllegalArgumentException("Non-finite idle days: " + idleDays);
            }
            if (idleDays < -CLOCK_SKEW_TOLERANCE_DAYS) {
                throw new IllegalArgumentException("Meaningful activity timestamp materially in future: " + idleDays);
            }
            double clamped = Math.max(0.0, idleDays);
            return 100.0 * Math.pow(0.5, clamped / 7.0);
        }

        public static double calculateCombinedFreshness(double ageScore, double activityScore) {
            return AGE_WEIGHT * ageScore + ACTIVITY_WEIGHT * activityScore;
        }

        public record FreshnessComputationResult(
                boolean available,
                double score,
                double confidence,
                double coverageAn,
                int signalCount,
                Double ageScore,
                Double activityScore,
                String explanation) {
            public static FreshnessComputationResult unavailable(double coverageAn, int signalCount, String reason) {
                return new FreshnessComputationResult(false, 0.0, 0.0, coverageAn, signalCount, null, null, reason);
            }
        }

        public FreshnessComputationResult computeFreshness() {
            return computeFreshness(this.source, this.sourceCap);
        }

        public FreshnessComputationResult computeFreshness(String sourceOverride, Double capOverride) {
            String effSource = sourceOverride != null ? sourceOverride : this.source;
            Double effCap = capOverride != null ? capOverride : this.sourceCap;
            double cap = ResponsivenessEvidenceV2.resolveSourceCap(effSource, effCap);

            boolean ageOk = (ageAvailable == null || Boolean.TRUE.equals(ageAvailable)) && ageDays != null;
            if (!ageOk) {
                return FreshnessComputationResult.unavailable(0.0, 0, "Issue creation timestamp unavailable");
            }

            double aScore = calculateAgeScore(ageDays);

            boolean actOk = (activityAvailable == null || Boolean.TRUE.equals(activityAvailable))
                    && idleDays != null && !Boolean.TRUE.equals(isBotActivity);
            if (!actOk) {
                // Age alone available -> available weight normalization
                double cov = 0.40;
                double cRaw = 1.0 * cov * 1.0;
                double conf = Math.min(cap, cRaw);
                return new FreshnessComputationResult(true, aScore, conf, cov, 1, aScore, null,
                        "Freshness based on creation age only (activity unavailable/bot-only)");
            }

            double actScore = calculateActivityScore(idleDays);
            double totalScore = AGE_WEIGHT * aScore + ACTIVITY_WEIGHT * actScore;
            double cov = 1.00;
            double cRaw = 1.0 * cov * 1.0;
            double conf = Math.min(cap, cRaw);
            return new FreshnessComputationResult(true, totalScore, conf, cov, 2, aScore, actScore,
                    "Freshness based on creation age (40%) and recent human activity (60%)");
        }

        public Double getAgeScore() {
            return ageScore;
        }

        public void setAgeScore(Double ageScore) {
            this.ageScore = ageScore;
        }

        public Double getActivityScore() {
            return activityScore;
        }

        public void setActivityScore(Double activityScore) {
            this.activityScore = activityScore;
        }

        public Double getAgeDays() {
            return ageDays;
        }

        public void setAgeDays(Double ageDays) {
            this.ageDays = ageDays;
        }

        public Double getIdleDays() {
            return idleDays;
        }

        public void setIdleDays(Double idleDays) {
            this.idleDays = idleDays;
        }

        public Boolean getAgeAvailable() {
            return ageAvailable;
        }

        public void setAgeAvailable(Boolean ageAvailable) {
            this.ageAvailable = ageAvailable;
        }

        public Boolean getActivityAvailable() {
            return activityAvailable;
        }

        public void setActivityAvailable(Boolean activityAvailable) {
            this.activityAvailable = activityAvailable;
        }

        public Boolean getIsBotActivity() {
            return isBotActivity;
        }

        public void setIsBotActivity(Boolean isBotActivity) {
            this.isBotActivity = isBotActivity;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Double getSourceCap() {
            return sourceCap;
        }

        public void setSourceCap(Double sourceCap) {
            this.sourceCap = sourceCap;
        }
    }

    /**
     * V2.3 Outcome Feedback Evidence: hierarchical Beta(2,2) backoff.
     * Conforms strictly to Section 9 of V2.3 Specification.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OutcomeFeedbackEvidenceV2 {
        public static final double ACCEPTANCE_WEIGHT = 0.40;
        public static final double COMPLETION_WEIGHT = 0.60;
        public static final double PRIOR_P0 = 0.50; // Beta(2,2) prior mean
        public static final double PRIOR_WEIGHT = 4.0; // alpha + beta = 2 + 2 = 4

        @JsonProperty("user_id")
        private String userId;

        @JsonProperty("technology")
        private String technology;

        @JsonProperty("difficulty_band")
        private String difficultyBand;

        @JsonProperty("source")
        private String source = "issue_pilot";

        @JsonProperty("source_cap")
        private Double sourceCap;

        // Hierarchy counts for Acceptance
        @JsonProperty("l1_accept")
        private HierarchyCounts l1Accept; // user_global

        @JsonProperty("l2_accept")
        private HierarchyCounts l2Accept; // user_tech

        @JsonProperty("l3_accept")
        private HierarchyCounts l3Accept; // user_tech_diff

        // Hierarchy counts for Completion
        @JsonProperty("l1_complete")
        private HierarchyCounts l1Complete; // user_global

        @JsonProperty("l2_complete")
        private HierarchyCounts l2Complete; // user_tech

        @JsonProperty("l3_complete")
        private HierarchyCounts l3Complete; // user_tech_diff

        // Computed summary fields for serialization/interop
        @JsonProperty("p_accept")
        private Double pAccept;

        @JsonProperty("p_complete_given_accepted")
        private Double pCompleteGivenAccepted;

        @JsonProperty("hierarchy_level")
        private String hierarchyLevel;

        @JsonProperty("hierarchy_level_accept")
        private String hierarchyLevelAccept;

        @JsonProperty("hierarchy_level_complete")
        private String hierarchyLevelComplete;

        @JsonProperty("cohort_key")
        private String cohortKey;

        @JsonProperty("trials_accept")
        private Integer trialsAccept;

        @JsonProperty("trials_complete")
        private Integer trialsComplete;

        public record HierarchyCounts(
                @JsonProperty("successes") int successes,
                @JsonProperty("trials") int trials) {
            public HierarchyCounts {
                if (successes < 0 || trials < 0 || successes > trials) {
                    throw new IllegalArgumentException(
                            "Invalid trials/successes: successes=" + successes + ", trials=" + trials);
                }
            }
        }

        public record HierarchicalLevelResult(
                String levelName,
                int trials,
                int successes,
                double smoothedP,
                boolean available) {}

        public record FeedbackComputationResult(
                boolean available,
                double score,
                double confidence,
                double coverageAf,
                Double acceptScore,
                Double completeScore,
                String acceptLevel,
                String completeLevel,
                int acceptTrials,
                int completeTrials,
                String explanation) {
            public static FeedbackComputationResult unavailable(double coverageAf, String reason) {
                return new FeedbackComputationResult(false, 0.0, 0.0, coverageAf, null, null, "none", "none", 0, 0, reason);
            }
        }

        public OutcomeFeedbackEvidenceV2() {
        }

        public static HierarchicalLevelResult evaluateHierarchy(HierarchyCounts l3, HierarchyCounts l2, HierarchyCounts l1) {
            if (l1 == null || l1.trials() < 5) {
                return new HierarchicalLevelResult("none", l1 != null ? l1.trials() : 0, l1 != null ? l1.successes() : 0, 0.0, false);
            }

            double p1 = (l1.successes() + PRIOR_WEIGHT * PRIOR_P0) / (l1.trials() + PRIOR_WEIGHT);

            double p2 = p1;
            if (l2 != null && l2.trials() > 0) {
                p2 = (l2.successes() + PRIOR_WEIGHT * p1) / (l2.trials() + PRIOR_WEIGHT);
            }

            double p3 = p2;
            if (l3 != null && l3.trials() > 0) {
                p3 = (l3.successes() + PRIOR_WEIGHT * p2) / (l3.trials() + PRIOR_WEIGHT);
            }

            if (l3 != null && l3.trials() >= 5) {
                return new HierarchicalLevelResult("user_tech_diff", l3.trials(), l3.successes(), p3, true);
            }
            if (l2 != null && l2.trials() >= 5) {
                return new HierarchicalLevelResult("user_tech", l2.trials(), l2.successes(), p2, true);
            }
            return new HierarchicalLevelResult("user_global", l1.trials(), l1.successes(), p1, true);
        }

        public FeedbackComputationResult computeFeedback() {
            return computeFeedback(this.source, this.sourceCap);
        }

        public FeedbackComputationResult computeFeedback(String sourceOverride, Double capOverride) {
            String effSource = sourceOverride != null ? sourceOverride : this.source;
            Double effCap = capOverride != null ? capOverride : this.sourceCap;
            double cap = ResponsivenessEvidenceV2.resolveSourceCap(effSource, effCap);

            HierarchicalLevelResult accRes = evaluateHierarchy(l3Accept, l2Accept, l1Accept);
            HierarchicalLevelResult compRes = evaluateHierarchy(l3Complete, l2Complete, l1Complete);

            if (!accRes.available() && !compRes.available()) {
                return FeedbackComputationResult.unavailable(0.0,
                        "Insufficient outcome history: no level with at least 5 trials for acceptance or completion.");
            }

            if (accRes.available() && compRes.available()) {
                double accScore = 100.0 * accRes.smoothedP();
                double compScore = 100.0 * compRes.smoothedP();
                double fScore = ACCEPTANCE_WEIGHT * accScore + COMPLETION_WEIGHT * compScore;
                double cAcc = (double) accRes.trials() / (accRes.trials() + 10.0);
                double cComp = (double) compRes.trials() / (compRes.trials() + 10.0);
                double rawConf = ACCEPTANCE_WEIGHT * cAcc + COMPLETION_WEIGHT * cComp;
                double conf = Math.min(cap, rawConf);
                return new FeedbackComputationResult(true, fScore, conf, 1.00, accScore, compScore,
                        accRes.levelName(), compRes.levelName(), accRes.trials(), compRes.trials(),
                        "Full outcome feedback: acceptance and completion both available");
            }

            if (accRes.available() && !compRes.available()) {
                double accScore = 100.0 * accRes.smoothedP();
                double fScore = accScore; // available-weight normalized: 0.40 / 0.40 = 1.0
                double cAcc = (double) accRes.trials() / (accRes.trials() + 10.0);
                double conf = Math.min(cap, cAcc);
                return new FeedbackComputationResult(true, fScore, conf, 0.40, accScore, null,
                        accRes.levelName(), "none", accRes.trials(), 0,
                        "Outcome feedback: acceptance only available (completion unavailable)");
            }

            // !accRes.available() && compRes.available()
            double compScore = 100.0 * compRes.smoothedP();
            double fScore = compScore; // available-weight normalized: 0.60 / 0.60 = 1.0
            double cComp = (double) compRes.trials() / (compRes.trials() + 10.0);
            double conf = Math.min(cap, cComp);
            return new FeedbackComputationResult(true, fScore, conf, 0.60, null, compScore,
                    "none", compRes.levelName(), 0, compRes.trials(),
                    "Outcome feedback: completion only available (acceptance unavailable)");
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getTechnology() {
            return technology;
        }

        public void setTechnology(String technology) {
            this.technology = technology;
        }

        public String getDifficultyBand() {
            return difficultyBand;
        }

        public void setDifficultyBand(String difficultyBand) {
            this.difficultyBand = difficultyBand;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public Double getSourceCap() {
            return sourceCap;
        }

        public void setSourceCap(Double sourceCap) {
            this.sourceCap = sourceCap;
        }

        public HierarchyCounts getL1Accept() {
            return l1Accept;
        }

        public void setL1Accept(HierarchyCounts l1Accept) {
            this.l1Accept = l1Accept;
        }

        public HierarchyCounts getL2Accept() {
            return l2Accept;
        }

        public void setL2Accept(HierarchyCounts l2Accept) {
            this.l2Accept = l2Accept;
        }

        public HierarchyCounts getL3Accept() {
            return l3Accept;
        }

        public void setL3Accept(HierarchyCounts l3Accept) {
            this.l3Accept = l3Accept;
        }

        public HierarchyCounts getL1Complete() {
            return l1Complete;
        }

        public void setL1Complete(HierarchyCounts l1Complete) {
            this.l1Complete = l1Complete;
        }

        public HierarchyCounts getL2Complete() {
            return l2Complete;
        }

        public void setL2Complete(HierarchyCounts l2Complete) {
            this.l2Complete = l2Complete;
        }

        public HierarchyCounts getL3Complete() {
            return l3Complete;
        }

        public void setL3Complete(HierarchyCounts l3Complete) {
            this.l3Complete = l3Complete;
        }

        public Double getPAccept() {
            return pAccept;
        }

        public void setPAccept(Double pAccept) {
            this.pAccept = pAccept;
        }

        public Double getPCompleteGivenAccepted() {
            return pCompleteGivenAccepted;
        }

        public void setPCompleteGivenAccepted(Double pCompleteGivenAccepted) {
            this.pCompleteGivenAccepted = pCompleteGivenAccepted;
        }

        public String getHierarchyLevel() {
            return hierarchyLevel;
        }

        public void setHierarchyLevel(String hierarchyLevel) {
            this.hierarchyLevel = hierarchyLevel;
        }

        public String getHierarchyLevelAccept() {
            return hierarchyLevelAccept;
        }

        public void setHierarchyLevelAccept(String hierarchyLevelAccept) {
            this.hierarchyLevelAccept = hierarchyLevelAccept;
        }

        public String getHierarchyLevelComplete() {
            return hierarchyLevelComplete;
        }

        public void setHierarchyLevelComplete(String hierarchyLevelComplete) {
            this.hierarchyLevelComplete = hierarchyLevelComplete;
        }

        public String getCohortKey() {
            return cohortKey;
        }

        public void setCohortKey(String cohortKey) {
            this.cohortKey = cohortKey;
        }

        public Integer getTrialsAccept() {
            return trialsAccept;
        }

        public void setTrialsAccept(Integer trialsAccept) {
            this.trialsAccept = trialsAccept;
        }

        public Integer getTrialsComplete() {
            return trialsComplete;
        }

        public void setTrialsComplete(Integer trialsComplete) {
            this.trialsComplete = trialsComplete;
        }
    }


    /**
     * V2.3 Contributor Skill Evidence per Technology conforming strictly to
     * Sections 5.2 and 5.3.
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

        public ContributorSkillEvidenceV2() {
        }

        public static double sat(double x, double k) {
            if (x <= 0.0)
                return 0.0;
            return 100.0 * (1.0 - Math.exp(-x / k));
        }

        public static double recencyDecay(double months) {
            if (months < 0.0)
                months = 0.0;
            return 100.0 * Math.exp(-months / 12.0);
        }

        public static double recencyHalfLife(double months) {
            if (months < 0.0)
                months = 0.0;
            return 100.0 * Math.pow(0.5, months / 12.0);
        }

        public static double mergeRate(int merged, int decided) {
            return 100.0 * ((double) merged + 2.0) / ((double) decided + 4.0);
        }

        public static Double questionnaireLevelToScore(String level) {
            if (level == null)
                return null;
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
                String explanation) {
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

            if (Boolean.TRUE.equals(mergeRateAvailable) && decidedPrs != null && decidedPrs >= 5
                    && mergedDecidedPrs != null) {
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

            if (Boolean.TRUE.equals(profileCoverageKnown) && Boolean.TRUE.equals(sourceQualityKnown)
                    && sumAvailableWeights >= 0.40) {
                skillObs = weightedSum / sumAvailableWeights;
                observedAvailable = true;

                int prCount = (Boolean.TRUE.equals(prVolumeAvailable) && uniqueAuthoredPrs != null)
                        ? Math.max(0, uniqueAuthoredPrs)
                        : 0;
                int commitCount = (Boolean.TRUE.equals(standaloneCommitsAvailable) && standaloneCommits != null)
                        ? Math.max(0, standaloneCommits)
                        : 0;
                nEff = prCount + commitCount;

                double pCov = profileCoverage != null ? Math.max(0.0, Math.min(1.0, profileCoverage)) : 1.0;
                double sQual = sourceQuality != null ? Math.max(0.0, Math.min(1.0, sourceQuality)) : 1.0;
                cObs = Math.min(1.0, (double) nEff / 10.0) * pCov * sQual;
            }

            // Case 1: Pure questionnaire
            if (!observedAvailable && qScore != null) {
                return new SkillComputationResult(
                        true, qScore, 0.40, "self_reported", sumAvailableWeights, nEff,
                        "Observed evidence below coverage threshold; scored via self-reported questionnaire.");
            }

            // Case 2: Blended observed + questionnaire
            if (observedAvailable && qScore != null) {
                double qConf = 0.40;
                double qWeight = Math.min(0.20, qConf / (cObs + qConf));
                double finalScore = (1.0 - qWeight) * skillObs + qWeight * qScore;
                double finalConf = (1.0 - qWeight) * cObs + qWeight * qConf;
                return new SkillComputationResult(
                        true, finalScore, finalConf, "blended", sumAvailableWeights, nEff,
                        String.format(java.util.Locale.ROOT,
                                "Blended observed (%.2f) and questionnaire (%.2f) with q_weight=%.4f", skillObs, qScore,
                                qWeight));
            }

            // Case 3: Pure observed
            if (observedAvailable) {
                return new SkillComputationResult(
                        true, skillObs, cObs, "observed", sumAvailableWeights, nEff,
                        String.format(java.util.Locale.ROOT,
                                "Observed skill score %.2f with confidence %.4f over %d episodes.", skillObs, cObs,
                                nEff));
            }

            // Case 4: Neither available
            return SkillComputationResult.unavailable(
                    sumAvailableWeights,
                    String.format(java.util.Locale.ROOT,
                            "Nominal coverage A_t = %.2f < 0.40 and no questionnaire report available.",
                            sumAvailableWeights));
        }

        // Getters and setters
        public String getTechnology() {
            return technology;
        }

        public void setTechnology(String technology) {
            this.technology = technology;
        }

        public Integer getUniqueContributedRepositories() {
            return uniqueContributedRepositories;
        }

        public void setUniqueContributedRepositories(Integer uniqueContributedRepositories) {
            this.uniqueContributedRepositories = uniqueContributedRepositories;
        }

        public Boolean getRepoAvailable() {
            return repoAvailable;
        }

        public void setRepoAvailable(Boolean repoAvailable) {
            this.repoAvailable = repoAvailable;
        }

        public Integer getStandaloneCommits() {
            return standaloneCommits;
        }

        public void setStandaloneCommits(Integer standaloneCommits) {
            this.standaloneCommits = standaloneCommits;
        }

        public Boolean getStandaloneCommitsAvailable() {
            return standaloneCommitsAvailable;
        }

        public void setStandaloneCommitsAvailable(Boolean standaloneCommitsAvailable) {
            this.standaloneCommitsAvailable = standaloneCommitsAvailable;
        }

        public Integer getUniqueAuthoredPrs() {
            return uniqueAuthoredPrs;
        }

        public void setUniqueAuthoredPrs(Integer uniqueAuthoredPrs) {
            this.uniqueAuthoredPrs = uniqueAuthoredPrs;
        }

        public Boolean getPrVolumeAvailable() {
            return prVolumeAvailable;
        }

        public void setPrVolumeAvailable(Boolean prVolumeAvailable) {
            this.prVolumeAvailable = prVolumeAvailable;
        }

        public Integer getMergedDecidedPrs() {
            return mergedDecidedPrs;
        }

        public void setMergedDecidedPrs(Integer mergedDecidedPrs) {
            this.mergedDecidedPrs = mergedDecidedPrs;
        }

        public Integer getDecidedPrs() {
            return decidedPrs;
        }

        public void setDecidedPrs(Integer decidedPrs) {
            this.decidedPrs = decidedPrs;
        }

        public Boolean getMergeRateAvailable() {
            return mergeRateAvailable;
        }

        public void setMergeRateAvailable(Boolean mergeRateAvailable) {
            this.mergeRateAvailable = mergeRateAvailable;
        }

        public Double getMonthsSinceLastActivity() {
            return monthsSinceLastActivity;
        }

        public void setMonthsSinceLastActivity(Double monthsSinceLastActivity) {
            this.monthsSinceLastActivity = monthsSinceLastActivity;
        }

        public Boolean getRecencyAvailable() {
            return recencyAvailable;
        }

        public void setRecencyAvailable(Boolean recencyAvailable) {
            this.recencyAvailable = recencyAvailable;
        }

        public Double getProfileCoverage() {
            return profileCoverage;
        }

        public void setProfileCoverage(Double profileCoverage) {
            this.profileCoverage = profileCoverage;
        }

        public Double getSourceQuality() {
            return sourceQuality;
        }

        public void setSourceQuality(Double sourceQuality) {
            this.sourceQuality = sourceQuality;
        }

        public Boolean getProfileCoverageKnown() {
            return profileCoverageKnown;
        }

        public void setProfileCoverageKnown(Boolean profileCoverageKnown) {
            this.profileCoverageKnown = profileCoverageKnown;
        }

        public Boolean getSourceQualityKnown() {
            return sourceQualityKnown;
        }

        public void setSourceQualityKnown(Boolean sourceQualityKnown) {
            this.sourceQualityKnown = sourceQualityKnown;
        }

        public Boolean getHasQuestionnaire() {
            return hasQuestionnaire;
        }

        public void setHasQuestionnaire(Boolean hasQuestionnaire) {
            this.hasQuestionnaire = hasQuestionnaire;
        }

        public String getQuestionnaireLevel() {
            return questionnaireLevel;
        }

        public void setQuestionnaireLevel(String questionnaireLevel) {
            this.questionnaireLevel = questionnaireLevel;
        }

        public Double getQuestionnaireScore() {
            return questionnaireScore;
        }

        public void setQuestionnaireScore(Double questionnaireScore) {
            this.questionnaireScore = questionnaireScore;
        }
    }
}
