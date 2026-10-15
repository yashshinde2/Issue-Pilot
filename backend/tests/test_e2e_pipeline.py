import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.cpp_engine_client import cpp_client

client = TestClient(app)

def test_health_endpoint():
    res = client.get("/api/health")
    assert res.status_code == 200
    data = res.json()
    assert "cpp_ranking_engine_available" in data
    assert data["cpp_ranking_engine_available"] is True
    assert data["algorithm_version"] == "1.0.0-phase1"

def test_cpp_engine_what_if_experiments():
    res = client.get("/api/simulator/experiments")
    assert res.status_code == 200
    data = res.json()
    experiments = data.get("experiments", [])
    assert len(experiments) == 7
    # All 7 experiments must be verified by C++ calculations
    for exp in experiments:
        assert exp["verified"] is True, f"Experiment {exp['name']} failed verification"

def test_cpp_engine_eval_metrics():
    eval_res = cpp_client.execute_eval()
    assert eval_res["total_evaluated"] == 10
    assert eval_res["precision_at_5"] >= 0.70
    assert eval_res["ndcg_at_5"] >= 0.85
    assert eval_res["ndcg_at_5"] > eval_res["baseline_ndcg_at_5"]

def test_simulator_ranking_calculation():
    sample_res = client.get("/api/simulator/sample-data")
    assert sample_res.status_code == 200
    sample_data = sample_res.json()
    profile = sample_data["profiles"][0]
    issues = sample_data["issues"]

    rank_res = client.post("/api/simulator/rank", json={
        "profile": profile,
        "candidates": issues
    })
    assert rank_res.status_code == 200
    data = rank_res.json()
    assert data["count"] == len(issues)
    results = data["results"]
    assert len(results) > 0
    # Strictly descending order
    for i in range(1, len(results)):
        assert results[i]["scores"]["final_score"] <= results[i-1]["scores"]["final_score"]

def test_personalized_recommendation_and_verification():
    sample_res = client.get("/api/simulator/sample-data")
    profile = sample_res.json()["profiles"][0]

    rec_res = client.post("/api/recommendations/personalized", json={
        "profile": profile,
        "target_count": 5,
        "prefer_live_atlas": False
    })
    assert rec_res.status_code == 200
    data = rec_res.json()
    assert "verified_recommendations" in data
    recs = data["verified_recommendations"]
    assert len(recs) <= 5
    for r in recs:
        assert r["verified"] is True

def test_feedback_event_recording():
    fb_res = client.post("/api/feedback", json={
        "user_id": "test-contributor-1",
        "issue_id": "sample-1",
        "event_type": "accepted"
    })
    assert fb_res.status_code == 200
    assert fb_res.json()["status"] == "success"

def test_validation_suite_endpoint():
    val_res = client.post("/api/validation/run")
    assert val_res.status_code == 200
    data = val_res.json()
    assert data["unit_tests"]["passed"] is True
    assert len(data["experiments"]) == 7
    assert data["evaluation_metrics"]["ndcg_at_5"] > 0.80
