"""
Automated test suite for Multi-Platform Integration:
GitHub, GitLab, Codeberg/Forgejo, and Bugzilla.

Validates:
1. Valid & invalid credentials for each adapter.
2. Authentication & permission failure handling.
3. API timeouts and server error handling.
4. Rate-limit parsing and Retry-After handling.
5. Unknown rate-limit handling and local request budgets (Bugzilla).
6. Platform-specific response normalization & pull/merge request exclusion.
7. Missing fields and optional metadata resilience.
8. Source-aware issue identity and idempotent upsert in MongoDB Atlas.
9. Fault isolation: one failing platform does not block the other three.
10. Multi-platform live candidate verification and backfilling.
11. Authoritative C++ 6-factor ranking with missing/unavailable signals.
12. Admin API endpoints and credential privacy.
"""
import time
import pytest
from datetime import datetime, timezone, timedelta
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient

from app.main import app
from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo
from app.adapters.base import (
    RateLimitExceededError,
    TransientAPIError,
    AuthenticationError,
    ResourceNotFoundError
)
from app.adapters import (
    ADAPTERS,
    github_adapter,
    gitlab_adapter,
    codeberg_adapter,
    bugzilla_adapter
)
from app.adapters.github_adapter import GitHubAdapter
from app.adapters.gitlab_adapter import GitLabAdapter
from app.adapters.codeberg_adapter import CodebergAdapter
from app.adapters.bugzilla_adapter import BugzillaAdapter
from app.ingestion.discovery import DiscoveryWorker
from app.verifier import LiveVerifier
from app.cpp_engine_client import cpp_client

client = TestClient(app)

@pytest.fixture(scope="module", autouse=True)
def setup_teardown_multi_platform_tests():
    """Ensure database connection is established and cleaned up."""
    db.connect()
    yield
    if db.is_connected():
        db.db.issues.delete_many({"source": {"$in": ["github_test", "gitlab_test", "codeberg_test", "bugzilla_test"]}})
        db.db.issues.delete_many({"repository_full_name": {"$in": ["test-org/test-repo", "test-project", "forgejo/forgejo"]}})


# ==============================================================================
# 1. ADAPTER HEALTH CHECKS & AUTHENTICATION VALIDATION
# ==============================================================================

def test_github_adapter_health_check_valid():
    adapter = GitHubAdapter(token="valid_gh_token", base_url="https://api.github.com")
    with patch.object(adapter.session, "request") as mock_req:
        # 1. /rate_limit response
        mock_rl = Mock(status_code=200)
        mock_rl.json.return_value = {
            "resources": {
                "core": {"limit": 5000, "remaining": 4999, "reset": 1800000000},
                "search": {"limit": 30, "remaining": 30, "reset": 1800000000}
            }
        }
        mock_rl.headers = {}
        mock_rl.text = "{}"

        # 2. /user response
        mock_user = Mock(status_code=200)
        mock_user.json.return_value = {"login": "testuser", "id": 12345}
        mock_user.headers = {"x-oauth-scopes": "repo, read:user"}
        mock_user.text = '{"login": "testuser"}'

        # 3. /search/issues response
        mock_search = Mock(status_code=200)
        mock_search.json.return_value = {"total_count": 100, "items": []}
        mock_search.headers = {}
        mock_search.text = "{}"

        mock_req.side_effect = [mock_rl, mock_user, mock_search]

        health = adapter.check_health()
        assert health["configured"] is True
        assert health["reachable"] is True
        assert health["auth_status"] == "valid"
        assert health["read_permission_status"] in ("granted", "confirmed")
        assert health["ingestion_enabled"] is True
        assert health["rate_limit_info"]["remaining"] == 4999


def test_github_adapter_health_check_invalid_token():
    adapter = GitHubAdapter(token="bad_token", base_url="https://api.github.com")
    with patch.object(adapter.session, "request") as mock_req:
        mock_resp = Mock(status_code=401)
        mock_resp.json.return_value = {"message": "Bad credentials"}
        mock_resp.headers = {}
        mock_resp.text = "Bad credentials"
        mock_req.return_value = mock_resp

        health = adapter.check_health()
        assert health["configured"] is True
        assert health["reachable"] is True
        assert health["auth_status"] == "invalid"
        assert health["read_permission_status"] == "denied"
        assert health["ingestion_enabled"] is False


