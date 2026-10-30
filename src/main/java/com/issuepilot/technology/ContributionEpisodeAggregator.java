package com.issuepilot.technology;

import com.issuepilot.model.EvidenceModelsV2.ContributorSkillEvidenceV2;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Deduplicates and aggregates verified human contribution episodes across repositories,
 * commits, and pull requests conforming strictly to Issue Pilot V2.3 Section 5.2.
 *
 * Rules:
 * - A commit linked to a PR/MR is not counted again as a standalone commit.
 * - Exclude forks and bots.
 * - Deduplicate repositories and PR/MR IDs across API pages.
 * - Distinct episode count: n_eff = unique_authored_prs + standalone_commits.
 * - Saturation parameters: k_repo=4, k_commit=80, k_pr=8, k_merge_rate_threshold=5, recency=12 months.
 */
public final class ContributionEpisodeAggregator {

    public record ContributionPr(
            String prId,
            String repositoryKey,
            String technology,
            boolean isMerged,
            boolean isClosed,
            boolean isBot,
            boolean isFork,
            Instant activityAt
    ) {}

    public record ContributionCommit(
            String commitSha,
            String repositoryKey,
            String technology,
            String linkedPrId,
            boolean isBot,
            boolean isFork,
            Instant committedAt
    ) {}

    public record QuestionnaireReport(
            String technology,
            String level, // beginner, basic, intermediate, advanced, expert
            Double explicitScore
    ) {}

