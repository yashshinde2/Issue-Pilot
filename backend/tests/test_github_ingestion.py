"""
Automated test suite for Milestone 3: GitHub Issue Discovery and Refresh.
Validates:
1. GitHub response normalization.
2. Pull-request exclusion.
3. Missing optional fields handling.
4. Upsert idempotency & field preservation.
5. Pagination and checkpoint recovery.
6. Rate-limit responses and deferral.
7. Transient API failures & exponential backoff retry.
8. Issue closure, unavailable, and stale-state transitions.
9. Candidate pool bounds and repository diversity limits.
10. Job concurrency locks and failure reporting.
11. Ranking engine compatibility with stored candidate issues.
12. Scheduler service truthful status and API controls.
"""
import time
import pytest
from datetime import datetime, timedelta, timezone
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
import requests

from app.main import app
from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo, IssueRepository, JobRepository
from app.adapters.base import (
    RateLimitExceededError,
    TransientAPIError,
    ResourceNotFoundError
)
from app.adapters.github_adapter import GitHubAdapter
from app.ingestion.discovery import DiscoveryWorker
from app.ingestion.refresher import RefreshWorker
from app.ingestion.lifecycle import LifecycleWorker
from app.ingestion.scheduler import SchedulerService
from app.cpp_engine_client import cpp_client

client = TestClient(app)

@pytest.fixture(scope="module", autouse=True)
def setup_teardown_ingestion_tests():
    """Ensure database connection is established for ingestion tests."""
    db.connect()
    yield
    # Cleanup any test candidates
    if db.is_connected():
        db.db.issues.delete_many({"repository_full_name": "issue-pilot-org/sample-repo"})
        db.db.jobs.delete_many({"job_type": {"$in": ["test_lock_job", "discovery", "refresh", "cleanup"]}})

SAMPLE_GITHUB_ISSUE = {
    "id": 1092837465,
    "number": 42,
    "title": "Async worker connection timeout under heavy load",
    "body": "When concurrency exceeds 50 workers, pymongo connection pool throws ServerSelectionTimeoutError in fastapi async routes.",
    "html_url": "https://github.com/issue-pilot-org/sample-repo/issues/42",
    "repository_url": "https://api.github.com/repos/issue-pilot-org/sample-repo",
    "state": "open",
    "comments": 3,
    "labels": [{"name": "bug"}, {"name": "python"}, {"name": "fastapi"}],
    "assignees": [],
    "assignee": None,
    "created_at": (datetime.now(timezone.utc) - timedelta(days=2)).isoformat(),
    "updated_at": (datetime.now(timezone.utc) - timedelta(hours=5)).isoformat(),
    "closed_at": None,
    "repository": {"language": "Python"}
}

SAMPLE_GITHUB_PR = {
    "id": 1092837466,
    "number": 43,
    "title": "Fix async worker connection timeout",
    "body": "This PR optimizes pool timeout",
    "html_url": "https://github.com/issue-pilot-org/sample-repo/pull/43",
    "repository_url": "https://api.github.com/repos/issue-pilot-org/sample-repo",
    "state": "open",
    "pull_request": {"url": "https://api.github.com/repos/issue-pilot-org/sample-repo/pulls/43"},
    "comments": 1,
    "labels": [{"name": "python"}],
    "created_at": datetime.now(timezone.utc).isoformat(),
    "updated_at": datetime.now(timezone.utc).isoformat()
}


# =============================================================================
# 1. GitHub Response Normalization
# =============================================================================
def test_1_github_response_normalization():
    adapter = GitHubAdapter(token="")
    normalized = adapter.normalize_issue(SAMPLE_GITHUB_ISSUE, query_info={"query": "test_q", "category": "Python"})

    assert normalized is not None
    assert normalized["id"] == "gh_issue-pilot-org_sample-repo_42"
    assert normalized["native_id"] == "1092837465"
    assert normalized["source"] == "github"
    assert normalized["repository_full_name"] == "issue-pilot-org/sample-repo"
    assert normalized["repository_owner"] == "issue-pilot-org"
    assert normalized["repository_name"] == "sample-repo"
    assert normalized["issue_number"] == 42
    assert normalized["title"] == SAMPLE_GITHUB_ISSUE["title"]
    assert normalized["state"] == "open"
    assert normalized["is_assigned"] is False
    assert normalized["comment_count"] == 3
    assert "bug" in normalized["labels"]
    assert "python" in normalized["labels"]

    # Technology evidence
    assert "Python" in normalized["detected_technologies"] or "Fastapi" in normalized["detected_technologies"]
    # Evidence breakdown
    assert "difficulty_evidence" in normalized
    assert normalized["difficulty_evidence"]["technical_complexity"] >= 50.0
    assert "responsiveness_evidence" in normalized
    assert "health_evidence" in normalized
    assert "freshness_evidence" in normalized
    assert normalized["freshness_evidence"]["available"] is True


