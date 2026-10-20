"""
Comprehensive automated test suite for Milestone 4:
Live GitHub Verification and Top-5 Recommendations.

Tests cover:
1. Five valid candidates produce five recommendations.
2. Closed issues are excluded and lower-ranked candidates backfill the list.
3. Assigned issues are excluded according to the project's eligibility policy.
4. Pull requests are excluded.
5. Deleted or inaccessible issues are handled correctly.
6. A repository redirect or rename does not incorrectly invalidate an issue.
7. Rate limits produce bounded backoff and a clear partial-result state.
8. Transient network failures are retried within configured limits.
9. Inconclusive verification is not treated as proof of closure.
10. Fewer than five valid candidates returns a shorter list without fabrication.
11. Candidates are delivered in the correct ranking order.
12. Persisted recommendation records contain scores, ranks, verification results, and exclusion reasons.
13. Retrying a run does not create unintended duplicate recommendation records.
14. Demo mode works without credentials.
15. Existing C++ scoring tests, MongoDB persistence tests, and ingestion tests still pass.
+ Integration test for complete pipeline: stored candidates -> C++ ranking -> mocked live verification -> backfill -> final recommendations -> persistence.
"""

import time
import pytest
from unittest.mock import Mock, patch
from requests.exceptions import Timeout, ConnectionError
from fastapi.testclient import TestClient

from app.main import app
from app.config import settings
from app.database import db
from app.crud import issue_repo, user_repo, rec_repo
from app.models import ContributorProfile, IssueRecord, RankingWeights
from app.verifier import live_verifier, LiveVerifier, VerificationResult

client = TestClient(app)

TEST_USER_M4 = "test-m4-developer"
TEST_REPO_M4 = "issuepilot/m4-verify-test"


@pytest.fixture(scope="module", autouse=True)
def setup_m4_test_environment():
    """Connect to database and ensure clean test collections."""
    db.connect()
    live_verifier.clear_cache()
    yield
    if db.is_connected():
        db.db.issues.delete_many({"repository_full_name": {"$regex": r"issuepilot/m4-"}})
        db.db.users.delete_one({"user_id": TEST_USER_M4})
        db.db.recommendations.delete_many({"user_id": TEST_USER_M4})
    live_verifier.clear_cache()


def _make_candidate(
    issue_id: str,
    number: int,
    title: str,
    repo: str = TEST_REPO_M4,
    tech: str = "Python",
    score_bump: float = 0.0
) -> dict:
    return {
        "id": issue_id,
        "source": "github",
        "repository_full_name": repo,
        "issue_number": number,
        "title": title,
        "issue_url": f"https://github.com/{repo}/issues/{number}",
        "state": "open",
        "is_assigned": False,
        "detected_technologies": [tech],
        "technologies": [{"technology": tech, "raw_relevance": 1.0}],
        "difficulty_evidence": {
            "scope_change_size": 50.0 + score_bump,
            "technical_complexity": 50.0 + score_bump,
            "historical_effort": 50.0 + score_bump,
            "dependency_arch_impact": 50.0,
            "coordination_complexity": 50.0
        },
        "responsiveness_evidence": {
            "available": True,
            "response_time_score": 80.0 + score_bump,
            "response_rate_score": 80.0,
            "pr_review_score": 80.0,
            "recent_maintainer_act": 80.0
        },
        "health_evidence": {
            "available": True,
            "recent_commits": 80.0,
            "pr_activity": 80.0,
            "issue_activity": 80.0,
            "active_contributors": 80.0,
            "recent_releases": 80.0
        },
        "freshness_evidence": {
            "available": True,
            "age_score": 80.0,
            "activity_score": 80.0
        }
    }


def _mock_gh_response(status_code=200, json_data=None, headers=None):
    resp = Mock()
    resp.status_code = status_code
    resp._json = json_data or {}
    resp.json = Mock(return_value=resp._json)
    resp.text = str(json_data or "")
    resp.headers = headers or {"x-ratelimit-remaining": "5000", "ETag": 'W/"test-etag"'}
    return resp


