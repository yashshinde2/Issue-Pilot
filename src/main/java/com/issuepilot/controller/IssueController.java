package com.issuepilot.controller;

import com.issuepilot.config.MongoConfig;
import com.issuepilot.repository.IssueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final IssueRepository issueRepository;
    private final MongoConfig mongoConfig;

    public IssueController(IssueRepository issueRepository, MongoConfig mongoConfig) {
        this.issueRepository = issueRepository;
        this.mongoConfig = mongoConfig;
    }

    private void ensureConnected() {
        if (!mongoConfig.isConnected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MongoDB Atlas connection is unavailable.");
        }
    }

    @PostMapping
    public Map<String, Object> upsertIssueRecord(@RequestBody Map<String, Object> payload) {
        ensureConnected();
        try {
            return issueRepository.upsertIssue(payload, true);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error upserting issue: " + e.getMessage());
        }
    }

    @PostMapping("/batch-upsert")
    public Map<String, Object> batchUpsertIssues(@RequestBody List<Map<String, Object>> issues) {
        ensureConnected();
        int upserted = 0;
        for (Map<String, Object> issue : issues) {
            try {
                issueRepository.upsertIssue(issue, true);
                upserted++;
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "success");
        res.put("total_submitted", issues.size());
        res.put("total_upserted", upserted);
        return res;
    }

    @GetMapping
    public Map<String, Object> queryIssues(
            @RequestParam(defaultValue = "open") String state,
            @RequestParam(required = false, name = "is_assigned") Boolean isAssigned,
            @RequestParam(required = false) String technology,
            @RequestParam(required = false, name = "min_difficulty") Double minDifficulty,
            @RequestParam(required = false, name = "max_difficulty") Double maxDifficulty,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int skip
    ) {
        ensureConnected();
        List<Map<String, Object>> list = issueRepository.queryIssues(
                state, isAssigned, technology, minDifficulty, maxDifficulty, search, source, limit, skip
        );
        long totalInPool = issueRepository.countIssues();

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("state", state);
        filters.put("is_assigned", isAssigned);
        filters.put("technology", technology);
        filters.put("min_difficulty", minDifficulty);
        filters.put("max_difficulty", maxDifficulty);
        filters.put("search", search);
        filters.put("source", source);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", list.size());
        response.put("total_in_pool", totalInPool);
        response.put("filters", filters);
        response.put("issues", list);

        return response;
    }

    @GetMapping("/{issueId}")
    public Map<String, Object> getIssueById(@PathVariable String issueId) {
        ensureConnected();
        Map<String, Object> issue = issueRepository.getIssueById(issueId);
        if (issue == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue '" + issueId + "' not found.");
        }
        return issue;
    }

    @DeleteMapping("/{issueId}")
    public Map<String, Object> deleteIssueById(@PathVariable String issueId) {
        ensureConnected();
        boolean deleted = issueRepository.deleteIssue(issueId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue '" + issueId + "' not found.");
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "success");
        res.put("message", "Issue '" + issueId + "' deleted.");
        return res;
    }
}
