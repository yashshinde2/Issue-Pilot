package com.issuepilot.adapter;

import java.util.Map;

public interface BaseIssueSourceAdapter {

    String getSourceName();

    default String getSourceInstance() {
        return getSourceName();
    }

    Map<String, Object> checkHealth();

    Map<String, Object> searchIssues(String query, int page, int perPage, String sort, String order);

    Map<String, Object> getIssue(String owner, String repo, int issueNumber);

    Map<String, Object> getRateLimit();

    Map<String, Object> getRateLimitState();

    Map<String, Object> normalizeIssue(Map<String, Object> rawItem, Map<String, Object> queryInfo);
}
