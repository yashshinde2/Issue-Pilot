import os
import subprocess
import pytest
from datetime import datetime, timezone
from pymongo.errors import DuplicateKeyError
from fastapi.testclient import TestClient

from app.main import app
from app.config import settings
from app.database import db, Database
from app.crud import (
    issue_repo,
    user_repo,
    rec_repo,
    feedback_repo,
    DuplicateRecordError,
    DatabaseConnectionError,
    InvalidDataError,
    IssueRepository,
    UserRepository
)
from app.models import ContributorProfile, ContributorSkill, IssueRecord, FeedbackEvent

client = TestClient(app)

# Use dedicated prefixes for disposable test records to ensure clean isolation
TEST_SOURCE = "test_github"
TEST_REPO = "issue-pilot-test-org/disposable-sandbox"
TEST_USER_ID = "test-contributor-persist-user-999"

@pytest.fixture(scope="module", autouse=True)
def setup_teardown_atlas():
    """Ensure database is connected and clean up disposable test records."""
    db.connect()
    assert db.is_connected(), "MongoDB Atlas must be connected to run persistence integration tests."
    # Clean any stale test records from previous runs
    db.db.issues.delete_many({"repository_full_name": TEST_REPO})
    db.db.users.delete_many({"user_id": {"$regex": "^test-contributor-persist"}})
    db.db.recommendations.delete_many({"user_id": {"$regex": "^test-contributor-persist"}})
    db.db.feedback.delete_many({"user_id": {"$regex": "^test-contributor-persist"}})
    yield
    # Cleanup after test suite
    if db.is_connected():
        db.db.issues.delete_many({"repository_full_name": TEST_REPO})
        db.db.users.delete_many({"user_id": {"$regex": "^test-contributor-persist"}})
        db.db.recommendations.delete_many({"user_id": {"$regex": "^test-contributor-persist"}})
        db.db.feedback.delete_many({"user_id": {"$regex": "^test-contributor-persist"}})
        db.disconnect()


# ==============================================================================
# 1. New issue can be inserted and retrieved
# ==============================================================================
def test_1_new_issue_can_be_inserted_and_retrieved():
    issue_data = {
        "id": "test_issue_101",
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "repository_owner": "issue-pilot-test-org",
        "repository_name": "disposable-sandbox",
        "issue_number": 101,
        "title": "Add Atlas connection pool support",
        "body": "Implement connection pooling and socket timeouts for high concurrency.",
        "issue_url": f"https://github.com/{TEST_REPO}/issues/101",
        "labels": ["enhancement", "database"],
        "state": "open",
        "is_assigned": False,
        "detected_technologies": ["Python", "MongoDB"],
        "technologies": [
            {"technology": "Python", "raw_relevance": 4.0},
            {"technology": "MongoDB", "raw_relevance": 5.0}
        ],
        "difficulty_evidence": {
            "scope_change_size": 45.0,
            "technical_complexity": 55.0,
            "historical_effort": 50.0,
            "dependency_arch_impact": 60.0,
            "coordination_complexity": 40.0
        }
    }

    # Insert via repository
    saved = issue_repo.upsert_issue(issue_data)
    assert saved is not None
    assert saved["issue_number"] == 101
    assert saved["repository_full_name"] == TEST_REPO

    # Retrieve by ID
    by_id = issue_repo.get_issue_by_id("test_issue_101")
    assert by_id is not None
    assert by_id["title"] == "Add Atlas connection pool support"
    assert "MongoDB" in by_id["detected_technologies"]

    # Retrieve by Natural Key
    by_nat = issue_repo.get_issue_by_natural_key(TEST_SOURCE, TEST_REPO, 101)
    assert by_nat is not None
    assert by_nat["id"] == "test_issue_101"