    public static Map<String, ContributorSkillEvidenceV2> aggregate(
            List<ContributionPr> prs,
            List<ContributionCommit> commits,
            List<QuestionnaireReport> questionnaires,
            Instant snapshotTime,
            double profileCoverage,
            double sourceQuality,
            boolean profileCoverageKnown,
            boolean sourceQualityKnown
    ) {
        Instant refTime = snapshotTime != null ? snapshotTime : Instant.now();

        // 1. Deduplicate PRs across API pages
        // Key: canonicalRepoKey + "|" + prId
        Map<String, ContributionPr> deduplicatedPrs = new LinkedHashMap<>();
        Set<String> knownPrIds = new HashSet<>();

        if (prs != null) {
            for (ContributionPr pr : prs) {
                if (pr == null || pr.isBot() || pr.isFork()) continue;
                if (pr.prId() == null || pr.prId().isBlank()) continue;
                String repo = pr.repositoryKey() != null ? pr.repositoryKey().trim().toLowerCase(Locale.ROOT) : "";
                String key = repo + "|" + pr.prId().trim();
                if (!deduplicatedPrs.containsKey(key)) {
                    deduplicatedPrs.put(key, pr);
                    knownPrIds.add(pr.prId().trim());
                    knownPrIds.add(key);
                }
            }
        }

        // 2. Deduplicate Commits and separate standalone commits from PR-linked commits
        // Key: canonicalRepoKey + "|" + commitSha
        Map<String, ContributionCommit> deduplicatedCommits = new LinkedHashMap<>();
        if (commits != null) {
            for (ContributionCommit c : commits) {
                if (c == null || c.isBot() || c.isFork()) continue;
                if (c.commitSha() == null || c.commitSha().isBlank()) continue;
                String repo = c.repositoryKey() != null ? c.repositoryKey().trim().toLowerCase(Locale.ROOT) : "";
                String key = repo + "|" + c.commitSha().trim();
                deduplicatedCommits.putIfAbsent(key, c);
            }
        }

        // Separate standalone commits: commit not linked to any PR
        List<ContributionCommit> standaloneCommits = new ArrayList<>();
        for (ContributionCommit c : deduplicatedCommits.values()) {
            boolean isLinked = false;
            if (c.linkedPrId() != null && !c.linkedPrId().isBlank()) {
                String lId = c.linkedPrId().trim();
                String repo = c.repositoryKey() != null ? c.repositoryKey().trim().toLowerCase(Locale.ROOT) : "";
                if (knownPrIds.contains(lId) || knownPrIds.contains(repo + "|" + lId)) {
                    isLinked = true;
                } else {
                    // Even if PR wasn't in list, non-empty linkedPrId means it was part of a PR
                    isLinked = true;
                }
            }
            if (!isLinked) {
                standaloneCommits.add(c);
            }
        }

        // 3. Group by canonical technology
        Set<String> allTechnologies = new TreeSet<>();
        Map<String, List<ContributionPr>> techPrs = new HashMap<>();
        Map<String, List<ContributionCommit>> techCommits = new HashMap<>();
        Map<String, Set<String>> techRepos = new HashMap<>();
        Map<String, Instant> techLatestActivity = new HashMap<>();

        for (ContributionPr pr : deduplicatedPrs.values()) {
            String canon = TechnologyRegistry.resolveCanonicalName(pr.technology());
            if (canon == null) canon = pr.technology();
            if (canon == null || canon.isBlank()) continue;

            allTechnologies.add(canon);
            techPrs.computeIfAbsent(canon, k -> new ArrayList<>()).add(pr);

            if (pr.repositoryKey() != null && !pr.repositoryKey().isBlank()) {
                techRepos.computeIfAbsent(canon, k -> new HashSet<>()).add(pr.repositoryKey().trim().toLowerCase(Locale.ROOT));
            }
            if (pr.activityAt() != null) {
                Instant prev = techLatestActivity.get(canon);
                if (prev == null || pr.activityAt().isAfter(prev)) {
                    techLatestActivity.put(canon, pr.activityAt());
                }
            }
        }

        for (ContributionCommit c : standaloneCommits) {
            String canon = TechnologyRegistry.resolveCanonicalName(c.technology());
            if (canon == null) canon = c.technology();
            if (canon == null || canon.isBlank()) continue;

            allTechnologies.add(canon);
            techCommits.computeIfAbsent(canon, k -> new ArrayList<>()).add(c);

            if (c.repositoryKey() != null && !c.repositoryKey().isBlank()) {
                techRepos.computeIfAbsent(canon, k -> new HashSet<>()).add(c.repositoryKey().trim().toLowerCase(Locale.ROOT));
            }
            if (c.committedAt() != null) {
                Instant prev = techLatestActivity.get(canon);
                if (prev == null || c.committedAt().isAfter(prev)) {
                    techLatestActivity.put(canon, c.committedAt());
                }
            }
        }

        // Also add technologies from questionnaires
        Map<String, QuestionnaireReport> questionnaireMap = new HashMap<>();
        if (questionnaires != null) {
            for (QuestionnaireReport q : questionnaires) {
                if (q == null || q.technology() == null) continue;
                String canon = TechnologyRegistry.resolveCanonicalName(q.technology());
                if (canon == null) canon = q.technology();
                allTechnologies.add(canon);
                questionnaireMap.put(canon, q);
            }
        }

        // 4. Build ContributorSkillEvidenceV2 for each technology
        Map<String, ContributorSkillEvidenceV2> result = new LinkedHashMap<>();

        for (String tech : allTechnologies) {
            ContributorSkillEvidenceV2 ev = new ContributorSkillEvidenceV2();
            ev.setTechnology(tech);

            List<ContributionPr> prList = techPrs.getOrDefault(tech, Collections.emptyList());
            List<ContributionCommit> commitList = techCommits.getOrDefault(tech, Collections.emptyList());
            Set<String> repos = techRepos.getOrDefault(tech, Collections.emptySet());
            Instant latestAct = techLatestActivity.get(tech);

            // Repos
            ev.setUniqueContributedRepositories(repos.size());
            ev.setRepoAvailable(true);

            // Standalone Commits
            ev.setStandaloneCommits(commitList.size());
            ev.setStandaloneCommitsAvailable(true);

            // PR volume
            ev.setUniqueAuthoredPrs(prList.size());
            ev.setPrVolumeAvailable(true);

            // Merge rate: decided PRs
            int decided = 0;
            int merged = 0;
            for (ContributionPr pr : prList) {
                if (pr.isMerged() || pr.isClosed()) {
                    decided++;
                    if (pr.isMerged()) {
                        merged++;
                    }
                }
            }
            ev.setDecidedPrs(decided);
            ev.setMergedDecidedPrs(merged);
            ev.setMergeRateAvailable(decided >= 5);

            // Recency
            if (latestAct != null) {
                long seconds = Duration.between(latestAct, refTime).getSeconds();
                if (seconds < 0) seconds = 0; // clamp clock skew
                double months = (double) seconds / (30.4375 * 86400.0);
                ev.setMonthsSinceLastActivity(months);
                ev.setRecencyAvailable(true);
            } else {
                ev.setRecencyAvailable(false);
            }

            // Quality metadata
            ev.setProfileCoverage(profileCoverage);
            ev.setSourceQuality(sourceQuality);
            ev.setProfileCoverageKnown(profileCoverageKnown);
            ev.setSourceQualityKnown(sourceQualityKnown);

            // Questionnaire
            QuestionnaireReport q = questionnaireMap.get(tech);
            if (q != null) {
                ev.setHasQuestionnaire(true);
                ev.setQuestionnaireLevel(q.level());
                if (q.explicitScore() != null) {
                    ev.setQuestionnaireScore(q.explicitScore());
                } else if (q.level() != null) {
                    ev.setQuestionnaireScore(ContributorSkillEvidenceV2.questionnaireLevelToScore(q.level()));
                }
            }

            result.put(tech, ev);
        }

        return result;
    }
}

