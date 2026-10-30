package com.issuepilot.model;

import java.util.Map;

/**
 * DTO representing a recommendation request with candidate selection,
 * filter, budget, and ranking configuration.
 */
public class RecommendationRequest {
    public String user_id;
    public Map<String, Object> profile;
    public Map<String, Object> weights;
    public int target_count = 5;
    public Integer shortlist_size;
    public int verification_budget = 30;
    public boolean prefer_live_atlas = true;
    public boolean demo_mode = false;
    public String technology_filter;
    public String source_filter;
    public Double min_difficulty;
    public Double max_difficulty;
    public String run_id;
    public Integer timeout_seconds;
    public Integer max_retries;
}