# ==============================================================================
# 2. Upserting the same issue twice produces one document and updates fields
# ==============================================================================
def test_2_upsert_same_issue_twice_is_idempotent_and_preserves_fields():
    issue_num = 102
    initial_data = {
        "id": "test_issue_102",
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "issue_number": issue_num,
        "title": "Initial Issue Title",
        "body": "Initial long description",
        "issue_url": f"https://github.com/{TEST_REPO}/issues/{issue_num}",
        "state": "open",
        "is_assigned": False,
        "comment_count": 2,
        "custom_metadata_tag": "preserve_this_value"
    }

    # First insertion
    res1 = issue_repo.upsert_issue(initial_data, preserve_unsupplied=True)
    assert res1["title"] == "Initial Issue Title"

    # Count matching documents
    count_after_first = db.db.issues.count_documents({
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "issue_number": issue_num
    })
    assert count_after_first == 1

    # Second upsert: partial update with new title and comment_count, unsupplied body and metadata
    updated_data = {
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "issue_number": issue_num,
        "title": "Updated Issue Title - Second Pass",
        "comment_count": 7
    }
    res2 = issue_repo.upsert_issue(updated_data, preserve_unsupplied=True)

    # Must still produce exactly ONE document
    count_after_second = db.db.issues.count_documents({
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "issue_number": issue_num
    })
    assert count_after_second == 1, "Upsert created a duplicate document instead of updating existing!"

    # Field verification: updated fields changed, unsupplied fields preserved
    doc = issue_repo.get_issue_by_natural_key(TEST_SOURCE, TEST_REPO, issue_num)
    assert doc["title"] == "Updated Issue Title - Second Pass"
    assert doc["comment_count"] == 7
    assert doc["body"] == "Initial long description", "Unsupplied field 'body' was erased by partial update!"
    assert doc["custom_metadata_tag"] == "preserve_this_value", "Unsupplied field 'custom_metadata_tag' was erased!"


# ==============================================================================
# 3. User profile creation, retrieval, and update
# ==============================================================================
def test_3_user_profile_crud_operations():
    profile = ContributorProfile(
        user_id=TEST_USER_ID,
        capability=72.0,
        skills={
            "Python": ContributorSkill(technology="Python", score=88.0, confidence=0.9),
            "MongoDB": ContributorSkill(technology="MongoDB", score=80.0, confidence=0.85)
        },
        preferences={"preferred_technologies": ["Python", "FastAPI"], "max_difficulty": 80.0}
    )

    # 1. Create
    created = user_repo.create_user(profile)
    assert created["user_id"] == TEST_USER_ID
    assert created["capability"] == 72.0
    assert "created_at" in created

    # 2. Retrieve
    retrieved = user_repo.get_user(TEST_USER_ID)
    assert retrieved is not None
    assert retrieved["skills"]["Python"]["score"] == 88.0
    assert retrieved["preferences"]["preferred_technologies"] == ["Python", "FastAPI"]

    # 3. Update (preserving existing skills)
    updated = user_repo.update_user(TEST_USER_ID, {
        "capability": 78.5,
        "completed_count": 5
    })
    assert updated["capability"] == 78.5
    assert updated["completed_count"] == 5
    assert "Python" in updated["skills"], "Skills were lost during partial update!"


# ==============================================================================
# 4. Issue queries return expected filtered records
# ==============================================================================
def test_4_filtered_issue_queries():
    # Insert candidate variations
    issues = [
        {
            "id": "filter_issue_1",
            "source": TEST_SOURCE,
            "repository_full_name": TEST_REPO,
            "issue_number": 201,
            "title": "Python Open Unassigned Easy",
            "issue_url": f"https://github.com/{TEST_REPO}/issues/201",
            "state": "open",
            "is_assigned": False,
            "detected_technologies": ["Python"],
            "difficulty_evidence": {"technical_complexity": 30.0}
        },
        {
            "id": "filter_issue_2",
            "source": TEST_SOURCE,
            "repository_full_name": TEST_REPO,
            "issue_number": 202,
            "title": "Cpp Open Unassigned Hard",
            "issue_url": f"https://github.com/{TEST_REPO}/issues/202",
            "state": "open",
            "is_assigned": False,
            "detected_technologies": ["C++"],
            "difficulty_evidence": {"technical_complexity": 85.0}
        },
        {
            "id": "filter_issue_3",
            "source": TEST_SOURCE,
            "repository_full_name": TEST_REPO,
            "issue_number": 203,
            "title": "Python Closed Issue",
            "issue_url": f"https://github.com/{TEST_REPO}/issues/203",
            "state": "closed",
            "is_assigned": False,
            "detected_technologies": ["Python"],
            "difficulty_evidence": {"technical_complexity": 30.0}
        },
        {
            "id": "filter_issue_4",
            "source": TEST_SOURCE,
            "repository_full_name": TEST_REPO,
            "issue_number": 204,
            "title": "Python Assigned Issue",
            "issue_url": f"https://github.com/{TEST_REPO}/issues/204",
            "state": "open",
            "is_assigned": True,
            "detected_technologies": ["Python"],
            "difficulty_evidence": {"technical_complexity": 30.0}
        }
    ]

    for item in issues:
        issue_repo.upsert_issue(item)

    # Filter 1: State=open and is_assigned=False
    open_unassigned = issue_repo.query_issues(state="open", is_assigned=False, limit=100)
    open_ids = [d["id"] for d in open_unassigned if d["repository_full_name"] == TEST_REPO]
    assert "filter_issue_1" in open_ids
    assert "filter_issue_2" in open_ids
    assert "filter_issue_3" not in open_ids, "Closed issue returned in open query!"
    assert "filter_issue_4" not in open_ids, "Assigned issue returned in unassigned query!"

    # Filter 2: Technology=Python
    python_only = issue_repo.query_issues(state="open", is_assigned=False, technology="Python", limit=100)
    py_ids = [d["id"] for d in python_only if d["repository_full_name"] == TEST_REPO]
    assert "filter_issue_1" in py_ids
    assert "filter_issue_2" not in py_ids

    # Filter 3: Difficulty >= 80.0
    hard_only = issue_repo.query_issues(state="open", is_assigned=False, min_difficulty=80.0, limit=100)
    hard_ids = [d["id"] for d in hard_only if d["repository_full_name"] == TEST_REPO]
    assert "filter_issue_2" in hard_ids
    assert "filter_issue_1" not in hard_ids


