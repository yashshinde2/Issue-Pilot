"""
Behavioral and Sensitivity Test Suite (Experiments A to G).
Uses controlled inputs and fixtures to verify authoritative C++ ranking engine behavior:
- Experiment A: Skill Match sensitivity
- Experiment B: Difficulty Match proximity
- Experiment C: Freshness sensitivity
- Experiment D: Feedback sensitivity & cold-start
- Experiment E: Missing data renormalization & insufficient data
- Experiment F: Live verification discard and shortlist backfill
- Experiment G: Deterministic reproducibility across repeated executions
"""
import os
import json
import pytest
from unittest.mock import patch, Mock
import requests

from app.cpp_engine_client import cpp_client
from app.verifier import live_verifier

FIXTURES_PATH = os.path.join(os.path.dirname(__file__), "fixtures", "behavioral_experiments.json")

@pytest.fixture(scope="module")
def fixtures():
    with open(FIXTURES_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def test_experiment_a_skill_match(fixtures):
    """
    Experiment A — Skill Match:
    Change one relevant technology skill while holding all other inputs constant.
    Verify the Skill Match component and final score change according to the formula.
    """
    data = fixtures["experiment_a_skill"]
    profile = dict(data["profile"])
    issue = dict(data["issue"])

    # 1. Initial calculation
    payload_initial = {
        "profile": profile,
        "candidates": [issue]
    }
    res_initial = cpp_client.execute_ranking(payload_initial)
    assert len(res_initial["results"]) == 1
    scores_initial = res_initial["results"][0]["scores"]

    assert abs(scores_initial["skill_match"] - data["expected_initial_skill"]) < 0.01

    # 2. Modify FastAPI skill from 80.0 to 30.0
    profile_modified = dict(profile)
    profile_modified["skills"] = dict(profile["skills"])
    profile_modified["skills"]["FastAPI"] = {"score": data["fastapi_modified_score"], "confidence": 1.0}

    payload_modified = {
        "profile": profile_modified,
        "candidates": [issue]
    }
    res_modified = cpp_client.execute_ranking(payload_modified)
    assert len(res_modified["results"]) == 1
    scores_modified = res_modified["results"][0]["scores"]

    assert abs(scores_modified["skill_match"] - data["expected_modified_skill"]) < 0.01

    # Verify skill delta
    actual_skill_delta = scores_modified["skill_match"] - scores_initial["skill_match"]
    assert abs(actual_skill_delta - data["expected_skill_delta"]) < 0.01

    # Verify final score delta = 0.35 * delta_S
    actual_final_delta = scores_modified["final_score"] - scores_initial["final_score"]
    assert abs(actual_final_delta - data["expected_final_score_delta"]) < 0.02


def test_experiment_b_difficulty_match(fixtures):
    """
    Experiment B — Difficulty Match:
    Change contributor capability or issue difficulty.
    Verify the Difficulty Match component responds as specified:
    D = 100 - |Capability - IssueDifficulty|
    """
    data = fixtures["experiment_b_difficulty"]
    profile = dict(data["profile"])
    issue = dict(data["issue"])

    # 1. Initial capability = 76.0, Issue Difficulty = 70.0 -> D = 100 - |76 - 70| = 94.0
    res_initial = cpp_client.execute_ranking({"profile": profile, "candidates": [issue]})
    d_initial = res_initial["results"][0]["scores"]["difficulty_match"]
    assert abs(d_initial - data["expected_initial_diff_match"]) < 0.01

    # 2. Modified capability = 95.0 -> D = 100 - |95 - 70| = 75.0
    profile_mod = dict(profile, capability=data["modified_capability"])
    res_mod = cpp_client.execute_ranking({"profile": profile_mod, "candidates": [issue]})
    d_mod = res_mod["results"][0]["scores"]["difficulty_match"]
    assert abs(d_mod - data["expected_modified_diff_match"]) < 0.01

    actual_diff_delta = d_mod - d_initial
    assert abs(actual_diff_delta - data["expected_diff_delta"]) < 0.01


def test_experiment_c_freshness(fixtures):
    """
    Experiment C — Freshness:
    Change issue age or last-activity time.
    Verify the freshness score changes according to configured thresholds (0.40*Age + 0.60*Activity).
    """
    data = fixtures["experiment_c_freshness"]
    profile = data["profile"]
    issue_fresh = data["issue_fresh"]
    issue_aged = data["issue_aged"]

    res_fresh = cpp_client.execute_ranking({"profile": profile, "candidates": [issue_fresh]})
    res_aged = cpp_client.execute_ranking({"profile": profile, "candidates": [issue_aged]})

    s_fresh = res_fresh["results"][0]["scores"]["freshness"]
    s_aged = res_aged["results"][0]["scores"]["freshness"]

    assert abs(s_fresh - data["expected_fresh_score"]) < 0.01
    assert abs(s_aged - data["expected_aged_score"]) < 0.01

    # Final score freshness delta: weight is 5% (0.05)
    score_fresh = res_fresh["results"][0]["scores"]["final_score"]
    score_aged = res_aged["results"][0]["scores"]["final_score"]
    delta_score = score_aged - score_fresh
    assert abs(delta_score - data["expected_weighted_freshness_delta"]) < 0.05


def test_experiment_d_feedback(fixtures):
    """
    Experiment D — Feedback:
    Change accepted, rejected, and completed feedback counts.
    Verify personalization changes only when the relevant data is available.
    """
    data = fixtures["experiment_d_feedback"]
    base_prof = dict(data["base_profile"])

    issue = {
        "id": "exp_d_issue",
        "issue_number": 301,
        "title": "Feedback personalization issue",
        "issue_url": "https://github.com/org/repo/issues/301",
        "state": "open",
        "technologies": [{"technology": "Python", "raw_relevance": 1.0}]
    }

    # Initial counts: accepted=10, rejected=10, completed=5
    prof_init = dict(base_prof, **data["counts_initial"])
    res_init = cpp_client.execute_ranking({"profile": prof_init, "candidates": [issue]})
    scores_init = res_init["results"][0]["scores"]
    assert scores_init["feedback_available"] is True
    # Acceptance: 10/20 = 50%, Completion: 5/10 = 50%, Similarity = 80 -> F = 0.3*50 + 0.4*50 + 0.3*80 = 59.0
    assert abs(scores_init["feedback"] - 59.0) < 0.01

    # Increased counts: accepted=20, rejected=5, completed=16
    prof_inc = dict(base_prof, **data["counts_increased"])
    res_inc = cpp_client.execute_ranking({"profile": prof_inc, "candidates": [issue]})
    scores_inc = res_inc["results"][0]["scores"]
    # Acceptance: 20/25 = 80%, Completion: 16/20 = 80%, Similarity = 80 -> F = 0.3*80 + 0.4*80 + 0.3*80 = 80.0
    assert abs(scores_inc["feedback"] - 80.0) < 0.01

    # When has_feedback_history is False, feedback is disabled and omitted
    prof_cold = dict(base_prof, has_feedback_history=False)
    res_cold = cpp_client.execute_ranking({"profile": prof_cold, "candidates": [issue]})
    scores_cold = res_cold["results"][0]["scores"]
    assert scores_cold["feedback_available"] is False
    assert "Feedback (Cold Start)" in scores_cold["missing_components"]


def test_experiment_e_missing_data(fixtures):
    """
    Experiment E — Missing Data:
    Remove a component's evidence.
    Verify the remaining weights are renormalized and the missing component is reported.
    Also verify that an issue with all components missing produces insufficient data.
    """
    data = fixtures["experiment_e_missing_data"]
    prof_cold = data["profile_cold_start"]

    issue_partial = {
        "id": "exp_e_partial",
        "issue_number": 401,
        "title": "Partial Issue",
        "issue_url": "https://github.com/org/repo/issues/401",
        "state": "open",
        "technologies": [{"technology": "Python", "raw_relevance": 1.0}],
        "responsiveness_evidence": {"available": False},
        "health_evidence": {"available": False}
    }

    res_partial = cpp_client.execute_ranking({"profile": prof_cold, "candidates": [issue_partial]})
    scores_partial = res_partial["results"][0]["scores"]

    # Missing components should list Responsiveness, Health, and Feedback (Cold Start)
    missing = scores_partial["missing_components"]
    assert "Responsiveness" in missing
    assert "Repository Health" in missing
    assert "Feedback (Cold Start)" in missing

    # Available weights: Skill (0.35) + Difficulty (0.25) + Freshness (0.05) = 0.65
    eff_skill = res_partial["results"][0]["effective_weights"]["skill"]
    assert abs(eff_skill - (0.35 / 0.65)) < 0.005

    # Case 2: All components missing -> insufficient data
    issue_all_missing = {
        "id": "exp_e_empty",
        "issue_number": 402,
        "title": "Completely empty issue",
        "issue_url": "https://github.com/org/repo/issues/402",
        "state": "open",
        "skill_available": False,
        "difficulty_evidence": {"available": False},
        "responsiveness_evidence": {"available": False},
        "health_evidence": {"available": False},
        "freshness_evidence": {"available": False}
    }
    res_all_missing = cpp_client.execute_ranking({"profile": prof_cold, "candidates": [issue_all_missing]})
    scores_empty = res_all_missing["results"][0]["scores"]
    assert scores_empty["final_score"] == 0.0
    assert "Insufficient scoring data available" in scores_empty["explanation"]


def test_experiment_f_backfill():
    """
    Experiment F — Backfill:
    Make the highest-ranked issues invalid in mocked GitHub responses.
    Verify lower-ranked eligible issues are delivered in the correct order.
    """
    live_verifier.clear_cache()

    candidates = [
        {
            "id": f"backfill_cand_{i}",
            "issue_number": 500 + i,
            "title": f"Candidate #{i}",
            "repository_full_name": "org/repo",
            "issue_url": f"https://github.com/org/repo/issues/{500 + i}",
            "scores": {"final_score": 90.0 - i * 2.0},
            "final_score": 90.0 - i * 2.0
        }
        for i in range(8)
    ]

    # Mock GitHub verification: candidates 500 (rank 1) and 501 (rank 2) are closed
    def mock_gh(url, **kwargs):
        num = int(url.split("/")[-1])
        if num in (500, 501):
            return Mock(
                status_code=200,
                json=lambda: {"state": "closed", "number": num, "assignees": []},
                headers={"X-RateLimit-Remaining": "4999"}
            )
        return Mock(
            status_code=200,
            json=lambda: {
                "state": "open",
                "number": num,
                "assignee": None,
                "assignees": [],
                "html_url": f"https://github.com/org/repo/issues/{num}",
                "repository_url": "https://api.github.com/repos/org/repo"
            },
            headers={"X-RateLimit-Remaining": "4999"}
        )

    with patch.object(live_verifier.session, "get", side_effect=mock_gh):
        delivered, audited, metrics = live_verifier.verify_and_backfill(candidates, target_count=5)

        assert len(delivered) == 5
        delivered_nums = [r["issue_number"] for r in delivered]

        # Top 2 closed issues must NOT be delivered
        assert 500 not in delivered_nums
        assert 501 not in delivered_nums

        # Candidates 502, 503, 504, 505, 506 must backfill in strictly descending order
        assert delivered_nums == [502, 503, 504, 505, 506]

        # Audited records show exclusion reasons
        excluded = [a for a in audited if a["decision"] == "rejected"]
        assert len(excluded) >= 2
        for exc in excluded:
            assert "closed" in exc["exclusion_reason"].lower()


def test_experiment_g_reproducibility():
    """
    Experiment G — Reproducibility:
    Run the same input repeatedly with the same configuration.
    Verify identical scores and deterministic ordering.
    """
    profile = {
        "user_id": "test_repro_user",
        "capability": 72.0,
        "skills": {
            "Python": {"score": 85.0, "confidence": 1.0},
            "FastAPI": {"score": 75.0, "confidence": 1.0}
        }
    }

    candidates = [
        {
            "id": f"repro_cand_{i}",
            "issue_number": 600 + i,
            "title": f"Candidate {i}",
            "repository_full_name": "org/repo",
            "issue_url": f"https://github.com/org/repo/issues/{600 + i}",
            "state": "open",
            "technologies": [{"technology": "Python", "raw_relevance": 1.0}],
            "difficulty_evidence": {
                "scope_change_size": 50.0 + i * 5,
                "technical_complexity": 50.0 + i * 5,
                "historical_effort": 50.0 + i * 5,
                "dependency_arch_impact": 50.0 + i * 5,
                "coordination_complexity": 50.0 + i * 5
            }
        }
        for i in range(5)
    ]

    payload = {"profile": profile, "candidates": candidates}

    first_run = cpp_client.execute_ranking(payload)
    first_scores = [r["scores"]["final_score"] for r in first_run["results"]]
    first_ids = [r["id"] for r in first_run["results"]]

    # Run 15 times repeatedly
    for _ in range(15):
        run = cpp_client.execute_ranking(payload)
        run_scores = [r["scores"]["final_score"] for r in run["results"]]
        run_ids = [r["id"] for r in run["results"]]

        assert run_ids == first_ids, "Candidate ordering changed between identical runs!"
        for s1, s2 in zip(first_scores, run_scores):
            assert abs(s1 - s2) < 0.000001, "Candidate score changed between identical runs!"
