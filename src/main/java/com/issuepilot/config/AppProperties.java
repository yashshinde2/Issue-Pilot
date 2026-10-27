package com.issuepilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Global configuration properties matching the Python Settings class in backend/app/config.py.
 */
@Component
public class AppProperties {

    @Value("${MONGODB_URI:mongodb://localhost:27017}")
    private String mongodbUri;

    @Value("${STAGING_MONGODB_URI:}")
    private String stagingMongodbUri;

    @Value("${EXPECTED_STAGING_HOST:}")
    private String expectedStagingHost;

    @Value("${MONGODB_DATABASE:issue_pilot}")
    private String mongodbDatabase;

    @Value("${MONGODB_MAX_POOL_SIZE:50}")
    private int mongodbMaxPoolSize;

    @Value("${MONGODB_MIN_POOL_SIZE:5}")
    private int mongodbMinPoolSize;

    @Value("${MONGODB_SERVER_SELECTION_TIMEOUT_MS:15000}")
    private int mongodbServerSelectionTimeoutMs;

    @Value("${MONGODB_CONNECT_TIMEOUT_MS:10000}")
    private int mongodbConnectTimeoutMs;

    @Value("${MONGODB_SOCKET_TIMEOUT_MS:10000}")
    private int mongodbSocketTimeoutMs;

    @Value("${MONGODB_MAX_IDLE_TIME_MS:45000}")
    private int mongodbMaxIdleTimeMs;

    @Value("${GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${GITHUB_API_BASE_URL:https://api.github.com}")
    private String githubApiBaseUrl;

    @Value("${INGESTION_ENABLED:true}")
    private boolean ingestionEnabled = true;

    @Value("${CANDIDATE_POOL_TARGET:3000}")
    private int candidatePoolTarget = 3000;

    @Value("${RECENT_WINDOW_DAYS:30}")
    private int recentWindowDays = 30;

    @Value("${OLDER_WINDOW_DAYS:90}")
    private int olderWindowDays = 90;

    @Value("${DISCOVERY_CADENCE_HOURS:24}")
    private int discoveryCadenceHours = 24;

    @Value("${REFRESH_CADENCE_DAYS:3}")
    private int refreshCadenceDays = 3;

    @Value("${MAX_ISSUES_PER_REPO:15}")
    private int maxIssuesPerRepo = 15;

    @Value("${MAX_SEARCH_PAGES_PER_RUN:5}")
    private int maxSearchPagesPerRun = 5;

    @Value("${PER_PAGE_COUNT:30}")
    private int perPageCount = 30;

    @Value("${GITHUB_RATE_LIMIT_BUFFER:10}")
    private int githubRateLimitBuffer = 10;

    @Value("${GITHUB_REQUEST_TIMEOUT_SECONDS:15}")
    private int githubRequestTimeoutSeconds = 15;

    @Value("${GITHUB_MAX_RETRIES:3}")
    private int githubMaxRetries = 3;

    @Value("${GITLAB_TOKEN:}")
    private String gitlabToken = "";

    @Value("${GITLAB_API_BASE_URL:https://gitlab.com/api/v4}")
    private String gitlabApiBaseUrl = "https://gitlab.com/api/v4";

    @Value("${GITLAB_RATE_LIMIT_BUFFER:20}")
    private int gitlabRateLimitBuffer = 20;

    @Value("${GITLAB_REQUEST_TIMEOUT_SECONDS:15}")
    private int gitlabRequestTimeoutSeconds = 15;

    @Value("${GITLAB_MAX_RETRIES:3}")
    private int gitlabMaxRetries = 3;

    @Value("${CODEBERG_TOKEN:}")
    private String codebergToken = "";

    @Value("${CODEBERG_API_BASE_URL:https://codeberg.org/api/v1}")
    private String codebergApiBaseUrl = "https://codeberg.org/api/v1";

    @Value("${CODEBERG_RATE_LIMIT_BUFFER:20}")
    private int codebergRateLimitBuffer = 20;