# ==============================================================================
# 5. Recommendations and feedback persist correctly
# ==============================================================================
def test_5_recommendations_and_feedback_persistence():
    user_id = f"{TEST_USER_ID}-rec-fb"
    issue_id = "test_issue_101"

    # 1. Recommendation snapshot persistence
    sample_rec = [{
        "id": issue_id,
        "issue_number": 101,
        "repository_full_name": TEST_REPO,
        "rank": 1,
        "final_score": 88.5,
        "scores": {"final_score": 88.5, "skill_match": 90.0, "difficulty_match": 85.0},
        "effective_weights": {"skill_weight": 0.35, "difficulty_weight": 0.25},
        "weighted_contributions": {"skill": 31.5, "difficulty": 21.25},
        "missing_components": [],
        "explanation": "High skill and difficulty alignment."
    }]

    inserted_count = rec_repo.save_recommendations(user_id, sample_rec, algorithm_version="1.0.0-phase1")
    assert inserted_count == 1

    snapshots = rec_repo.get_user_recommendations(user_id, limit=5)
    assert len(snapshots) >= 1
    assert snapshots[0]["final_score"] == 88.5
    assert snapshots[0]["algorithm_version"] == "1.0.0-phase1"

    # 2. Feedback event recording and atomic user stats update
    # First create user
    user_repo.upsert_user(ContributorProfile(user_id=user_id, capability=70.0))

    feedback_event = FeedbackEvent(
        user_id=user_id,
        issue_id=issue_id,
        event_type="accepted",
        recommendation_snapshot={"final_score": 88.5}
    )
    fb_record = feedback_repo.record_feedback(feedback_event)
    assert fb_record["event_type"] == "accepted"

    # Verify feedback query
    user_fb = feedback_repo.get_user_feedback(user_id)
    assert user_fb["counts"]["accepted"] >= 1
    assert any(e["issue_id"] == issue_id for e in user_fb["events"])

    # Verify user profile counter was incremented
    user_after_fb = user_repo.get_user(user_id)
    assert user_after_fb["accepted_count"] >= 1
    assert user_after_fb["has_feedback_history"] is True


# ==============================================================================
# 6. Unique indexes prevent duplicate issue identities
# ==============================================================================
def test_6_unique_compound_index_enforces_identity_uniqueness():
    issue_num = 301
    doc1 = {
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "issue_number": issue_num,
        "title": "Original Document",
        "issue_url": f"https://github.com/{TEST_REPO}/issues/{issue_num}"
    }

    # Clean prior test state
    db.db.issues.delete_many({"source": TEST_SOURCE, "repository_full_name": TEST_REPO, "issue_number": issue_num})

    # Direct insert of first document
    db.db.issues.insert_one(doc1)

    # Attempting to insert a SECOND document with identical compound key must raise DuplicateKeyError
    doc2 = {
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "issue_number": issue_num,
        "title": "Duplicate Document Attempt"
    }
    with pytest.raises(DuplicateKeyError):
        db.db.issues.insert_one(doc2)

    # Test user unique index: duplicate user_id must raise DuplicateRecordError
    dup_user_id = f"{TEST_USER_ID}-unique-dup"
    db.db.users.delete_many({"user_id": dup_user_id})
    user_repo.create_user(ContributorProfile(user_id=dup_user_id))
    with pytest.raises(DuplicateRecordError):
        user_repo.create_user(ContributorProfile(user_id=dup_user_id))