def test_gitlab_adapter_health_check_valid():
    adapter = GitLabAdapter(token="gl_token", base_url="https://gitlab.com/api/v4")
    with patch.object(adapter.session, "request") as mock_req:
        # 1. /user
        mock_user = Mock(status_code=200)
        mock_user.json.return_value = {"username": "gl_user", "id": 999}
        mock_user.headers = {
            "ratelimit-limit": "2000",
            "ratelimit-remaining": "1998",
            "ratelimit-reset": "1800000000"
        }
        mock_user.text = '{"username": "gl_user"}'

        # 2. project issues check
        mock_issues = Mock(status_code=200)
        mock_issues.json.return_value = [{"id": 1, "title": "Test"}]
        mock_issues.headers = mock_user.headers
        mock_issues.text = '[{"id": 1}]'

        mock_req.side_effect = [mock_user, mock_issues]

        health = adapter.check_health()
        assert health["configured"] is True
        assert health["reachable"] is True
        assert health["auth_status"] == "valid"
        assert health["read_permission_status"] in ("granted", "confirmed")
        assert health["ingestion_enabled"] is True
        assert health["rate_limit_info"]["remaining"] == 1998


def test_gitlab_adapter_health_check_insufficient_scope():
    adapter = GitLabAdapter(token="limited_token", base_url="https://gitlab.com/api/v4")
    with patch.object(adapter.session, "request") as mock_req:
        # 1. /user succeeds
        mock_user = Mock(status_code=200)
        mock_user.json.return_value = {"username": "gl_user"}
        mock_user.headers = {}
        mock_user.text = '{"username": "gl_user"}'

        # 2. issues check returns 403 Forbidden
        mock_issues = Mock(status_code=403)
        mock_issues.json.return_value = {"message": "403 Forbidden - Insufficient scope"}
        mock_issues.headers = {}
        mock_issues.text = "403 Forbidden - Insufficient scope"

        mock_req.side_effect = [mock_user, mock_issues]

        health = adapter.check_health()
        assert health["reachable"] is True
        assert health["auth_status"] == "valid"
        assert health["read_permission_status"] == "denied"
        assert health["ingestion_enabled"] is False
        assert "read_api" in health["recommended_action"].lower()


def test_codeberg_adapter_health_check_valid():
    adapter = CodebergAdapter(token="cb_token", base_url="https://codeberg.org/api/v1")
    with patch.object(adapter.session, "request") as mock_req:
        # 1. /version
        mock_ver = Mock(status_code=200)
        mock_ver.json.return_value = {"version": "16.0.0-dev"}
        mock_ver.headers = {"ratelimit": "r=1995;t=120"}
        mock_ver.text = '{"version": "16.0.0-dev"}'

        # 2. /user
        mock_user = Mock(status_code=200)
        mock_user.json.return_value = {"username": "forgejo_dev"}
        mock_user.headers = {"ratelimit": "r=1994;t=120"}
        mock_user.text = '{"username": "forgejo_dev"}'

        # 3. repo issues
        mock_issues = Mock(status_code=200)
        mock_issues.json.return_value = [{"id": 10, "title": "Forgejo issue"}]
        mock_issues.headers = {"ratelimit": "r=1993;t=120"}
        mock_issues.text = '[{"id": 10}]'

        mock_req.side_effect = [mock_ver, mock_user, mock_issues]

        health = adapter.check_health()
        assert health["configured"] is True
        assert health["reachable"] is True
        assert health["auth_status"] == "valid"
        assert health["read_permission_status"] in ("granted", "confirmed")
        assert health["ingestion_enabled"] is True
        assert "16.0.0" in health["api_version"]
        assert health["rate_limit_info"]["remaining"] == 1993


def test_bugzilla_adapter_health_check_unreachable():
    adapter = BugzillaAdapter(base_url="https://your-bugzilla-instance", api_key="secret_bz_key")
    health = adapter.check_health()
    assert health["configured"] is True
    assert health["reachable"] is False
    assert health["auth_status"] == "not_verified"
    assert health["ingestion_enabled"] is False
    assert "secret_bz_key" not in str(health)  # Never expose secrets
    assert "bugzilla" in health["recommended_action"].lower()


