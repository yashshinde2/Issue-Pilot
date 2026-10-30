package com.issuepilot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IssueRecord {

    private String id;

    @JsonProperty("issue_key")
    private String issueKey;

    @JsonProperty("canonical_repository_key")
    private String canonicalRepositoryKey;

    @JsonProperty("native_id")
    private String nativeId;

    @JsonProperty("source_issue_id")
    private String sourceIssueId;

    private String source = "github";

    @JsonProperty("source_instance")
    private String sourceInstance = "api.github.com";

    @JsonProperty("repository_id")
    private String repositoryId;

    @JsonProperty("repository_full_name")
    private String repositoryFullName;

    @JsonProperty("repository_owner")
    private String repositoryOwner;

    @JsonProperty("repository_name")
    private String repositoryName;

    @JsonProperty("repository_url")
    private String repositoryUrl;

    @JsonProperty("issue_number")
    private Integer issueNumber = 0;

    private String title;

    private String body = "";

    @JsonProperty("issue_url")
    private String issueUrl;

    @JsonProperty("canonical_url")
    private String canonicalUrl;

    private String product;

    private String component;

    private List<String> labels = new ArrayList<>();

    private String state = "open";

    @JsonProperty("is_assigned")
    private boolean isAssigned = false;

    private List<String> assignees = new ArrayList<>();

    @JsonProperty("comment_count")
    private int commentCount = 0;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    private List<TechnologyRelevance> technologies = new ArrayList<>();

    @JsonProperty("detected_technologies")
    private List<String> detectedTechnologies = new ArrayList<>();

    @JsonProperty("technology_evidence")
    private List<TechnologyRelevance> technologyEvidence;

    @JsonProperty("difficulty_evidence")
    private EvidenceModels.DifficultyEvidence difficultyEvidence = new EvidenceModels.DifficultyEvidence();

    @JsonProperty("responsiveness_evidence")
    private EvidenceModels.ResponsivenessEvidence responsivenessEvidence = new EvidenceModels.ResponsivenessEvidence();

    @JsonProperty("health_evidence")
    private EvidenceModels.RepositoryHealthEvidence healthEvidence = new EvidenceModels.RepositoryHealthEvidence();

    @JsonProperty("freshness_evidence")
    private EvidenceModels.FreshnessEvidence freshnessEvidence = new EvidenceModels.FreshnessEvidence();

    @JsonProperty("difficulty_evidence_v2")
    private Evidence<EvidenceModelsV2.DifficultyEvidenceV2> difficultyEvidenceV2;

    @JsonProperty("responsiveness_evidence_v2")
    private Evidence<EvidenceModelsV2.ResponsivenessEvidenceV2> responsivenessEvidenceV2;

    @JsonProperty("health_evidence_v2")
    private Evidence<EvidenceModelsV2.RepositoryHealthEvidenceV2> healthEvidenceV2;

    @JsonProperty("freshness_evidence_v2")
    private Evidence<EvidenceModelsV2.FreshnessEvidenceV2> freshnessEvidenceV2;

    @JsonProperty("algorithm_metadata")
    private AlgorithmMetadata algorithmMetadata = AlgorithmMetadata.current();

    @JsonProperty("stale_status")
    private String staleStatus = "active";

    private boolean verified = false;

    @JsonProperty("verification_notes")
    private String verificationNotes;

    @JsonProperty("first_seen_at")
    private String firstSeenAt;

    @JsonProperty("last_seen_at")
    private String lastSeenAt;

    @JsonProperty("closed_at")
    private String closedAt;

    @JsonProperty("last_activity_at")
    private String lastActivityAt;

    @JsonProperty("repository_language")
    private String repositoryLanguage;

    @JsonProperty("discovery_query")
    private String discoveryQuery;

    @JsonProperty("discovery_category")
    private String discoveryCategory;

    @JsonProperty("last_verified_at")
    private String lastVerifiedAt;

    @JsonProperty("first_seen")
    private String firstSeen;

    @JsonProperty("last_seen")
    private String lastSeen;

    @JsonProperty("last_verified")
    private String lastVerified;

    @JsonProperty("refresh_status")
    private String refreshStatus = "active";

    public IssueRecord() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getNativeId() { return nativeId; }
    public void setNativeId(String nativeId) { this.nativeId = nativeId; }
    public String getSourceIssueId() { return sourceIssueId; }
    public void setSourceIssueId(String sourceIssueId) { this.sourceIssueId = sourceIssueId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceInstance() { return sourceInstance; }
    public void setSourceInstance(String sourceInstance) { this.sourceInstance = sourceInstance; }
    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public void setRepositoryFullName(String repositoryFullName) { this.repositoryFullName = repositoryFullName; }
    public String getRepositoryOwner() { return repositoryOwner; }
    public void setRepositoryOwner(String repositoryOwner) { this.repositoryOwner = repositoryOwner; }
    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public void setRepositoryUrl(String repositoryUrl) { this.repositoryUrl = repositoryUrl; }
    public Integer getIssueNumber() { return issueNumber; }
    public void setIssueNumber(Integer issueNumber) { this.issueNumber = issueNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getIssueUrl() { return issueUrl; }
    public void setIssueUrl(String issueUrl) { this.issueUrl = issueUrl; }
    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getComponent() { return component; }
    public void setComponent(String component) { this.component = component; }
    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels != null ? labels : new ArrayList<>(); }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public boolean isAssigned() { return isAssigned; }
    public void setAssigned(boolean assigned) { isAssigned = assigned; }
    public List<String> getAssignees() { return assignees; }
    public void setAssignees(List<String> assignees) { this.assignees = assignees != null ? assignees : new ArrayList<>(); }
    public int getCommentCount() { return commentCount; }
    public void setCommentCount(int commentCount) { this.commentCount = commentCount; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public List<TechnologyRelevance> getTechnologies() { return technologies; }
    public void setTechnologies(List<TechnologyRelevance> technologies) { this.technologies = technologies != null ? technologies : new ArrayList<>(); }
    public List<String> getDetectedTechnologies() { return detectedTechnologies; }
    public void setDetectedTechnologies(List<String> detectedTechnologies) { this.detectedTechnologies = detectedTechnologies != null ? detectedTechnologies : new ArrayList<>(); }
    public List<TechnologyRelevance> getTechnologyEvidence() { return technologyEvidence; }
    public void setTechnologyEvidence(List<TechnologyRelevance> technologyEvidence) { this.technologyEvidence = technologyEvidence; }
    public EvidenceModels.DifficultyEvidence getDifficultyEvidence() { return difficultyEvidence; }
    public void setDifficultyEvidence(EvidenceModels.DifficultyEvidence difficultyEvidence) { this.difficultyEvidence = difficultyEvidence; }
    public EvidenceModels.ResponsivenessEvidence getResponsivenessEvidence() { return responsivenessEvidence; }
    public void setResponsivenessEvidence(EvidenceModels.ResponsivenessEvidence responsivenessEvidence) { this.responsivenessEvidence = responsivenessEvidence; }
    public EvidenceModels.RepositoryHealthEvidence getHealthEvidence() { return healthEvidence; }
    public void setHealthEvidence(EvidenceModels.RepositoryHealthEvidence healthEvidence) { this.healthEvidence = healthEvidence; }
    public EvidenceModels.FreshnessEvidence getFreshnessEvidence() { return freshnessEvidence; }
    public void setFreshnessEvidence(EvidenceModels.FreshnessEvidence freshnessEvidence) { this.freshnessEvidence = freshnessEvidence; }
    public String getStaleStatus() { return staleStatus; }
    public void setStaleStatus(String staleStatus) { this.staleStatus = staleStatus; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getVerificationNotes() { return verificationNotes; }
    public void setVerificationNotes(String verificationNotes) { this.verificationNotes = verificationNotes; }
    public String getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(String firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public String getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(String lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getClosedAt() { return closedAt; }
    public void setClosedAt(String closedAt) { this.closedAt = closedAt; }
    public String getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(String lastActivityAt) { this.lastActivityAt = lastActivityAt; }
    public String getRepositoryLanguage() { return repositoryLanguage; }
    public void setRepositoryLanguage(String repositoryLanguage) { this.repositoryLanguage = repositoryLanguage; }
    public String getDiscoveryQuery() { return discoveryQuery; }
    public void setDiscoveryQuery(String discoveryQuery) { this.discoveryQuery = discoveryQuery; }
    public String getDiscoveryCategory() { return discoveryCategory; }
    public void setDiscoveryCategory(String discoveryCategory) { this.discoveryCategory = discoveryCategory; }
    public String getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(String lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public String getFirstSeen() { return firstSeen; }
    public void setFirstSeen(String firstSeen) { this.firstSeen = firstSeen; }
    public String getLastSeen() { return lastSeen; }
    public void setLastSeen(String lastSeen) { this.lastSeen = lastSeen; }
    public String getLastVerified() { return lastVerified; }
    public void setLastVerified(String lastVerified) { this.lastVerified = lastVerified; }
    public String getRefreshStatus() { return refreshStatus; }
    public void setRefreshStatus(String refreshStatus) { this.refreshStatus = refreshStatus; }

    public String getIssueKey() {
        if (issueKey != null && !issueKey.isBlank()) {
            return issueKey;
        }
        String src = (source != null && !source.isBlank()) ? source : "github";
        String inst = (sourceInstance != null && !sourceInstance.isBlank()) ? sourceInstance : "api.github.com";
        String repo = (canonicalRepositoryKey != null && !canonicalRepositoryKey.isBlank()) ? canonicalRepositoryKey :
                      (repositoryFullName != null && !repositoryFullName.isBlank()) ? repositoryFullName :
                      (product != null ? (product + (component != null ? "/" + component : "")) : "unknown/unknown");
        String natId = (nativeId != null && !nativeId.isBlank()) ? nativeId :
                       (sourceIssueId != null && !sourceIssueId.isBlank()) ? sourceIssueId :
                       (issueNumber != null ? String.valueOf(issueNumber) : "0");
        return CanonicalIssueKey.build(src, inst, repo, natId);
    }

    public void setIssueKey(String issueKey) {
        this.issueKey = issueKey;
    }

    public String getCanonicalRepositoryKey() {
        if (canonicalRepositoryKey != null && !canonicalRepositoryKey.isBlank()) {
            return canonicalRepositoryKey;
        }
        if (repositoryFullName != null && !repositoryFullName.isBlank()) {
            return repositoryFullName;
        }
        if (product != null) {
            return product + (component != null ? "/" + component : "");
        }
        return "";
    }

    public void setCanonicalRepositoryKey(String canonicalRepositoryKey) {
        this.canonicalRepositoryKey = canonicalRepositoryKey;
    }

    public Evidence<EvidenceModelsV2.DifficultyEvidenceV2> getDifficultyEvidenceV2() {
        return difficultyEvidenceV2;
    }

    public void setDifficultyEvidenceV2(Evidence<EvidenceModelsV2.DifficultyEvidenceV2> difficultyEvidenceV2) {
        this.difficultyEvidenceV2 = difficultyEvidenceV2;
    }

    public Evidence<EvidenceModelsV2.ResponsivenessEvidenceV2> getResponsivenessEvidenceV2() {
        return responsivenessEvidenceV2;
    }

    public void setResponsivenessEvidenceV2(Evidence<EvidenceModelsV2.ResponsivenessEvidenceV2> responsivenessEvidenceV2) {
        this.responsivenessEvidenceV2 = responsivenessEvidenceV2;
    }

    public Evidence<EvidenceModelsV2.RepositoryHealthEvidenceV2> getHealthEvidenceV2() {
        return healthEvidenceV2;
    }

    public void setHealthEvidenceV2(Evidence<EvidenceModelsV2.RepositoryHealthEvidenceV2> healthEvidenceV2) {
        this.healthEvidenceV2 = healthEvidenceV2;
    }

    public Evidence<EvidenceModelsV2.FreshnessEvidenceV2> getFreshnessEvidenceV2() {
        return freshnessEvidenceV2;
    }

    public void setFreshnessEvidenceV2(Evidence<EvidenceModelsV2.FreshnessEvidenceV2> freshnessEvidenceV2) {
        this.freshnessEvidenceV2 = freshnessEvidenceV2;
    }

    public AlgorithmMetadata getAlgorithmMetadata() {
        return algorithmMetadata;
    }

    public void setAlgorithmMetadata(AlgorithmMetadata algorithmMetadata) {
        this.algorithmMetadata = algorithmMetadata;
    }
}
