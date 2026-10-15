from typing import List, Dict, Optional, Any
from pydantic import BaseModel, Field

class TechnologyRelevance(BaseModel):
    technology: str
    raw_relevance: float = 1.0
    normalized_relevance: Optional[float] = None

class ContributorSkill(BaseModel):
    technology: str
    score: float = 50.0       # 0 - 100
    confidence: float = 1.0   # 0 - 1

class ContributorProfile(BaseModel):
    user_id: str = "demo-contributor"
    skills: Dict[str, ContributorSkill] = Field(default_factory=dict)
    capability: float = 70.0
    accepted_count: int = 0
    rejected_count: int = 0
    completed_count: int = 0
    similarity_to_history: float = 75.0
    has_feedback_history: bool = False
    preferences: Dict[str, Any] = Field(default_factory=dict)
    created_at: Optional[str] = None
    updated_at: Optional[str] = None
    last_active_at: Optional[str] = None

class RankingWeights(BaseModel):
    skill_weight: float = 0.35
    difficulty_weight: float = 0.25
    responsiveness_weight: float = 0.15
    health_weight: float = 0.10
    feedback_weight: float = 0.10
    freshness_weight: float = 0.05

class DifficultyEvidence(BaseModel):
    scope_change_size: float = 50.0
    technical_complexity: float = 50.0
    historical_effort: float = 50.0
    dependency_arch_impact: float = 50.0
    coordination_complexity: float = 50.0

class ResponsivenessEvidence(BaseModel):
    available: bool = True
    response_time_score: float = 80.0
    response_rate_score: float = 80.0
    pr_review_score: float = 80.0
    recent_maintainer_act: float = 80.0

class RepositoryHealthEvidence(BaseModel):
    available: bool = True
    recent_commits: float = 80.0
    pr_activity: float = 80.0
    issue_activity: float = 80.0
    active_contributors: float = 80.0
    recent_releases: float = 80.0

class FreshnessEvidence(BaseModel):
    available: bool = True
    age_score: float = 80.0
    activity_score: float = 80.0

class IssueRecord(BaseModel):
    id: str
    native_id: Optional[str] = None
    repository_full_name: str
    repository_owner: Optional[str] = None
    repository_name: Optional[str] = None
    repository_url: Optional[str] = None
    issue_number: int
    title: str
    body: Optional[str] = ""
    issue_url: str
    labels: List[str] = Field(default_factory=list)
    state: str = "open"
    is_assigned: bool = False
    assignees: List[str] = Field(default_factory=list)
    comment_count: int = 0
    created_at: Optional[str] = None
    updated_at: Optional[str] = None
    technologies: List[TechnologyRelevance] = Field(default_factory=list)
    detected_technologies: List[str] = Field(default_factory=list)
    difficulty_evidence: DifficultyEvidence = Field(default_factory=DifficultyEvidence)
    responsiveness_evidence: ResponsivenessEvidence = Field(default_factory=ResponsivenessEvidence)
    health_evidence: RepositoryHealthEvidence = Field(default_factory=RepositoryHealthEvidence)
    freshness_evidence: FreshnessEvidence = Field(default_factory=FreshnessEvidence)
    source: str = "github"
    stale_status: str = "active"
    verified: bool = False
    verification_notes: Optional[str] = None
    first_seen_at: Optional[str] = None
    last_seen_at: Optional[str] = None

class ComponentScores(BaseModel):
    skill_match: float
    difficulty_match: float
    computed_issue_difficulty: float
    responsiveness: float
    responsiveness_available: bool
    repository_health: float
    health_available: bool
    feedback: float
    feedback_available: bool
    freshness: float
    freshness_available: bool
    final_score: float

class RankingResult(BaseModel):
    rank: int
    id: str
    issue_number: int
    title: str
    repository_full_name: str
    issue_url: str
    state: str
    is_assigned: bool
    verified: bool
    verification_notes: Optional[str] = None
    scores: ComponentScores
    effective_weights: Dict[str, float]
    weighted_contributions: Dict[str, float]
    missing_components: List[str] = Field(default_factory=list)
    explanation: str

class SimulatorRequest(BaseModel):
    profile: ContributorProfile
    weights: Optional[RankingWeights] = None
    candidates: List[IssueRecord] = Field(default_factory=list)

class FeedbackEvent(BaseModel):
    user_id: str
    issue_id: str
    event_type: str # accepted, rejected, completed
    event_timestamp: Optional[str] = None
    recommendation_snapshot: Optional[Dict[str, Any]] = None

class RecommendationRecord(BaseModel):
    user_id: str
    issue_id: str
    native_id: Optional[str] = None
    repository_full_name: Optional[str] = None
    rank: int
    final_score: float
    scores: Optional[Dict[str, Any]] = None
    effective_weights: Optional[Dict[str, float]] = None
    weighted_contributions: Optional[Dict[str, float]] = None
    missing_components: List[str] = Field(default_factory=list)
    explanation: Optional[str] = None
    verification_notes: Optional[str] = None
    algorithm_version: str = "1.0.0-phase1"
    generated_at: Optional[str] = None

class JobRecord(BaseModel):
    job_id: str
    job_type: str = "discovery_and_refresh"
    start_time: str
    finish_time: Optional[str] = None
    status: str = "in_progress"
    checkpoints: Dict[str, Any] = Field(default_factory=dict)
    queries_executed: List[str] = Field(default_factory=list)
    issues_discovered: int = 0
    issues_inserted: int = 0
    issues_updated: int = 0
    issues_skipped: int = 0
    errors: List[str] = Field(default_factory=list)
    execution_metadata: Dict[str, Any] = Field(default_factory=dict)