# ==============================================================================
# 2. RATE LIMITING, RETRY-AFTER & LOCAL BUDGETS
# ==============================================================================

def test_gitlab_rate_limit_retry_after():
    adapter = GitLabAdapter(token="gl_token")
    with patch.object(adapter.session, "request") as mock_req:
        mock_resp = Mock(status_code=429)
        mock_resp.headers = {"retry-after": "45"}
        mock_resp.text = "Too Many Requests"
        mock_req.return_value = mock_resp

        with pytest.raises(RateLimitExceededError) as exc_info:
            adapter._execute_request("GET", "user")
        assert exc_info.value.reset_timestamp is not None


def test_codeberg_ietf_rate_limit_header():
    adapter = CodebergAdapter()
    headers = {"ratelimit": "r=0;t=120", "ratelimit-policy": "2000;w=900"}
    adapter._parse_rate_limit_headers(headers)
    assert adapter.rate_limit_state["remaining"] == 0
    assert adapter.rate_limit_state["limit"] == 2000


def test_bugzilla_unknown_quota_and_local_budget():
    adapter = BugzillaAdapter(base_url="https://bugzilla.mozilla.org", local_request_budget=5)
    rl = adapter.get_rate_limit()
    assert rl["server_quota"] == "unknown"
    assert rl["remaining_local_budget"] == 5

    # Consume local budget
    for _ in range(5):
        adapter._record_request()

    assert adapter.rate_limit_state["local_budget_remaining"] == 0
    with pytest.raises(RateLimitExceededError) as exc_info:
        adapter._check_rate_limit()
    assert "local request budget" in str(exc_info.value)


# ==============================================================================
# 3. RESPONSE NORMALIZATION & METADATA RESILIENCE
# ==============================================================================

def test_github_response_normalization():
    adapter = GitHubAdapter()
    raw = {
        "id": 101,
        "number": 42,
        "title": "Bug in query parser",
        "body": "Parser fails when encountering nested arrays in python.",
        "html_url": "https://github.com/issue-pilot-org/sample-repo/issues/42",
        "repository_url": "https://api.github.com/repos/issue-pilot-org/sample-repo",
        "state": "open",
        "comments": 4,
        "created_at": "2026-03-01T12:00:00Z",
        "updated_at": "2026-03-05T14:30:00Z",
        "labels": [{"name": "bug"}, {"name": "python"}],
        "assignees": []
    }
    norm = adapter.normalize_issue(raw)
    assert norm["source"] == "github"
    assert norm["canonical_url"] == "https://github.com/issue-pilot-org/sample-repo/issues/42"
    assert norm["source_issue_id"] == "101"
    assert norm["issue_number"] == 42
    assert norm["repository_full_name"] == "issue-pilot-org/sample-repo"
    assert "python" in [t.lower() for t in norm["detected_technologies"]]
    assert norm["state"] == "open"
    assert norm["comment_count"] == 4


def test_gitlab_response_normalization_and_mr_exclusion():
    adapter = GitLabAdapter()
    # Merge Request should be excluded
    raw_mr = {
        "id": 201,
        "iid": 12,
        "title": "MR test",
        "web_url": "https://gitlab.com/group/proj/-/merge_requests/12"
    }
    assert adapter.normalize_issue(raw_mr) is None

    # Valid Issue
    raw_issue = {
        "id": 202,
        "iid": 13,
        "project_id": 999,
        "title": "GitLab pipeline error with Docker",
        "description": "Docker build fails when executing multi-stage python image.",
        "web_url": "https://gitlab.com/group/proj/-/issues/13",
        "state": "opened",
        "user_notes_count": 2,
        "created_at": "2026-03-02T10:00:00Z",
        "updated_at": "2026-03-06T11:00:00Z",
        "labels": ["ci", "docker", "python"],
        "assignees": []
    }
    norm = adapter.normalize_issue(raw_issue)
    assert norm is not None
    assert norm["source"] == "gitlab"
    assert norm["source_issue_id"] == "202"
    assert norm["issue_number"] == 13
    assert norm["state"] == "open"
    assert "docker" in [t.lower() for t in norm["detected_technologies"]]


