"""
Milestone 5: Comprehensive End-to-End Integration & System Validation Suite.
Validates the complete 11-step workflow:
 1. Discover GitHub issues.
 2. Normalize and upsert issues into MongoDB Atlas.
 3. Retrieve a persisted contributor profile and issue candidate pool.
 4. Invoke the authoritative C++ ranking engine.
 5. Sort candidates by score.
 6. Verify shortlisted issues against GitHub.
 7. Exclude invalid candidates and backfill from lower-ranked candidates.
 8. Persist the final recommendation snapshot and verification outcomes.
 9. Retrieve recommendations through the API.
10. Submit feedback and confirm it persists.
11. Verify that subsequent recommendations can use available feedback data.

Also validates:
- Demo mode vs Live mode separation.
- Truthful error reporting on service failure (no silent mocks in live mode).
"""
import uuid
import pytest
from datetime import datetime, timezone
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient

from app.main import app
from app.database import db
from app.crud import issue_repo, user_repo, rec_repo, feedback_repo
from app.adapters.github_adapter import GitHubAdapter
from app.ingestion.discovery import DiscoveryWorker
from app.verifier import live_verifier
from app.models import ContributorProfile

client = TestClient(app)

TEST_USER_M5 = "m5_e2e_validator_user"
TEST_REPO_M5 = "issue-pilot-org/milestone5-test-repo"


@pytest.fixture(scope="module", autouse=True)
def setup_teardown_m5():
    """Ensure database connection and clean test artifacts."""
    db.connect()
    yield
    if db.is_connected():
        db.db.issues.delete_many({"repository_full_name": TEST_REPO_M5})
        db.db.users.delete_many({"user_id": TEST_USER_M5})
        db.db.recommendations.delete_many({"user_id": TEST_USER_M5})
        db.db.feedback.delete_many({"user_id": TEST_USER_M5})


def _mock_gh_response(status_code=200, json_data=None, headers=None):
    mock = Mock()
    mock.status_code = status_code
    mock.json.return_value = json_data or {}
    mock.headers = headers or {"X-RateLimit-Remaining": "4950"}
    return mock