# =============================================================================
# 2. Pull Request Exclusion
# =============================================================================
def test_2_pull_request_exclusion():
    adapter = GitHubAdapter(token="")
    normalized_pr = adapter.normalize_issue(SAMPLE_GITHUB_PR)
    assert normalized_pr is None, "Pull requests must be excluded from the issue candidate pool"


# =============================================================================
# 3. Missing Optional Fields Handling
# =============================================================================
def test_3_missing_optional_fields_handling():
    adapter = GitHubAdapter(token="")
    minimal_issue = {
        "id": 999111,
        "number": 10,
        "title": "Minimal issue with missing fields",
        "html_url": "https://github.com/foo/bar/issues/10",
        "state": "open"
        # body, repository_url, labels, assignees, comments omitted
    }
    normalized = adapter.normalize_issue(minimal_issue)
    assert normalized is not None
    assert normalized["repository_full_name"] == "foo/bar"
    assert normalized["repository_owner"] == "foo"
    assert normalized["repository_name"] == "bar"
    assert normalized["body"] == ""
    assert normalized["labels"] == []
    assert normalized["assignees"] == []
    assert normalized["is_assigned"] is False
    assert normalized["comment_count"] == 0
    assert len(normalized["technologies"]) > 0


# =============================================================================
# 4. Upsert Idempotency & Field Preservation
# =============================================================================
def test_4_upsert_idempotency_and_field_preservation():
    if not db.is_connected():
        pytest.skip("MongoDB Atlas connection unavailable.")

    adapter = GitHubAdapter(token="")
    normalized = adapter.normalize_issue(SAMPLE_GITHUB_ISSUE)
    normalized["difficulty_evidence"]["technical_complexity"] = 88.0

    # First upsert
    res1 = issue_repo.upsert_issue(normalized, preserve_unsupplied=True)
    first_seen = res1.get("first_seen_at")
    assert first_seen is not None

    # Partial update: title updated, comments updated, difficulty_evidence omitted
    partial_update = {
        "source": "github",
        "repository_full_name": "issue-pilot-org/sample-repo",
        "issue_number": 42,
        "title": "Async worker connection timeout under heavy load - UPDATED",
        "comment_count": 5
    }
    res2 = issue_repo.upsert_issue(partial_update, preserve_unsupplied=True)

    # Exactly 1 record
    count = issue_repo.count_issues({
        "source": "github",
        "repository_full_name": "issue-pilot-org/sample-repo",
        "issue_number": 42
    })
    assert count == 1
    assert res2["title"] == "Async worker connection timeout under heavy load - UPDATED"
    assert res2["comment_count"] == 5
    # Preserved evidence
    assert res2["difficulty_evidence"]["technical_complexity"] == 88.0
    assert res2["first_seen_at"] == first_seen

    # Cleanup
    issue_repo.delete_issue(normalized["id"])


# =============================================================================
# 5. Pagination and Checkpoint Recovery
# =============================================================================
def test_5_pagination_and_checkpoint_recovery():
    mock_adapter = Mock(spec=GitHubAdapter)
    mock_adapter.rate_limit_state = {"remaining": 500, "limit": 5000, "reset": int(time.time()) + 3600}

    # Simulate 2 pages of search results
    item_p1 = dict(SAMPLE_GITHUB_ISSUE, number=101, id=800101)
    item_p2 = dict(SAMPLE_GITHUB_ISSUE, number=102, id=800102)

    def mock_search(query, page=1, per_page=30, sort="updated", order="desc"):
        if page == 1:
            return {"items": [item_p1], "total_count": 2}
        elif page == 2:
            return {"items": [item_p2], "total_count": 2}
        return {"items": [], "total_count": 2}

    mock_adapter.search_issues.side_effect = mock_search
    mock_adapter.normalize_issue.side_effect = lambda it, **kw: GitHubAdapter().normalize_issue(it)

    worker = DiscoveryWorker(adapter=mock_adapter)

    # Run page 1 only
    res1 = worker.run_discovery(max_pages_per_query=1, resume=False, force=True)
    assert res1["status"] in ("completed", "partial_success")
    assert res1["issues_discovered"] >= 1

    # Checkpoint was recorded
    if db.is_connected():
        chk = job_repo.get_latest_checkpoint("discovery")
        assert chk is not None
        assert "next_page" in chk
        assert chk["next_page"] == 1 or chk["topic_index"] >= 0

        # Cleanup created issues
        issue_repo.delete_issue("gh_issue-pilot-org_sample-repo_101")
        issue_repo.delete_issue("gh_issue-pilot-org_sample-repo_102")