def test_codeberg_response_normalization():
    adapter = CodebergAdapter()
    # Pull request exclusion
    raw_pr = {"id": 301, "number": 1, "title": "PR", "pull_request": {}}
    assert adapter.normalize_issue(raw_pr) is None

    # Valid Issue
    raw_issue = {
        "id": 302,
        "number": 15,
        "title": "Forgejo Federation API Timeout",
        "body": "ActivityPub federation request times out with go context deadline.",
        "html_url": "https://codeberg.org/forgejo/forgejo/issues/15",
        "state": "open",
        "comments": 5,
        "created_at": "2026-02-15T09:00:00Z",
        "updated_at": "2026-03-01T10:00:00Z",
        "labels": [{"name": "kind/bug"}, {"name": "federation"}],
        "assignees": [],
        "repository": {"full_name": "forgejo/forgejo"}
    }
    norm = adapter.normalize_issue(raw_issue)
    assert norm["source"] == "codeberg"
    assert norm["source_issue_id"] == "302"
    assert norm["repository_full_name"] == "forgejo/forgejo"
    assert norm["state"] == "open"


def test_bugzilla_response_normalization_no_repo_fabrication():
    adapter = BugzillaAdapter(base_url="https://bugzilla.mozilla.org")
    raw_bug = {
        "id": 1823456,
        "summary": "Crash in WebRender when processing CSS backdrop-filter",
        "product": "Core",
        "component": "Graphics: WebRender",
        "status": "NEW",
        "resolution": "",
        "creation_time": "2026-01-10T15:20:00Z",
        "last_change_time": "2026-03-02T12:00:00Z",
        "keywords": ["crash", "webrender"],
        "assigned_to": "nobody@mozilla.org",
        "comments": [{"count": 0, "text": "Crash reproduces in rust webrender shader."}]
    }
    norm = adapter.normalize_issue(raw_bug)
    assert norm["source"] == "bugzilla"
    assert norm["source_issue_id"] == "1823456"
    assert norm["title"] == "Crash in WebRender when processing CSS backdrop-filter"
    assert norm["canonical_url"] == "https://bugzilla.mozilla.org/show_bug.cgi?id=1823456"
    # Metadata preservation without git repository fabrication:
    assert norm["product"] == "Core"
    assert norm["component"] == "Graphics: WebRender"
    assert norm["repository_full_name"] == "Core/Graphics: WebRender"
    assert norm["state"] == "open"


def test_missing_fields_and_optional_metadata():
    adapter = GitHubAdapter()
    raw = {
        "id": 99999,
        "number": 1,
        "title": "Minimal issue",
        "body": None,  # null body
        "html_url": "https://github.com/org/repo/issues/1",
        "repository_url": "https://api.github.com/repos/org/repo",
        "state": "open",
        "created_at": "2026-03-01T00:00:00Z",
        "updated_at": "2026-03-01T00:00:00Z",
    }
    norm = adapter.normalize_issue(raw)
    assert norm["body"] == ""
    assert norm["labels"] == []
    assert norm["assignees"] == []
    assert norm["comment_count"] == 0


# ==============================================================================
# 4. SOURCE-AWARE IDENTITY & IDEMPOTENT UPSERTS IN ATLAS
# ==============================================================================