# ==============================================================================
# 1. Five valid candidates produce five recommendations
# ==============================================================================
def test_1_five_valid_candidates_produce_five_recommendations():
    live_verifier.clear_cache()
    candidates = [_make_candidate(f"m4_cand_{i}", 100 + i, f"Valid candidate issue {i}") for i in range(8)]

    # Mock all GitHub responses as open, unassigned issues
    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        return _mock_gh_response(200, {
            "state": "open",
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": url.replace("api.github.com/repos", "github.com"),
            "repository_url": "https://api.github.com/repos/" + TEST_REPO_M4
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(
            candidates,
            target_count=5,
            max_budget=30
        )

        assert len(recs) == 5, f"Expected exactly 5 recommendations, got {len(recs)}"
        assert metrics["delivered_count"] == 5
        assert metrics["is_partial_result"] is False
        for idx, rec in enumerate(recs):
            assert rec["delivered_rank"] == idx + 1
            assert rec["verified"] is True
            assert rec["verification_status"] == "live_verified"


# ==============================================================================
# 2. Closed issues are excluded and lower-ranked candidates backfill the list
# ==============================================================================
def test_2_closed_issues_excluded_and_lower_ranked_candidates_backfill():
    live_verifier.clear_cache()
    # 7 candidates in descending rank order
    candidates = [_make_candidate(f"m4_backfill_{i}", 200 + i, f"Backfill test issue {i}") for i in range(7)]

    # Issues 200 and 202 are closed on GitHub
    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        state = "closed" if num in (200, 202) else "open"
        return _mock_gh_response(200, {
            "state": state,
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(
            candidates,
            target_count=5,
            max_budget=30
        )

        # 5 delivered recommendations after backfilling candidates 201, 203, 204, 205, 206
        assert len(recs) == 5
        delivered_numbers = [r["issue_number"] for r in recs]
        assert 200 not in delivered_numbers, "Closed issue 200 was not excluded!"
        assert 202 not in delivered_numbers, "Closed issue 202 was not excluded!"
        assert delivered_numbers == [201, 203, 204, 205, 206]
        assert metrics["candidates_excluded"] == 2


# ==============================================================================
# 3. Assigned issues are excluded according to the project's eligibility policy
# ==============================================================================
def test_3_assigned_issues_excluded_according_to_eligibility_policy():
    live_verifier.clear_cache()
    candidates = [_make_candidate(f"m4_assign_{i}", 300 + i, f"Assignment test {i}") for i in range(6)]

    # Candidate 300 is assigned to an existing contributor
    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        assignee = {"login": "octocat"} if num == 300 else None
        return _mock_gh_response(200, {
            "state": "open",
            "number": num,
            "assignee": assignee,
            "assignees": [assignee] if assignee else [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(candidates, target_count=5)
        delivered_numbers = [r["issue_number"] for r in recs]
        assert 300 not in delivered_numbers, "Assigned issue 300 was not excluded!"
        assert len(recs) == 5
        # Verify exclusion reason was audited
        audit_300 = next(a for a in audits if a["issue_number"] == 300)
        assert audit_300["decision"] == "rejected"
        assert "assigned" in audit_300["exclusion_reason"].lower()


# ==============================================================================
# 4. Pull requests are excluded
# ==============================================================================
def test_4_pull_requests_are_excluded():
    live_verifier.clear_cache()
    candidates = [_make_candidate(f"m4_pr_{i}", 400 + i, f"PR exclusion test {i}") for i in range(6)]

    # Candidate 400 is actually a Pull Request returned by GitHub Issues API
    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        pr_obj = {"url": f"https://api.github.com/repos/{TEST_REPO_M4}/pulls/{num}"} if num == 400 else None
        data = {
            "state": "open",
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        }
        if pr_obj:
            data["pull_request"] = pr_obj
        return _mock_gh_response(200, data)

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(candidates, target_count=5)
        delivered_numbers = [r["issue_number"] for r in recs]
        assert 400 not in delivered_numbers, "Pull request 400 was not excluded!"
        assert len(recs) == 5
        audit_400 = next(a for a in audits if a["issue_number"] == 400)
        assert audit_400["decision"] == "rejected"
        assert "pull request" in audit_400["exclusion_reason"].lower()


# ==============================================================================
# 5. Deleted or inaccessible issues are handled correctly (HTTP 404)
# ==============================================================================
def test_5_deleted_or_inaccessible_issues_handled_correctly():
    live_verifier.clear_cache()
    candidates = [_make_candidate(f"m4_404_{i}", 500 + i, f"404 test {i}") for i in range(6)]

    # Candidate 500 returned HTTP 404 (deleted or private repo)
    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        if num == 500:
            return _mock_gh_response(404, {"message": "Not Found"})
        return _mock_gh_response(200, {
            "state": "open",
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(candidates, target_count=5)
        delivered_numbers = [r["issue_number"] for r in recs]
        assert 500 not in delivered_numbers, "Deleted issue 500 was not excluded!"
        assert len(recs) == 5
        audit_500 = next(a for a in audits if a["issue_number"] == 500)
        assert audit_500["decision"] == "rejected"
        assert "404" in audit_500["exclusion_reason"]


# ==============================================================================
# 6. Repository redirect or rename does not incorrectly invalidate an issue
# ==============================================================================
def test_6_repository_redirect_or_rename_does_not_invalidate_issue():
    live_verifier.clear_cache()
    candidate = _make_candidate("m4_redirect_1", 601, "Redirected repo issue", repo="old-org/old-repo")

    # GitHub responds with canonical new URL from org rename
    def mock_get(url, **kwargs):
        return _mock_gh_response(200, {
            "state": "open",
            "number": 601,
            "assignee": None,
            "assignees": [],
            "html_url": "https://github.com/new-org/renamed-repo/issues/601",
            "repository_url": "https://api.github.com/repos/new-org/renamed-repo"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill([candidate], target_count=1)
        assert len(recs) == 1
        rec = recs[0]
        assert rec["verified"] is True
        # Canonical repository was updated from redirect
        assert rec["repository_full_name"] == "new-org/renamed-repo"
        assert rec["canonical_url"] == "https://github.com/new-org/renamed-repo/issues/601"


# ==============================================================================
# 7. Rate limits produce bounded backoff and a clear partial-result state
# ==============================================================================
def test_7_rate_limits_produce_clear_partial_result_and_warning():
    live_verifier.clear_cache()
    candidates = [_make_candidate(f"m4_rl_{i}", 700 + i, f"Rate limit test {i}") for i in range(4)]

    # Candidate 700 succeeds, then 701, 702, 703 hit rate limit (HTTP 429)
    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        if num == 700:
            return _mock_gh_response(200, {
                "state": "open",
                "number": 700,
                "assignee": None,
                "assignees": [],
                "html_url": f"https://github.com/{TEST_REPO_M4}/issues/700",
                "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
            })
        return _mock_gh_response(429, {"message": "API rate limit exceeded"}, headers={"x-ratelimit-remaining": "0"})

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(candidates, target_count=5)
        # Only 1 candidate could be verified
        assert len(recs) == 1
        assert metrics["is_partial_result"] is True
        assert metrics["candidates_inconclusive"] == 3
        assert metrics["warning"] is not None
        assert "rate limits" in metrics["warning"].lower()
        # Inconclusive candidates are NOT marked as rejected/closed
        rl_audits = [a for a in audits if a["issue_number"] > 700]
        for a in rl_audits:
            assert a["decision"] == "inconclusive"
            assert a["verification_status"] == "inconclusive"


# ==============================================================================
# 8. Transient network failures are retried within configured limits
# ==============================================================================
def test_8_transient_network_failures_are_retried():
    live_verifier.clear_cache()
    candidate = _make_candidate("m4_retry_1", 801, "Retry test issue")

    call_count = 0

    def mock_get(url, **kwargs):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            # 1st attempt fails with transient 503
            return _mock_gh_response(503, {"message": "Service Unavailable"})
        # 2nd attempt succeeds
        return _mock_gh_response(200, {
            "state": "open",
            "number": 801,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/801",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        with patch("time.sleep", return_value=None):  # Fast execution
            recs, audits, metrics = live_verifier.verify_and_backfill([candidate], target_count=1, max_retries=2)
            assert len(recs) == 1
            assert recs[0]["verified"] is True
            assert call_count == 2, "Expected retry to have triggered on transient error!"


# ==============================================================================
# 9. Inconclusive verification is not treated as proof of closure
# ==============================================================================
def test_9_inconclusive_verification_not_treated_as_proof_of_closure():
    live_verifier.clear_cache()
    candidate = _make_candidate("m4_timeout_1", 901, "Timeout test issue")

    # Simulate persistent network timeout
    def mock_get(url, **kwargs):
        raise Timeout("Connection timed out to api.github.com")

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        with patch("time.sleep", return_value=None):
            recs, audits, metrics = live_verifier.verify_and_backfill([candidate], target_count=1, max_retries=2)
            assert len(recs) == 0
            assert metrics["candidates_inconclusive"] == 1
            assert metrics["candidates_excluded"] == 0
            audit = audits[0]
            assert audit["verification_status"] == "inconclusive"
            assert audit["decision"] == "inconclusive"
            # Crucial: Not excluded as closed!
            assert "closed" not in audit["exclusion_reason"].lower()


# ==============================================================================
# 10. Fewer than five valid candidates returns a shorter list without fabrication
# ==============================================================================
def test_10_fewer_than_five_valid_candidates_returns_shorter_list():
    live_verifier.clear_cache()
    # Only 3 candidates exist in total
    candidates = [_make_candidate(f"m4_short_{i}", 1000 + i, f"Short candidate {i}") for i in range(3)]

    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        return _mock_gh_response(200, {
            "state": "open",
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(candidates, target_count=5)
        # Exactly 3 recommendations delivered, no synthetic fabrication to reach 5
        assert len(recs) == 3
        assert metrics["delivered_count"] == 3
        assert metrics["is_partial_result"] is True
        assert metrics["partial_result_reason"] is not None
        assert "Delivered 3 of 5" in metrics["partial_result_reason"]


# ==============================================================================
# 11. Candidates are delivered in correct ranking order
# ==============================================================================
def test_11_candidates_delivered_in_correct_ranking_order():
    live_verifier.clear_cache()
    # 5 candidates with explicitly ordered scores
    candidates = []
    for i in range(5):
        c = _make_candidate(f"m4_order_{i}", 1100 + i, f"Order test {i}")
        c["scores"] = {"final_score": 95.0 - (i * 5.0)}
        c["final_score"] = 95.0 - (i * 5.0)
        candidates.append(c)

    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        return _mock_gh_response(200, {
            "state": "open",
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        recs, audits, metrics = live_verifier.verify_and_backfill(candidates, target_count=5)
        assert len(recs) == 5
        for i in range(len(recs)):
            assert recs[i]["delivered_rank"] == i + 1
            if i > 0:
                assert recs[i]["final_score"] <= recs[i - 1]["final_score"]


# ==============================================================================
# 12. Persisted recommendation records contain scores, ranks, verification results, and exclusion reasons
# ==============================================================================
def test_12_persisted_recommendation_records_contain_all_audit_fields():
    run_id = "test_run_audit_fields_12"
    rec_item = {
        "id": "audit_issue_1",
        "issue_number": 1201,
        "repository_full_name": TEST_REPO_M4,
        "rank": 1,
        "original_rank": 1,
        "delivered_rank": 1,
        "final_score": 89.5,
        "scores": {"final_score": 89.5, "skill_match": 92.0, "difficulty_match": 85.0},
        "effective_weights": {"skill_weight": 0.35, "difficulty_weight": 0.25},
        "weighted_contributions": {"skill": 32.2, "difficulty": 21.25},
        "missing_components": [],
        "explanation": "High skill alignment with Python codebase.",
        "verification_status": "live_verified",
        "verified_at": "2026-10-20T12:00:00Z",
        "verification_notes": "Live GitHub state confirmed: Open & Unassigned",
        "decision": "included",
        "exclusion_reason": None,
        "response_metadata": {"status_code": 200, "etag": 'W/"abc"'}
    }

    excluded_item = {
        "id": "audit_issue_2",
        "issue_number": 1202,
        "repository_full_name": TEST_REPO_M4,
        "rank": 2,
        "original_rank": 2,
        "delivered_rank": None,
        "final_score": 85.0,
        "scores": {"final_score": 85.0},
        "effective_weights": {"skill_weight": 0.35},
        "weighted_contributions": {"skill": 29.75},
        "missing_components": [],
        "explanation": "Moderate skill alignment.",
        "verification_status": "excluded",
        "verified_at": "2026-10-20T12:00:00Z",
        "verification_notes": "Issue is closed on GitHub",
        "decision": "rejected",
        "exclusion_reason": "Issue is closed on GitHub",
        "response_metadata": {"status_code": 200}
    }

    count = rec_repo.save_recommendations(
        user_id=TEST_USER_M4,
        recommendations=[rec_item],
        algorithm_version="1.0.0-phase1",
        run_id=run_id,
        audited_candidates=[rec_item, excluded_item]
    )
    assert count == 2

    # Query audit records
    records = rec_repo.get_run_recommendations(run_id)
    assert len(records) == 2
    rec_doc = next(r for r in records if r["issue_id"] == "audit_issue_1")
    excl_doc = next(r for r in records if r["issue_id"] == "audit_issue_2")

    # Assert complete auditability
    assert rec_doc["original_rank"] == 1
    assert rec_doc["delivered_rank"] == 1
    assert rec_doc["decision"] == "included"
    assert rec_doc["verification_status"] == "live_verified"
    assert rec_doc["scores"]["final_score"] == 89.5

    assert excl_doc["original_rank"] == 2
    assert excl_doc["delivered_rank"] is None
    assert excl_doc["decision"] == "rejected"
    assert excl_doc["exclusion_reason"] == "Issue is closed on GitHub"


# ==============================================================================
# 13. Retrying a run does not create unintended duplicate recommendation records
# ==============================================================================
def test_13_retrying_run_does_not_create_duplicate_records():
    run_id = "test_run_idempotent_retry_13"
    rec_items = [
        {
            "id": f"idem_issue_{i}",
            "issue_number": 1300 + i,
            "repository_full_name": TEST_REPO_M4,
            "rank": i + 1,
            "final_score": 80.0,
            "scores": {"final_score": 80.0},
            "decision": "included"
        }
        for i in range(3)
    ]

    # First execution
    rec_repo.save_recommendations(
        user_id=TEST_USER_M4,
        recommendations=rec_items,
        run_id=run_id
    )
    records_1 = rec_repo.get_run_recommendations(run_id)
    assert len(records_1) == 3

    # Retry with exact same run_id (e.g. network retry)
    rec_repo.save_recommendations(
        user_id=TEST_USER_M4,
        recommendations=rec_items,
        run_id=run_id
    )
    records_2 = rec_repo.get_run_recommendations(run_id)
    # Must still be exactly 3 records, NO duplicates!
    assert len(records_2) == 3


# ==============================================================================
# 14. Demo mode works without credentials
# ==============================================================================
def test_14_demo_mode_works_without_credentials():
    res = client.post("/api/recommendations/personalized", json={
        "demo_mode": True,
        "prefer_live_atlas": False,
        "target_count": 5
    })
    assert res.status_code == 200
    data = res.json()
    assert data["data_source"] == "sample_demonstration_candidates"
    recs = data["recommendations"]
    assert len(recs) == 5
    for r in recs:
        assert r["verified"] is True
        assert r["verification_status"] == "sample_verified"


# ==============================================================================
# 15. End-to-end integration test: Stored candidates -> C++ ranking -> Live verification -> Backfill -> Persistence
# ==============================================================================
def test_15_end_to_end_integration_pipeline():
    live_verifier.clear_cache()
    # 1. Insert 8 candidate issues into MongoDB Atlas candidate pool with isolated tech filter
    test_issues = [
        _make_candidate(f"e2e_m4_issue_{i}", 1500 + i, f"E2E Test Issue {i}", tech="M4IntegrationTech", score_bump=float(i * 3))
        for i in range(8)
    ]
    for issue in test_issues:
        issue_repo.upsert_issue(issue)

    # 2. Upsert contributor profile into Atlas with matching technology skill
    profile = ContributorProfile(
        user_id=TEST_USER_M4,
        capability=75.0,
        skills={"M4IntegrationTech": {"technology": "M4IntegrationTech", "score": 90.0, "confidence": 0.95}}
    )
    user_repo.upsert_user(profile)

    # 3. Mock GitHub verification: issues 1507 and 1505 are closed/assigned; others open
    def mock_get(url, **kwargs):
        num = int(url.split("/")[-1])
        if num == 1507:
            return _mock_gh_response(200, {"state": "closed", "number": num, "assignees": []})
        if num == 1505:
            return _mock_gh_response(200, {"state": "open", "number": num, "assignees": [{"login": "dev"}]})
        return _mock_gh_response(200, {
            "state": "open",
            "number": num,
            "assignee": None,
            "assignees": [],
            "html_url": f"https://github.com/{TEST_REPO_M4}/issues/{num}",
            "repository_url": f"https://api.github.com/repos/{TEST_REPO_M4}"
        })

    with patch.object(live_verifier.session, "get", side_effect=mock_get):
        res = client.post("/api/recommendations/personalized", json={
            "user_id": TEST_USER_M4,
            "technology_filter": "M4IntegrationTech",
            "prefer_live_atlas": True,
            "demo_mode": False,
            "target_count": 5,
            "verification_budget": 30
        })

        assert res.status_code == 200
        data = res.json()
        assert data["data_source"] == "atlas_candidates"
        assert data["user_id"] == TEST_USER_M4
        assert "run_id" in data

        recs = data["recommendations"]
        assert len(recs) == 5, f"Expected 5 recommendations, got {len(recs)}"

        # Candidates 1507 (closed) and 1505 (assigned) must NOT be in recommendations
        delivered_nums = [r["issue_number"] for r in recs]
        assert 1507 not in delivered_nums
        assert 1505 not in delivered_nums

        # Ranks must be 1..5 in order
        for idx, r in enumerate(recs):
            assert r["delivered_rank"] == idx + 1
            assert r["verified"] is True
            assert r["verification_status"] == "live_verified"

        # 4. Verify MongoDB Atlas recommendation snapshot persistence
        run_audit = client.get(f"/api/recommendations/runs/{data['run_id']}")
        assert run_audit.status_code == 200
        audit_data = run_audit.json()
        assert len(audit_data["delivered"]) == 5
        assert len(audit_data["rejected"]) >= 2
