package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JobRecord {

    @JsonProperty("job_id")
    private String jobId;

    @JsonProperty("job_type")
    private String jobType = "discovery_and_refresh";

    @JsonProperty("start_time")
    private String startTime;

    @JsonProperty("finish_time")
    private String finishTime;

    private String status = "in_progress";

    private Map<String, Object> checkpoints = new HashMap<>();

    @JsonProperty("queries_executed")
    private List<String> queriesExecuted = new ArrayList<>();

    @JsonProperty("issues_discovered")
    private int issuesDiscovered = 0;

    @JsonProperty("issues_inserted")
    private int issuesInserted = 0;

    @JsonProperty("issues_updated")
    private int issuesUpdated = 0;

    @JsonProperty("issues_skipped")
    private int issuesSkipped = 0;

    @JsonProperty("issues_marked_stale")
    private int issuesMarkedStale = 0;

    @JsonProperty("issues_marked_closed")
    private int issuesMarkedClosed = 0;

    private List<String> errors = new ArrayList<>();

    @JsonProperty("rate_limit_info")
    private Map<String, Object> rateLimitInfo;

    @JsonProperty("execution_metadata")
    private Map<String, Object> executionMetadata = new HashMap<>();

    @JsonProperty("per_source_summary")
    private Map<String, Object> perSourceSummary = new HashMap<>();

    public JobRecord() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getFinishTime() { return finishTime; }
    public void setFinishTime(String finishTime) { this.finishTime = finishTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getCheckpoints() { return checkpoints; }
    public void setCheckpoints(Map<String, Object> checkpoints) { this.checkpoints = checkpoints != null ? checkpoints : new HashMap<>(); }
    public List<String> getQueriesExecuted() { return queriesExecuted; }
    public void setQueriesExecuted(List<String> queriesExecuted) { this.queriesExecuted = queriesExecuted != null ? queriesExecuted : new ArrayList<>(); }
    public int getIssuesDiscovered() { return issuesDiscovered; }
    public void setIssuesDiscovered(int issuesDiscovered) { this.issuesDiscovered = issuesDiscovered; }
    public int getIssuesInserted() { return issuesInserted; }
    public void setIssuesInserted(int issuesInserted) { this.issuesInserted = issuesInserted; }
    public int getIssuesUpdated() { return issuesUpdated; }
    public void setIssuesUpdated(int issuesUpdated) { this.issuesUpdated = issuesUpdated; }
    public int getIssuesSkipped() { return issuesSkipped; }
    public void setIssuesSkipped(int issuesSkipped) { this.issuesSkipped = issuesSkipped; }
    public int getIssuesMarkedStale() { return issuesMarkedStale; }
    public void setIssuesMarkedStale(int issuesMarkedStale) { this.issuesMarkedStale = issuesMarkedStale; }
    public int getIssuesMarkedClosed() { return issuesMarkedClosed; }
    public void setIssuesMarkedClosed(int issuesMarkedClosed) { this.issuesMarkedClosed = issuesMarkedClosed; }
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors != null ? errors : new ArrayList<>(); }
    public Map<String, Object> getRateLimitInfo() { return rateLimitInfo; }
    public void setRateLimitInfo(Map<String, Object> rateLimitInfo) { this.rateLimitInfo = rateLimitInfo; }
    public Map<String, Object> getExecutionMetadata() { return executionMetadata; }
    public void setExecutionMetadata(Map<String, Object> executionMetadata) { this.executionMetadata = executionMetadata != null ? executionMetadata : new HashMap<>(); }
    public Map<String, Object> getPerSourceSummary() { return perSourceSummary; }
    public void setPerSourceSummary(Map<String, Object> perSourceSummary) { this.perSourceSummary = perSourceSummary != null ? perSourceSummary : new HashMap<>(); }
}