    @Value("${CODEBERG_REQUEST_TIMEOUT_SECONDS:15}")
    private int codebergRequestTimeoutSeconds = 15;

    @Value("${CODEBERG_MAX_RETRIES:3}")
    private int codebergMaxRetries = 3;

    @Value("${BUGZILLA_BASE_URL:}")
    private String bugzillaBaseUrl = "";

    @Value("${BUGZILLA_API_KEY:}")
    private String bugzillaApiKey = "";

    @Value("${BUGZILLA_LOCAL_REQUEST_BUDGET:100}")
    private int bugzillaLocalRequestBudget = 100;

    @Value("${BUGZILLA_REQUEST_TIMEOUT_SECONDS:10}")
    private int bugzillaRequestTimeoutSeconds = 10;

    @Value("${BUGZILLA_MAX_RETRIES:2}")
    private int bugzillaMaxRetries = 2;

    @Value("${SOURCE_ALLOCATION_GITHUB:0.40}")
    private double sourceAllocationGithub;

    @Value("${SOURCE_ALLOCATION_GITLAB:0.30}")
    private double sourceAllocationGitlab;

    @Value("${SOURCE_ALLOCATION_CODEBERG:0.15}")
    private double sourceAllocationCodeberg;

    @Value("${SOURCE_ALLOCATION_BUGZILLA:0.15}")
    private double sourceAllocationBugzilla;

    @Value("${SCHEDULER_ENABLED:false}")
    private boolean schedulerEnabled;

    @Value("${SCHEDULER_INTERVAL_MINUTES:60}")
    private int schedulerIntervalMinutes;

    @Value("${REDIS_URL:}")
    private String redisUrl;

    @Value("${FINAL_RECOMMENDATION_COUNT:5}")
    private int finalRecommendationCount;

    @Value("${VERIFICATION_CANDIDATE_LIMIT:30}")
    private int verificationCandidateLimit;

    @Value("${VERIFICATION_TIMEOUT_SECONDS:5}")
    private int verificationTimeoutSeconds;

    @Value("${VERIFICATION_MAX_RETRIES:2}")
    private int verificationMaxRetries;

    @Value("${VERIFICATION_CACHE_TTL_SECONDS:300}")
    private int verificationCacheTtlSeconds;

    @Value("${VERIFICATION_CONCURRENCY:5}")
    private int verificationConcurrency;

    @Value("${CPP_ENGINE_PATH:ranking_engine/build/issue_pilot_ranking_engine.exe}")
    private String cppEnginePath;

    @Value("${CORS_ORIGINS:*}")
    private String corsOrigins;

    public String getMongodbUri() {
        return mongodbUri;
    }

    public String getStagingMongodbUri() {
        return stagingMongodbUri;
    }

    public String getExpectedStagingHost() {
        return expectedStagingHost;
    }

    public String getSanitizedMongodbUri() {
        String uri = (stagingMongodbUri != null && !stagingMongodbUri.isBlank()) ? stagingMongodbUri : mongodbUri;
        return EnvLoader.maskUri(uri);
    }

    public String getMongodbDatabase() {
        return mongodbDatabase;
    }

    public int getMongodbMaxPoolSize() {
        return mongodbMaxPoolSize;
    }

    public int getMongodbMinPoolSize() {
        return mongodbMinPoolSize;
    }

    public int getMongodbServerSelectionTimeoutMs() {
        return mongodbServerSelectionTimeoutMs;
    }

    public int getMongodbConnectTimeoutMs() {
        return mongodbConnectTimeoutMs;
    }

    public int getMongodbSocketTimeoutMs() {
        return mongodbSocketTimeoutMs;
    }

    public int getMongodbMaxIdleTimeMs() {
        return mongodbMaxIdleTimeMs;
    }

    public String getGithubToken() {
        return githubToken;
    }

    public String getGithubApiBaseUrl() {
        return (githubApiBaseUrl != null && !githubApiBaseUrl.isBlank()) ? githubApiBaseUrl : "https://api.github.com";
    }