# ==============================================================================
# 7. Data remains available after application is stopped and restarted
# ==============================================================================
def test_7_data_persists_across_application_restart():
    restart_user_id = f"{TEST_USER_ID}-restart-check"
    restart_issue_num = 401

    # 1. Insert records on current active connection
    user_repo.upsert_user(ContributorProfile(
        user_id=restart_user_id,
        capability=83.0,
        preferences={"restarted": True}
    ))
    issue_repo.upsert_issue({
        "id": "restart_issue_401",
        "source": TEST_SOURCE,
        "repository_full_name": TEST_REPO,
        "issue_number": restart_issue_num,
        "title": "Persistence across restart test issue",
        "issue_url": f"https://github.com/{TEST_REPO}/issues/{restart_issue_num}",
        "state": "open",
        "is_assigned": False
    })

    # 2. Simulate Application Shutdown: disconnect current database client
    db.disconnect()
    assert db.client is None
    assert db.db is None

    # 3. Simulate Application Startup / Reboot: instantiate new Database connection
    fresh_db = Database()
    connected = fresh_db.connect(settings.mongodb_uri, settings.mongodb_database)
    assert connected is True, "Failed to connect to Atlas after simulated restart!"

    # 4. Verify data survived application reboot
    fresh_user_repo = UserRepository(fresh_db.db)
    fresh_issue_repo = IssueRepository(fresh_db.db)

    persisted_user = fresh_user_repo.get_user(restart_user_id)
    assert persisted_user is not None
    assert persisted_user["capability"] == 83.0
    assert persisted_user["preferences"]["restarted"] is True

    persisted_issue = fresh_issue_repo.get_issue_by_natural_key(TEST_SOURCE, TEST_REPO, restart_issue_num)
    assert persisted_issue is not None
    assert persisted_issue["title"] == "Persistence across restart test issue"

    # Restore global db instance
    db.connect()


# ==============================================================================
# 8. Application reports clear error when MongoDB is unavailable
# ==============================================================================
def test_8_application_reports_clear_error_when_mongodb_unavailable():
    # 1. Database client connection failure reporting
    unreachable_db = Database()
    # Unroutable local port with 500ms timeout
    connected = unreachable_db.connect(
        uri="mongodb://127.0.0.1:29999/?serverSelectionTimeoutMS=500",
        database_name="unreachable_test_db"
    )
    assert connected is False
    assert unreachable_db.db is None
    assert unreachable_db.is_connected() is False

    # 2. API live mode clear error response (HTTP 503 instead of silent fake data)
    # Disconnect live database to simulate downtime
    db.disconnect()
    try:
        res = client.post("/api/recommendations/personalized", json={
            "user_id": "test-contributor",
            "prefer_live_atlas": True,
            "demo_mode": False
        })
        assert res.status_code == 503
        data = res.json()
        assert "detail" in data
        assert "unavailable" in data["detail"].lower()
    finally:
        # Reconnect for remaining tests
        db.connect()


# ==============================================================================
# 9. Existing C++ ranking tests and demo mode still pass
# ==============================================================================
def test_9_existing_cpp_ranking_tests_and_demo_mode_pass():
    # 1. Authoritative C++ ranking engine unit tests executable
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    cpp_test_exe = os.path.join(base_dir, "ranking_engine", "build", "test_ranking.exe")
    assert os.path.exists(cpp_test_exe), f"C++ test executable not found at {cpp_test_exe}"

    result = subprocess.run([cpp_test_exe], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
    assert result.returncode == 0
    assert "ALL 10 UNIT TESTS PASSED SUCCESSFULLY!" in result.stdout

    # 2. Backend Demo Mode operates cleanly without live Atlas candidates
    demo_res = client.post("/api/recommendations/personalized", json={
        "profile": {
            "user_id": "demo-user",
            "capability": 70.0,
            "skills": {"Python": {"technology": "Python", "score": 85.0, "confidence": 0.9}}
        },
        "target_count": 5,
        "prefer_live_atlas": False,
        "demo_mode": True
    })
    assert demo_res.status_code == 200
    data = demo_res.json()
    assert data["data_source"] == "sample_demonstration_candidates"
    assert len(data["verified_recommendations"]) > 0
    for rec in data["verified_recommendations"]:
        assert rec["verified"] is True
        assert "scores" in rec
        assert rec["scores"]["final_score"] >= 0.0