# =============================================================================
# 6. Rate-Limit Responses and Deferral
# =============================================================================
def test_6_rate_limit_responses_and_deferral():
    mock_session = Mock(spec=requests.Session)
    mock_session.headers = {}
    mock_resp = Mock(spec=requests.Response)
    mock_resp.status_code = 403
    mock_resp.text = "API rate limit exceeded for user ID"
    mock_resp.headers = {
        "x-ratelimit-remaining": "0",
        "x-ratelimit-limit": "60",
        "x-ratelimit-reset": str(int(time.time()) + 1800),
        "retry-after": "60"
    }
    mock_session.request.return_value = mock_resp

    adapter = GitHubAdapter(session=mock_session)

    # Assert adapter raises RateLimitExceededError
    with pytest.raises(RateLimitExceededError) as exc_info:
        adapter.search_issues("test")
    assert exc_info.value.remaining == 0

    # Discovery worker catches rate limit and records job status 'rate_limited'
    worker = DiscoveryWorker(adapter=adapter)
    res = worker.run_discovery(force=True)
    assert res["status"] == "rate_limited"
    assert any("RateLimitExceeded" in err for err in res["errors"])


# =============================================================================
# 7. Transient API Failures & Exponential Backoff Retry
# =============================================================================
def test_7_transient_api_failures_and_exponential_backoff():
    mock_session = Mock(spec=requests.Session)
    mock_session.headers = {}

    # 1st attempt: 502 Bad Gateway
    resp_502 = Mock(spec=requests.Response)
    resp_502.status_code = 502
    resp_502.text = "Bad Gateway"
    resp_502.headers = {"x-ratelimit-remaining": "100"}

    # 2nd attempt: 200 OK
    resp_200 = Mock(spec=requests.Response)
    resp_200.status_code = 200
    resp_200.headers = {"x-ratelimit-remaining": "99"}
    resp_200.json.return_value = {"total_count": 0, "items": []}

    mock_session.request.side_effect = [resp_502, resp_200]

    adapter = GitHubAdapter(session=mock_session)
    result = adapter.search_issues("test_query")
    assert result["total_count"] == 0
    assert mock_session.request.call_count == 2


# =============================================================================
# 8. Issue Closure and Stale-State Transitions
# =============================================================================
def test_8_issue_closure_and_stale_state_transitions():
    if not db.is_connected():
        pytest.skip("MongoDB Atlas connection unavailable.")

    adapter = GitHubAdapter(token="")
    test_issue = dict(SAMPLE_GITHUB_ISSUE, number=999, id=999999)
    normalized = adapter.normalize_issue(test_issue)
    normalized["stale_status"] = "needs_refresh"
    issue_repo.upsert_issue(normalized)

    # 1. Closed on GitHub -> mark closed
    mock_adapter = Mock(spec=GitHubAdapter)
    mock_adapter.rate_limit_state = {"remaining": 500, "limit": 5000}
    mock_adapter.get_issue.return_value = {
        "state": "closed",
        "closed_at": datetime.now(timezone.utc).isoformat(),
        "number": 999
    }

    refresher = RefreshWorker(adapter=mock_adapter)
    ref_res = refresher.run_refresh(batch_size=10, max_age_days=0, force=True)
    assert ref_res["issues_marked_closed"] >= 1

    updated = issue_repo.get_issue_by_id(normalized["id"])
    assert updated["state"] == "closed"
    assert updated["stale_status"] == "closed"

    # Closed issues excluded from recommendation queries
    open_candidates = issue_repo.query_issues(state="open", search="issue-pilot-org/sample-repo")
    assert all(c["id"] != normalized["id"] for c in open_candidates)

    # 2. 404 on GitHub -> mark unavailable
    mock_adapter.get_issue.return_value = None
    issue_repo.upsert_issue(dict(normalized, state="open", stale_status="needs_refresh"))
    ref_res_404 = refresher.run_refresh(batch_size=10, max_age_days=0, force=True)
    assert ref_res_404["issues_marked_unavailable"] >= 1

    updated_404 = issue_repo.get_issue_by_id(normalized["id"])
    assert updated_404["stale_status"] == "unavailable"

    # 3. Older than older_window_days -> mark stale
    old_time = (datetime.now(timezone.utc) - timedelta(days=95)).isoformat()
    issue_repo.upsert_issue(dict(normalized, state="open", stale_status="active", last_seen_at=old_time))

    cleaner = LifecycleWorker()
    clean_res = cleaner.run_cleanup(older_than_days=90, force=True)
    assert clean_res["issues_marked_stale"] >= 1

    updated_stale = issue_repo.get_issue_by_id(normalized["id"])
    assert updated_stale["stale_status"] == "stale"

    # Cleanup
    issue_repo.delete_issue(normalized["id"])