def test_complete_11_step_e2e_workflow():
    """Execute and validate all 11 steps of the Issue Pilot end-to-end integration workflow."""
    if not db.is_connected():
        pytest.skip("MongoDB Atlas connection unavailable.")

    live_verifier.clear_cache()

    # -------------------------------------------------------------------------
    # STEP 1: Discover GitHub issues (mocked GitHub API response)
    # -------------------------------------------------------------------------
    mock_gh_adapter = Mock(spec=GitHubAdapter)
    mock_gh_adapter.rate_limit_state = {"remaining": 4900, "limit": 5000}
    sample_raw_issues = [
        {
            "id": 800000 + i,
            "number": 800 + i,
            "title": f"M5 Integration Issue #{i} Async Architecture",
            "body": "Need asynchronous worker integration with MongoDB and FastAPI.",
            "html_url": f"https://github.com/{TEST_REPO_M5}/issues/{800 + i}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M5}",
            "state": "open",
            "comments": 3 + i,
            "labels": [{"name": "backend"}, {"name": "python"}, {"name": "M5E2EIntegrationTech"}],
            "assignee": None,
            "assignees": [],
            "created_at": "2026-10-15T10:00:00Z",
            "updated_at": "2026-10-20T12:00:00Z"
        }
        for i in range(8)
    ]
    mock_gh_adapter.search_issues.return_value = {"items": sample_raw_issues, "total_count": len(sample_raw_issues)}

    real_adapter = GitHubAdapter(token="")
    mock_gh_adapter.normalize_issue.side_effect = real_adapter.normalize_issue

    discovery_worker = DiscoveryWorker(adapter=mock_gh_adapter)
    disc_res = discovery_worker.run_discovery(max_issues_to_discover=8, max_pages_per_query=1, force=True)
    assert disc_res["issues_discovered"] >= 8

    # -------------------------------------------------------------------------
    # STEP 2: Normalize and upsert issues into MongoDB Atlas
    # -------------------------------------------------------------------------
    real_adapter = GitHubAdapter(token="")
    normalized_ids = []
    for raw in sample_raw_issues:
        norm = real_adapter.normalize_issue(raw)
        norm["technologies"] = [{"technology": "M5E2EIntegrationTech", "raw_relevance": 1.0}]
        norm["detected_technologies"] = ["M5E2EIntegrationTech"]
        diff_val = 74.0 if raw["number"] == 800 else (50.0 + (raw["number"] % 7) * 4)
        norm["difficulty_evidence"] = {
            "scope_change_size": diff_val,
            "technical_complexity": diff_val,
            "historical_effort": diff_val,
            "dependency_arch_impact": diff_val,
            "coordination_complexity": diff_val
        }
        norm["responsiveness_evidence"] = {"available": True, "response_time_score": 85.0, "response_rate_score": 85.0, "pr_review_score": 80.0, "recent_maintainer_act": 85.0}
        norm["health_evidence"] = {"available": True, "recent_commits": 90.0, "pr_activity": 85.0, "issue_activity": 85.0, "active_contributors": 85.0, "recent_releases": 80.0}
        norm["freshness_evidence"] = {"available": True, "age_score": 90.0, "activity_score": 85.0}
        issue_repo.upsert_issue(norm)
        normalized_ids.append(norm["id"])

    # Confirm issues are persisted in Atlas
    stored = issue_repo.query_issues(search=TEST_REPO_M5, limit=20)
    assert len(stored) >= 8

    # -------------------------------------------------------------------------
    # STEP 3: Retrieve a persisted contributor profile and candidate pool
    # -------------------------------------------------------------------------
    initial_profile = ContributorProfile(
        user_id=TEST_USER_M5,
        capability=74.0,
        skills={
            "M5E2EIntegrationTech": {"technology": "M5E2EIntegrationTech", "score": 95.0, "confidence": 1.0}
        },
        has_feedback_history=False
    )
    user_repo.upsert_user(initial_profile)
    fetched_user = user_repo.get_user(TEST_USER_M5)
    assert fetched_user is not None
    assert fetched_user["capability"] == 74.0

    # -------------------------------------------------------------------------
    # STEPS 4, 5, 6, 7, 8: C++ Engine Invocation, Sorting, Verification, Backfill, Persistence
    # Mock GitHub verification: Rank #1 issue (800) is CLOSED on GitHub -> must backfill!
    # -------------------------------------------------------------------------
    def mock_verification_get(url, **kwargs):
        num = int(url.split("/")[-1])
        if num == 800:
            # Issue 800 closed on GitHub
            return _mock_gh_response(200, {"state": "closed", "number": num, "assignees": []})
        return _mock_gh_response(200, {
            "state": "open",
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M5}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M5}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_verification_get):
        rec_res = client.post("/api/recommendations/personalized", json={
            "user_id": TEST_USER_M5,
            "technology_filter": "M5E2EIntegrationTech",
            "target_count": 5,
            "prefer_live_atlas": True,
            "demo_mode": False
        })
        assert rec_res.status_code == 200
        rec_data = rec_res.json()

        # Step 4 & 5: C++ ranking produces deterministic sorted results
        assert rec_data["algorithm_version"] == "1.0.0-phase1"
        recs = rec_data["recommendations"]
        assert len(recs) == 5

        for i in range(len(recs)):
            assert recs[i]["delivered_rank"] == i + 1
            if i > 0:
                score_curr = recs[i].get("final_score") or recs[i]["scores"]["final_score"]
                score_prev = recs[i - 1].get("final_score") or recs[i - 1]["scores"]["final_score"]
                assert score_curr <= score_prev

        # Step 6 & 7: Issue 800 was excluded and backfilled
        delivered_numbers = [r["issue_number"] for r in recs]
        assert 800 not in delivered_numbers, "Closed issue 800 should have been discarded!"

        # Step 8: Recommendation snapshot persisted in Atlas
        run_id = rec_data["run_id"]
        assert run_id is not None

    # -------------------------------------------------------------------------
    # STEP 9: Retrieve recommendations through the API
    # -------------------------------------------------------------------------
    snapshots_res = client.get(f"/api/recommendations/snapshots/{TEST_USER_M5}")
    assert snapshots_res.status_code == 200
    assert snapshots_res.json()["count"] >= 1

    run_audit_res = client.get(f"/api/recommendations/runs/{run_id}")
    assert run_audit_res.status_code == 200
    audit_data = run_audit_res.json()
    assert len(audit_data["delivered"]) == 5
    # The rejected issue 800 is audited with exclusion reason
    rejected_issues = [r for r in audit_data["rejected"] if r.get("issue_number") == 800 or r.get("native_id") == "800" or "800" in r.get("issue_id", "")]
    assert len(rejected_issues) == 1
    assert "closed" in rejected_issues[0]["exclusion_reason"].lower()

    # -------------------------------------------------------------------------
    # STEP 10: Submit feedback and confirm it persists
    # -------------------------------------------------------------------------
    chosen_issue_id = recs[0]["id"]

    # Submit accepted feedback
    fb1 = client.post("/api/feedback", json={
        "user_id": TEST_USER_M5,
        "issue_id": chosen_issue_id,
        "event_type": "accepted"
    })
    assert fb1.status_code == 200
    assert fb1.json()["status"] == "success"

    # Submit completed feedback
    fb2 = client.post("/api/feedback", json={
        "user_id": TEST_USER_M5,
        "issue_id": chosen_issue_id,
        "event_type": "completed"
    })
    assert fb2.status_code == 200

    # Retrieve feedback history
    fb_history_res = client.get(f"/api/feedback/{TEST_USER_M5}")
    assert fb_history_res.status_code == 200
    counts = fb_history_res.json()["counts"]
    assert counts["accepted"] >= 1
    assert counts["completed"] >= 1

    # -------------------------------------------------------------------------
    # STEP 11: Verify that subsequent recommendations use available feedback data
    # -------------------------------------------------------------------------
    # The user document in Atlas now has has_feedback_history = True
    updated_user = user_repo.get_user(TEST_USER_M5)
    assert updated_user.get("has_feedback_history") is True
    assert updated_user.get("accepted_count", 0) >= 1
    assert updated_user.get("completed_count", 0) >= 1

    with patch.object(live_verifier.session, "get", side_effect=mock_verification_get):
        subsequent_res = client.post("/api/recommendations/personalized", json={
            "user_id": TEST_USER_M5,
            "technology_filter": "M5E2EIntegrationTech",
            "target_count": 5,
            "prefer_live_atlas": True,
            "demo_mode": False
        })
        assert subsequent_res.status_code == 200
        sub_recs = subsequent_res.json()["recommendations"]
        assert len(sub_recs) == 5

        # Check the first recommendation's scores: Feedback component must now be active!
        top_scores = sub_recs[0]["scores"]
        assert top_scores["feedback_available"] is True
        assert top_scores["feedback"] > 0.0
        # Missing components should no longer list Feedback (Cold Start)
        assert "Feedback (Cold Start)" not in top_scores["missing_components"]


def test_demo_mode_operates_without_credentials():
    """Verify demo mode operates cleanly without MongoDB Atlas or GitHub tokens."""
    res = client.post("/api/recommendations/personalized", json={
        "demo_mode": True,
        "prefer_live_atlas": False,
        "target_count": 5
    })
    assert res.status_code == 200
    data = res.json()
    assert data["data_source"] == "sample_demonstration_candidates"
    assert len(data["recommendations"]) == 5
    for r in data["recommendations"]:
        assert r["verified"] is True


def test_live_mode_reports_clear_error_when_database_unavailable():
    """
    Verify live mode truthfully reports service failure (HTTP 503)
    when MongoDB Atlas is unavailable, instead of silently returning mock data.
    """
    with patch.object(db, "is_connected", return_value=False):
        res = client.post("/api/recommendations/personalized", json={
            "user_id": "any-user",
            "demo_mode": False,
            "prefer_live_atlas": True
        })
        assert res.status_code == 503
        detail = res.json()["detail"]
        assert "MongoDB Atlas is unavailable" in detail
        assert "Set demo_mode=true" in detail