    public boolean isIngestionEnabled() {
        return ingestionEnabled;
    }

    public int getCandidatePoolTarget() {
        return candidatePoolTarget;
    }

    public int getRecentWindowDays() {
        return recentWindowDays;
    }

    public int getOlderWindowDays() {
        return olderWindowDays;
    }

    public int getDiscoveryCadenceHours() {
        return discoveryCadenceHours;
    }

    public int getRefreshCadenceDays() {
        return refreshCadenceDays;
    }

    public int getMaxIssuesPerRepo() {
        return maxIssuesPerRepo;
    }

    public int getMaxSearchPagesPerRun() {
        return maxSearchPagesPerRun;
    }

    public int getPerPageCount() {
        return perPageCount;
    }

    public int getGithubRateLimitBuffer() {
        return githubRateLimitBuffer;
    }

    public int getGithubRequestTimeoutSeconds() {
        return githubRequestTimeoutSeconds;
    }

    public int getGithubMaxRetries() {
        return githubMaxRetries;
    }

    public String getGitlabToken() {
        return gitlabToken;
    }

    public String getGitlabApiBaseUrl() {
        return (gitlabApiBaseUrl != null && !gitlabApiBaseUrl.isBlank()) ? gitlabApiBaseUrl : "https://gitlab.com/api/v4";
    }

    public int getGitlabRateLimitBuffer() {
        return gitlabRateLimitBuffer;
    }

    public int getGitlabRequestTimeoutSeconds() {
        return gitlabRequestTimeoutSeconds;
    }

    public int getGitlabMaxRetries() {
        return gitlabMaxRetries;
    }

    public String getCodebergToken() {
        return codebergToken;
    }

    public String getCodebergApiBaseUrl() {
        return (codebergApiBaseUrl != null && !codebergApiBaseUrl.isBlank()) ? codebergApiBaseUrl : "https://codeberg.org/api/v1";
    }

    public int getCodebergRateLimitBuffer() {
        return codebergRateLimitBuffer;
    }

    public int getCodebergRequestTimeoutSeconds() {
        return codebergRequestTimeoutSeconds;
    }

    public int getCodebergMaxRetries() {
        return codebergMaxRetries;
    }

    public String getBugzillaBaseUrl() {
        return bugzillaBaseUrl != null ? bugzillaBaseUrl : "";
    }

    public String getBugzillaApiKey() {
        return bugzillaApiKey;
    }

    public int getBugzillaLocalRequestBudget() {
        return bugzillaLocalRequestBudget;
    }

    public int getBugzillaRequestTimeoutSeconds() {
        return bugzillaRequestTimeoutSeconds;
    }

    public int getBugzillaMaxRetries() {
        return bugzillaMaxRetries;
    }

    public double getSourceAllocationGithub() {
        return sourceAllocationGithub;
    }

    public double getSourceAllocationGitlab() {
        return sourceAllocationGitlab;
    }

    public double getSourceAllocationCodeberg() {
        return sourceAllocationCodeberg;
    }

    public double getSourceAllocationBugzilla() {
        return sourceAllocationBugzilla;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public String getRedisUrl() {
        return redisUrl;
    }

    public int getFinalRecommendationCount() {
        return finalRecommendationCount;
    }

    public int getVerificationCandidateLimit() {
        return verificationCandidateLimit;
    }

    public int getVerificationTimeoutSeconds() {
        return verificationTimeoutSeconds;
    }

    public int getVerificationMaxRetries() {
        return verificationMaxRetries;
    }

    public int getVerificationCacheTtlSeconds() {
        return verificationCacheTtlSeconds;
    }

    public int getVerificationConcurrency() {
        return verificationConcurrency;
    }

    public int getSchedulerIntervalMinutes() {
        return schedulerIntervalMinutes;
    }

    public String getCppEnginePath() {
        return cppEnginePath;
    }

    public String getCppRankingEnginePath() {
        return cppEnginePath;
    }

    public String getCorsOrigins() {
        return corsOrigins;
    }
}