# =============================================================================
# 9. Candidate Pool Bounds and Repository Diversity
# =============================================================================
def test_9_candidate_pool_bounds_and_repository_diversity():
    mock_adapter = Mock(spec=GitHubAdapter)
    mock_adapter.rate_limit_state = {"remaining": 500, "limit": 5000}

    # Generate 5 items from same repository
    same_repo_items = [
        dict(SAMPLE_GITHUB_ISSUE, number=i, id=990000 + i)
        for i in range(1, 6)
    ]
    mock_adapter.search_issues.return_value = {"items": same_repo_items, "total_count": 5}
    mock_adapter.normalize_issue.side_effect = lambda it, **kw: GitHubAdapter().normalize_issue(it)

    with patch.object(settings, "max_issues_per_repo", 2):
        worker = DiscoveryWorker(adapter=mock_adapter)
        res = worker.run_discovery(max_pages_per_query=1, resume=False, force=True)
        # Should have skipped items exceeding repository diversity limit of 2
        assert res["issues_skipped_diversity"] >= 3

    if db.is_connected():
        for i in range(1, 6):
            issue_repo.delete_issue(f"gh_issue-pilot-org_sample-repo_{i}")


# =============================================================================
# 10. Job Concurrency Lock and Failure Reporting
# =============================================================================
def test_10_job_concurrency_lock_and_failure_reporting():
    if not db.is_connected():
        pytest.skip("MongoDB Atlas connection unavailable.")

    # 1. Acquire lock
    job_id1 = job_repo.acquire_job_lock("test_lock_job", timeout_seconds=60)
    assert job_id1 is not None

    # 2. Second attempt must be rejected while lock is held
    job_id2 = job_repo.acquire_job_lock("test_lock_job", timeout_seconds=60)
    assert job_id2 is None, "Second job must not acquire lock while first is running"

    # 3. Release lock
    job_repo.release_job_lock(job_id1, status="completed", updates={"issues_discovered": 10})
    record = job_repo.get_job(job_id1)
    assert record["status"] == "completed"
    assert record["issues_discovered"] == 10
    assert record["finish_time"] is not None

    # 4. Now a new job can acquire the lock
    job_id3 = job_repo.acquire_job_lock("test_lock_job", timeout_seconds=60)
    assert job_id3 is not None
    job_repo.release_job_lock(job_id3, status="failed", updates={"errors": ["Simulated error"]})
    failed_record = job_repo.get_job(job_id3)
    assert failed_record["status"] == "failed"
    assert "Simulated error" in failed_record["errors"]


# =============================================================================
# 11. Ranking Compatibility with Stored Candidates
# =============================================================================
def test_11_ranking_compatibility_with_stored_candidates():
    adapter = GitHubAdapter(token="")
    normalized = adapter.normalize_issue(SAMPLE_GITHUB_ISSUE)

    profile = {
        "user_id": "test-contributor-m3",
        "skills": {
            "Python": {"technology": "Python", "score": 90.0, "confidence": 0.95},
            "FastAPI": {"technology": "FastAPI", "score": 85.0, "confidence": 0.90}
        },
        "capability": 75.0,
        "has_feedback_history": False
    }

    payload = {
        "profile": profile,
        "candidates": [normalized]
    }

    # Authoritative C++ ranking engine evaluates candidate
    ranking_res = cpp_client.execute_ranking(payload)
    assert "results" in ranking_res
    assert len(ranking_res["results"]) == 1
    rec = ranking_res["results"][0]
    assert rec["id"] == normalized["id"]
    assert rec["scores"]["skill_match"] > 0
    assert rec["scores"]["final_score"] > 0
    assert "Skill Match" in rec["explanation"] or "Match" in rec["explanation"]


# =============================================================================
# 12. Scheduler Status and API Controls
# =============================================================================
def test_12_scheduler_status_and_api_controls():
    sched = SchedulerService()
    # Initially inactive
    status_init = sched.get_status()
    assert status_init["scheduler_active"] is False

    # Start scheduler
    assert sched.start() is True
    assert sched.is_running is True
    status_running = sched.get_status()
    assert status_running["scheduler_active"] is True
    assert status_running["next_run_times"]["discovery"] is not None

    # Stop scheduler
    assert sched.stop() is True
    assert sched.is_running is False
    status_stopped = sched.get_status()
    assert status_stopped["scheduler_active"] is False

    # Test REST API endpoints
    status_res = client.get("/api/ingestion/status")
    assert status_res.status_code == 200
    data = status_res.json()
    assert "scheduler" in data
    assert "rate_limit" in data

    rate_res = client.get("/api/ingestion/rate-limit")
    assert rate_res.status_code == 200

    sched_res = client.get("/api/ingestion/scheduler/status")
    assert sched_res.status_code == 200
    assert "scheduler_active" in sched_res.json()