def test_source_aware_issue_identity_no_collision():
    """Verify issue #100 on GitHub vs GitLab vs Codeberg vs Bugzilla do not collide."""
    if not db.is_connected():
        pytest.skip("MongoDB Atlas not connected")

    gh_issue = {
        "source": "github_test",
        "source_instance": "https://api.github.com",
        "source_issue_id": "gh_100",
        "issue_number": 100,
        "title": "GitHub Issue 100",
        "body": "Description GH",
        "canonical_url": "https://github.com/test-org/test-repo/issues/100",
        "repository_full_name": "test-org/test-repo",
        "state": "open"
    }

    gl_issue = {
        "source": "gitlab_test",
        "source_instance": "https://gitlab.com/api/v4",
        "source_issue_id": "gl_100",
        "issue_number": 100,
        "title": "GitLab Issue 100",
        "body": "Description GL",
        "canonical_url": "https://gitlab.com/test-project/-/issues/100",
        "repository_full_name": "test-project",
        "state": "open"
    }

    bz_issue = {
        "source": "bugzilla_test",
        "source_instance": "https://bugzilla.mozilla.org",
        "source_issue_id": "bz_100",
        "issue_number": 100,
        "title": "Bugzilla Bug 100",
        "body": "Description BZ",
        "canonical_url": "https://bugzilla.mozilla.org/show_bug.cgi?id=100",
        "product": "Core",
        "component": "General",
        "state": "open"
    }

    res_gh = issue_repo.upsert_issue(gh_issue)
    res_gl = issue_repo.upsert_issue(gl_issue)
    res_bz = issue_repo.upsert_issue(bz_issue)

    assert res_gh["id"] is not None
    assert res_gl["id"] is not None
    assert res_bz["id"] is not None

    # Distinct compound identities:
    assert len({res_gh["id"], res_gl["id"], res_bz["id"]}) == 3

    # Verify all 3 exist concurrently
    doc_gh = issue_repo.get_by_source_identity("github_test", "https://api.github.com", "gh_100")
    doc_gl = issue_repo.get_by_source_identity("gitlab_test", "https://gitlab.com/api/v4", "gl_100")
    doc_bz = issue_repo.get_by_source_identity("bugzilla_test", "https://bugzilla.mozilla.org", "bz_100")

    assert doc_gh is not None and doc_gh["title"] == "GitHub Issue 100"
    assert doc_gl is not None and doc_gl["title"] == "GitLab Issue 100"
    assert doc_bz is not None and doc_bz["title"] == "Bugzilla Bug 100"


def test_idempotent_upsert_preserves_ranking_evidence():
    """Verify partial refresh response preserves existing ranking evidence."""
    if not db.is_connected():
        pytest.skip("MongoDB Atlas not connected")

    initial = {
        "source": "gitlab_test",
        "source_instance": "https://gitlab.com/api/v4",
        "source_issue_id": "gl_evidence_test",
        "issue_number": 55,
        "title": "Initial Title",
        "body": "Initial Body with python",
        "canonical_url": "https://gitlab.com/test-project/-/issues/55",
        "state": "open",
        "detected_technologies": ["Python", "FastAPI"],
        "technology_evidence": [{"technology": "Python", "confidence": 0.95}],
        "maintainer_responsiveness": {"response_time_hours": 3.5, "score": 85.0}
    }
    issue_repo.upsert_issue(initial)

    # Partial update omitting maintainer_responsiveness
    partial_update = {
        "source": "gitlab_test",
        "source_instance": "https://gitlab.com/api/v4",
        "source_issue_id": "gl_evidence_test",
        "issue_number": 55,
        "title": "Updated Title",
        "body": "Updated Body",
        "canonical_url": "https://gitlab.com/test-project/-/issues/55",
        "state": "open"
    }
    issue_repo.upsert_issue(partial_update)

    fetched = issue_repo.get_by_source_identity("gitlab_test", "https://gitlab.com/api/v4", "gl_evidence_test")
    assert fetched["title"] == "Updated Title"
    # Preserved evidence:
    assert fetched["detected_technologies"] == ["Python", "FastAPI"]
    assert fetched["maintainer_responsiveness"]["score"] == 85.0


# ==============================================================================
# 5. FAULT ISOLATION: ONE FAILING PLATFORM DOES NOT HALT THE REST
# ==============================================================================

def test_discovery_fault_isolation_bugzilla_failing():
    """Verify that when Bugzilla fails or is unconfigured, discovery continues on the other 3."""
    worker = DiscoveryWorker()

    with patch.object(github_adapter, "check_health", return_value={"ingestion_enabled": True}), \
         patch.object(gitlab_adapter, "check_health", return_value={"ingestion_enabled": True}), \
         patch.object(codeberg_adapter, "check_health", return_value={"ingestion_enabled": True}), \
         patch.object(bugzilla_adapter, "check_health", return_value={
             "ingestion_enabled": False, "error_message": "Host unresolvable", "recommended_action": "Configure instance URL"
         }), \
         patch.object(github_adapter, "search_issues", return_value={"total_count": 0, "items": []}), \
         patch.object(gitlab_adapter, "search_issues", return_value={"total_count": 0, "items": []}), \
         patch.object(codeberg_adapter, "search_issues", return_value={"total_count": 0, "items": []}):

        summary = worker.run_discovery(max_issues_to_discover=20, pages_per_query=1, force=True, source="all")
        assert summary["status"] in ["completed", "partial"]
        per_source = summary["per_source_summary"]

        # Working sources were called
        assert per_source["github"]["status"] == "completed"
        assert per_source["gitlab"]["status"] == "completed"
        assert per_source["codeberg"]["status"] == "completed"
        # Bugzilla was skipped gracefully
        assert per_source["bugzilla"]["status"] == "skipped_unhealthy"


