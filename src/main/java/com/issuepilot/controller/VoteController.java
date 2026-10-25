package com.issuepilot.controller;

import com.issuepilot.model.IssueVote;
import com.issuepilot.service.VoteAffinityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/votes")
public class VoteController {

    private final VoteAffinityService voteAffinityService;

    public VoteController(VoteAffinityService voteAffinityService) {
        this.voteAffinityService = voteAffinityService;
    }

    public static class VoteRequest {
        public String user_id;
        public String source;
        public String platform_issue_id;
        public int vote; // +1 or -1
        public String recommendation_run_id;
        public Map<String, Object> feature_snapshot;
    }

    public static class AffinityEvaluationRequest {
        public String user_id;
        public List<Map<String, Object>> candidates;
    }

    @PostMapping
    public Map<String, Object> recordVote(@RequestBody VoteRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }
        if (req.user_id == null || req.user_id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'user_id' is required.");
        }
        if (req.source == null || req.source.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'source' is required.");
        }
        if (req.platform_issue_id == null || req.platform_issue_id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'platform_issue_id' is required.");
        }
        if (req.vote != 1 && req.vote != -1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'vote' must be +1 (like) or -1 (dislike).");
        }

        try {
            IssueVote saved = voteAffinityService.recordVote(
                    req.user_id,
                    req.source,
                    req.platform_issue_id,
                    req.vote,
                    req.recommendation_run_id,
                    req.feature_snapshot
            );

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("status", "success");
            res.put("message", (req.vote == 1 ? "Liked" : "Disliked") + " issue '" + req.platform_issue_id + "' on " + req.source);
            res.put("vote", saved);
            return res;
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, iae.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to record vote: " + e.getMessage());
        }
    }

    @DeleteMapping
    public Map<String, Object> removeVote(
            @RequestParam(name = "user_id") String userId,
            @RequestParam String source,
            @RequestParam(name = "platform_issue_id") String platformIssueId,
            @RequestHeader(name = "X-User-Id", required = false) String requestingUserId
    ) {
        if (requestingUserId != null && !requestingUserId.isBlank() && !requestingUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot remove another user's votes.");
        }

        boolean removed = voteAffinityService.removeVote(userId, source, platformIssueId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", removed ? "success" : "not_found");
        res.put("removed", removed);
        res.put("message", removed ? "Vote removed successfully." : "No vote found for specified issue.");
        return res;
    }

    @GetMapping("/{userId}")
    public Map<String, Object> getUserVotes(
            @PathVariable String userId,
            @RequestHeader(name = "X-User-Id", required = false) String requestingUserId
    ) {
        try {
            List<IssueVote> votes = voteAffinityService.getUserVotes(requestingUserId, userId);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("user_id", userId);
            res.put("total_votes", votes.size());
            res.put("votes", votes);
            return res;
        } catch (SecurityException se) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, se.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/summary/{userId}")
    public Map<String, Object> getVoteSummary(@PathVariable String userId) {
        Map<String, Integer> counts = voteAffinityService.countUserVotes(userId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("user_id", userId);
        res.put("counts", counts);
        return res;
    }

    @PostMapping("/affinity")
    public Map<String, Object> evaluateAffinity(@RequestBody AffinityEvaluationRequest req) {
        if (req == null || req.user_id == null || req.user_id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'user_id' is required.");
        }
        return voteAffinityService.evaluateCandidateAffinity(req.user_id, req.candidates);
    }
}
