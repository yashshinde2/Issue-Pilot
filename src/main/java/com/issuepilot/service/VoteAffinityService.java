package com.issuepilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.IssueRecord;
import com.issuepilot.model.IssueVote;
import com.issuepilot.model.TechnologyRelevance;
import com.issuepilot.repository.IssueRepository;
import com.issuepilot.repository.VoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class VoteAffinityService {

    private static final Logger logger = LoggerFactory.getLogger(VoteAffinityService.class);

    private final VoteRepository voteRepository;
    private final IssueRepository issueRepository;
    private final CppRankingClient cppRankingClient;
    private final ObjectMapper objectMapper;

    public VoteAffinityService(
            VoteRepository voteRepository,
            IssueRepository issueRepository,
            CppRankingClient cppRankingClient,
            ObjectMapper objectMapper) {
        this.voteRepository = voteRepository;
        this.issueRepository = issueRepository;
        this.cppRankingClient = cppRankingClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> extractFeatureSnapshot(Map<String, Object> issueMap) {
        if (issueMap == null) return new LinkedHashMap<>();
        IssueRecord record = objectMapper.convertValue(issueMap, IssueRecord.class);
        return extractFeatureSnapshot(record);
    }

    public Map<String, Object> extractFeatureSnapshot(IssueRecord issue) {
        Map<String, Object> snap = new LinkedHashMap<>();
        if (issue == null)
            return snap;

        snap.put("source", issue.getSource());
        snap.put("platform_issue_id",
                issue.getSourceIssueId() != null ? issue.getSourceIssueId() : String.valueOf(issue.getIssueNumber()));
        snap.put("repository_full_name", issue.getRepositoryFullName());

        // Technologies
        List<String> techs = new ArrayList<>();
        if (issue.getTechnologies() != null) {
            for (TechnologyRelevance tr : issue.getTechnologies()) {
                if (tr != null && tr.getTechnology() != null && !tr.getTechnology().isBlank()) {
                    techs.add(tr.getTechnology());
                }
            }
        }
        if (techs.isEmpty() && issue.getDetectedTechnologies() != null) {
            techs.addAll(issue.getDetectedTechnologies());
        }
        snap.put("technologies", techs);

        // Labels
        snap.put("labels", issue.getLabels() != null ? issue.getLabels() : Collections.emptyList());

        // Issue type
        String issueType = issue.getDiscoveryCategory();
        if (issueType == null || issueType.isBlank()) {
            if (issue.getLabels() != null) {
                for (String l : issue.getLabels()) {
                    String lower = l.toLowerCase(Locale.ROOT);
                    if (lower.contains("bug") || lower.contains("defect")) {
                        issueType = "bug";
                        break;
                    } else if (lower.contains("enhancement") || lower.contains("feature")) {
                        issueType = "enhancement";
                        break;
                    } else if (lower.contains("doc")) {
                        issueType = "documentation";
                        break;
                    }
                }
            }
        }
        snap.put("issue_type", issueType != null ? issueType : "task");

        // Difficulty
        if (issue.getDifficultyEvidence() != null) {
            var diff = issue.getDifficultyEvidence();
            double dScore = 0.25 * diff.getScopeChangeSize() +
                    0.25 * diff.getTechnicalComplexity() +
                    0.25 * diff.getHistoricalEffort() +
                    0.15 * diff.getDependencyArchImpact() +
                    0.10 * diff.getCoordinationComplexity();
            snap.put("difficulty", dScore);
            snap.put("difficulty_available", true);
        } else {
            snap.put("difficulty", -1.0);
            snap.put("difficulty_available", false);
        }

        // Structured metadata
        List<String> meta = new ArrayList<>();
        if (issue.getRepositoryLanguage() != null && !issue.getRepositoryLanguage().isBlank()) {
            meta.add(issue.getRepositoryLanguage());
        }
        if (issue.getProduct() != null && !issue.getProduct().isBlank()) {
            meta.add(issue.getProduct());
        }
        if (issue.getComponent() != null && !issue.getComponent().isBlank()) {
            meta.add(issue.getComponent());
        }
        if (issue.getDiscoveryCategory() != null && !issue.getDiscoveryCategory().isBlank()) {
            meta.add(issue.getDiscoveryCategory());
        }
        snap.put("structured_metadata", meta);

        return snap;
    }

    public IssueVote recordVote(
            String userId,
            String source,
            String platformIssueId,
            int voteValue,
            String recommendationRunId,
            Map<String, Object> featureSnapshot) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID cannot be empty.");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Source platform cannot be empty.");
        }
        if (platformIssueId == null || platformIssueId.isBlank()) {
            throw new IllegalArgumentException("Platform issue ID cannot be empty.");
        }
        if (voteValue != 1 && voteValue != -1) {
            throw new IllegalArgumentException("Vote must be +1 (like) or -1 (dislike).");
        }

        IssueVote vote = new IssueVote(userId, source, platformIssueId, voteValue);
        vote.setRecommendationRunId(recommendationRunId);

        // If snapshot not supplied, try resolving from database
        if (featureSnapshot != null && !featureSnapshot.isEmpty()) {
            vote.setFeatureSnapshot(featureSnapshot);
        } else {
            try {
                // Try looking up candidate by id
                String compositeId = source + "_" + platformIssueId;
                Map<String, Object> recordMap = issueRepository.getIssueById(compositeId);
                if (recordMap != null) {
                    IssueRecord record = objectMapper.convertValue(recordMap, IssueRecord.class);
                    if (record != null) {
                        vote.setFeatureSnapshot(extractFeatureSnapshot(record));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return voteRepository.recordVote(vote);
    }

    public boolean removeVote(String userId, String source, String platformIssueId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID cannot be empty.");
        }
        return voteRepository.removeVote(userId, source, platformIssueId);
    }

    public List<IssueVote> getUserVotes(String requestingUserId, String targetUserId) {
        // Enforce user isolation & authorization: requesting user must match target
        // user
        if (requestingUserId != null && !requestingUserId.isBlank() && !requestingUserId.equals(targetUserId)) {
            throw new SecurityException("Access denied: You cannot view or modify another user's votes.");
        }
        return voteRepository.getUserVotes(targetUserId);
    }

    public Map<String, Integer> countUserVotes(String userId) {
        return voteRepository.countUserVotes(userId);
    }

    public Map<String, Object> evaluateCandidateAffinity(String userId, List<Map<String, Object>> candidates) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user_id", userId);
        response.put("total_candidates", candidates != null ? candidates.size() : 0);

        List<IssueVote> votes = voteRepository.getUserVotes(userId);
        response.put("total_votes_considered", votes.size());

        if (candidates == null || candidates.isEmpty()) {
            response.put("results", Collections.emptyList());
            return response;
        }

        if (votes.isEmpty()) {
            // No votes: generate baseline zero-affinity response deterministically
            List<Map<String, Object>> emptyResults = new ArrayList<>();
            for (Map<String, Object> c : candidates) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", c.get("id"));
                item.put("source", c.get("source"));
                item.put("platform_issue_id", c.get("platform_issue_id"));
                item.put("affinity_score", 0.0);
                item.put("confidence", 0.0);
                item.put("positive_evidence", 0.0);
                item.put("negative_evidence", 0.0);
                item.put("conflict_indicator", false);
                item.put("conflict_ratio", 0.0);
                item.put("matched_feature_groups", Collections.emptyList());
                item.put("explanations", List.of("No vote history available for user."));
                emptyResults.add(item);
            }
            response.put("results", emptyResults);
            response.put("algorithm_version", "2.0.0-affinity");
            return response;
        }

        // Prepare payload for authoritative C++ engine
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("candidates", candidates);
        payload.put("votes", votes);

        try {
            Map<String, Object> engineRes = cppRankingClient.executeAffinity(payload);
            response.put("algorithm_version", engineRes.getOrDefault("algorithm_version", "2.0.0-affinity"));
            response.put("weights", engineRes.get("weights"));
            response.put("results", engineRes.get("results"));
            return response;
        } catch (Exception e) {
            logger.error("C++ vote-affinity engine evaluation failed: {}", e.getMessage(), e);
            // Safe fallback: report failure diagnostics without crashing caller
            response.put("error", "Affinity calculation failed: " + e.getMessage());
            response.put("fallback", true);
            List<Map<String, Object>> fallbackResults = new ArrayList<>();
            for (Map<String, Object> c : candidates) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", c.get("id"));
                item.put("affinity_score", 0.0);
                item.put("confidence", 0.0);
                item.put("explanations", List.of("Affinity calculation temporarily unavailable."));
                fallbackResults.add(item);
            }
            response.put("results", fallbackResults);
            return response;
        }
    }
}