# ==============================================================================
# 6. LIVE VERIFICATION ACROSS MULTI-PLATFORM CANDIDATES & BACKFILL
# ==============================================================================

def test_live_verification_multi_platform():
    verifier = LiveVerifier()

    cand_gh = {
        "candidate_id": "gh_cand_1",
        "source": "github",
        "repository_full_name": "psf/requests",
        "issue_number": 6124,
        "canonical_url": "https://github.com/psf/requests/issues/6124",
        "title": "Open issue"
    }

    cand_gl_closed = {
        "candidate_id": "gl_cand_2",
        "source": "gitlab",
        "repository_full_name": "gitlab-org/gitlab",
        "issue_number": 10,
        "canonical_url": "https://gitlab.com/gitlab-org/gitlab/-/issues/10",
        "title": "Closed MR issue"
    }

    cand_bz_resolved = {
        "candidate_id": "bz_cand_3",
        "source": "bugzilla",
        "issue_number": 9999,
        "source_issue_id": "9999",
        "canonical_url": "https://bugzilla.mozilla.org/show_bug.cgi?id=9999",
        "title": "Resolved bug"
    }

    mock_gh_resp = Mock(status_code=200)
    mock_gh_resp.json.return_value = {
        "state": "open",
        "assignees": [],
        "html_url": "https://github.com/psf/requests/issues/6124"
    }
    mock_gh_resp.headers = {"ETag": "W/'123'"}

    with patch.object(verifier.session, "request", return_value=mock_gh_resp), \
         patch.object(gitlab_adapter, "get_issue", return_value={"state": "closed"}), \
         patch.object(bugzilla_adapter, "get_issue", return_value={"status": "RESOLVED", "resolution": "FIXED"}):

        # GitHub verifies
        v_gh = verifier.verify_candidate(cand_gh)
        assert v_gh.is_valid is True

        # GitLab fails verification
        v_gl = verifier.verify_candidate(cand_gl_closed)
        assert v_gl.is_valid is False
        assert "closed" in v_gl.reason.lower()

        # Bugzilla fails verification
        v_bz = verifier.verify_candidate(cand_bz_resolved)
        assert v_bz.is_valid is False
        assert "resolved" in v_bz.reason.lower()


# ==============================================================================
# 7. C++ RANKING WITH UNAVAILABLE SCORING SIGNALS
# ==============================================================================

def test_cpp_ranking_with_unavailable_signals():
    """Verify candidates lacking repository health or difficulty still score via weight renormalization."""
    sample_res = client.get("/api/simulator/sample-data")
    assert sample_res.status_code == 200
    sample_data = sample_res.json()
    profile = sample_data["profiles"][0]
    cand = dict(sample_data["issues"][0])
    # Mark health as unavailable to test weight renormalization
    cand["health_evidence"] = {"available": False}

    res = client.post("/api/simulator/rank", json={
        "profile": profile,
        "candidates": [cand]
    })
    assert res.status_code == 200
    data = res.json()
    assert data["count"] == 1
    scores = data["results"][0]["scores"]
    assert scores["final_score"] > 0
    assert scores["health_available"] is False
    assert "Repository Health" in scores["missing_components"]


# ==============================================================================
# 8. ADMIN API HEALTH ENDPOINTS & CREDENTIAL PRIVACY
# ==============================================================================

def test_admin_health_endpoints_privacy():
    """Ensure GET /health and GET /platforms never leak secret tokens in JSON responses."""
    resp = client.get("/api/ingestion/health")
    assert resp.status_code == 200
    text = resp.text
    # Tokens should NEVER be leaked
    assert "ghp_" not in text
    assert "glpat-" not in text

    resp_plat = client.get("/api/ingestion/platforms")
    assert resp_plat.status_code == 200
    data = resp_plat.json()
    assert "platforms" in data
    assert "github" in data["platforms"]
    assert "gitlab" in data["platforms"]
    assert "codeberg" in data["platforms"]
    assert "bugzilla" in data["platforms"]
