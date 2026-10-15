"""
Interactive Algorithm Explorer for Issue Pilot
Runs directly against the C++ Ranking Engine binary without touching frontend.
"""

import json
import subprocess
import os
import sys

ENGINE_EXE = os.path.join(os.path.dirname(__file__), "ranking_engine", "build", "issue_pilot_ranking_engine.exe")

def run_engine_stdin(payload: dict) -> dict:
    if not os.path.exists(ENGINE_EXE):
        raise FileNotFoundError(f"Engine binary not found at: {ENGINE_EXE}")
    proc = subprocess.Popen(
        [ENGINE_EXE],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    stdout, stderr = proc.communicate(input=json.dumps(payload))
    if proc.returncode != 0:
        raise RuntimeError(f"Engine failed ({proc.returncode}): {stderr}")
    return json.loads(stdout)

def run_engine_flag(flag: str) -> str:
    if not os.path.exists(ENGINE_EXE):
        raise FileNotFoundError(f"Engine binary not found at: {ENGINE_EXE}")
    res = subprocess.run([ENGINE_EXE, flag], capture_output=True, text=True, check=True)
    return res.stdout

def print_banner(title: str):
    print("\n" + "=" * 65)
    print(f" {title}")
    print("=" * 65)

def demo_worked_example():
    print_banner("DEMO: Worked Example (Doc Section 16)")
    profile = {
        "user_id": "alice_dev",
        "capability": 65.0,
        "skills": {
            "python": {"score": 90.0},
            "fastapi": {"score": 80.0}
        },
        "has_feedback_history": True,
        "accepted_count": 6,
        "rejected_count": 4,
        "completed_count": 5,
        "similarity_to_history": 75.0
    }

    candidates = [
        {
            "id": "issue-jwt-auth",
            "issue_number": 101,
            "title": "Implement JWT Token Authentication & Rotation",
            "repository_full_name": "tiangolo/fastapi",
            "issue_url": "https://github.com/tiangolo/fastapi/issues/101",
            "state": "open",
            "is_assigned": False,
            "technologies": [
                {"technology": "python", "raw_relevance": 0.8},
                {"technology": "fastapi", "raw_relevance": 0.2}
            ],
            "difficulty_evidence": {
                "scope_change_size": 65.0,
                "technical_complexity": 70.0,
                "historical_effort": 60.0,
                "dependency_arch_impact": 55.0,
                "coordination_complexity": 45.0
            },
            "responsiveness_evidence": {
                "available": True,
                "response_time_score": 85.0,
                "response_rate_score": 90.0,
                "pr_review_score": 80.0,
                "recent_maintainer_act": 85.0
            },
            "health_evidence": {
                "available": True,
                "recent_commits": 90.0,
                "pr_activity": 85.0,
                "issue_activity": 80.0,
                "active_contributors": 85.0,
                "recent_releases": 80.0
            },
            "freshness_evidence": {
                "available": True,
                "age_score": 95.0,
                "activity_score": 90.0
            }
        },
        {
            "id": "issue-docs-typo",
            "issue_number": 102,
            "title": "Fix typo in tutorial documentation",
            "repository_full_name": "tiangolo/fastapi",
            "issue_url": "https://github.com/tiangolo/fastapi/issues/102",
            "state": "open",
            "is_assigned": False,
            "technologies": [
                {"technology": "markdown", "raw_relevance": 1.0}
            ],
            "difficulty_evidence": {
                "scope_change_size": 10.0,
                "technical_complexity": 10.0,
                "historical_effort": 10.0,
                "dependency_arch_impact": 10.0,
                "coordination_complexity": 10.0
            },
            "responsiveness_evidence": {
                "available": True,
                "response_time_score": 85.0,
                "response_rate_score": 90.0,
                "pr_review_score": 80.0,
                "recent_maintainer_act": 85.0
            },
            "health_evidence": {
                "available": True,
                "recent_commits": 90.0,
                "pr_activity": 85.0,
                "issue_activity": 80.0,
                "active_contributors": 85.0,
                "recent_releases": 80.0
            },
            "freshness_evidence": {
                "available": True,
                "age_score": 40.0,
                "activity_score": 30.0
            }
        }
    ]

    res = run_engine_stdin({"profile": profile, "candidates": candidates})
    for r in res.get("results", []):
        print(f"\n[Rank #{r['rank']}] {r['title']} (Score: {r['scores']['final_score']:.2f} / 100)")
        print(f"  URL: {r['issue_url']}")
        print(f"  Component Breakdown:")
        print(f"    - Skill Match (S)        : {r['scores']['skill_match']:.2f} (Weight: {r['effective_weights']['skill']*100:.1f}% -> +{r['weighted_contributions']['skill']:.2f} pts)")
        print(f"    - Difficulty Match (D)   : {r['scores']['difficulty_match']:.2f} (Weight: {r['effective_weights']['difficulty']*100:.1f}% -> +{r['weighted_contributions']['difficulty']:.2f} pts)")
        print(f"    - Responsiveness (R)     : {r['scores']['responsiveness']:.2f} (Weight: {r['effective_weights']['responsiveness']*100:.1f}% -> +{r['weighted_contributions']['responsiveness']:.2f} pts)")
        print(f"    - Repository Health (H)  : {r['scores']['repository_health']:.2f} (Weight: {r['effective_weights']['health']*100:.1f}% -> +{r['weighted_contributions']['health']:.2f} pts)")
        print(f"    - Feedback / History (F) : {r['scores']['feedback']:.2f} (Weight: {r['effective_weights']['feedback']*100:.1f}% -> +{r['weighted_contributions']['feedback']:.2f} pts)")
        print(f"    - Freshness (N)          : {r['scores']['freshness']:.2f} (Weight: {r['effective_weights']['freshness']*100:.1f}% -> +{r['weighted_contributions']['freshness']:.2f} pts)")
        print(f"  Formula Decomposition:")
        print(f"    {r['explanation']}")

def demo_eval_metrics():
    print_banner("BENCHMARK EVALUATION METRICS (--eval)")
    raw = run_engine_flag("--eval")
    data = json.loads(raw)
    print(f"Algorithm Version : {data.get('algorithm_version')}")
    print(f"Total Evaluated   : {data.get('total_evaluated')} candidates")
    print(f"Precision@5       : {data.get('precision_at_5'):.1%}")
    print(f"Recall@5          : {data.get('recall_at_5'):.1%}")
    print(f"DCG@5             : {data.get('dcg_at_5'):.4f}")
    print(f"IDCG@5            : {data.get('idcg_at_5'):.4f}")
    print(f"NDCG@5            : {data.get('ndcg_at_5'):.4f}  (Baseline was: {data.get('baseline_ndcg_at_5'):.2f})")
    print(f"Dataset           : {data.get('dataset_description')}")

def demo_experiments():
    print_banner("7 WHAT-IF SIMULATION EXPERIMENTS (--test-experiments)")
    raw = run_engine_flag("--test-experiments")
    data = json.loads(raw)
    for exp in data.get("experiments", []):
        status = "PASSED [VERIFIED]" if exp.get("verified") else "FAILED"
        print(f"* [{status}] {exp['id']}: {exp['name']}")
        print(f"    Description: {exp.get('description', '')}")


def run_cpp_unit_tests():
    print_banner("RUNNING C++ ENGINE UNIT TESTS (test_ranking.exe)")
    test_exe = os.path.join(os.path.dirname(__file__), "ranking_engine", "build", "test_ranking.exe")
    res = subprocess.run([test_exe], capture_output=True, text=True)
    print(res.stdout)

if __name__ == "__main__":
    print("Issue Pilot - Standalone Algorithm Verifier & Explorer")
    print("=" * 65)
    print("1. Run Worked Example Ranking (2 sample issues + contributor profile)")
    print("2. Run Evaluation Benchmark (NDCG@5 & Precision metrics)")
    print("3. Run 7 What-If Simulation Experiments")
    print("4. Run C++ Unit Test Suite (10 Formula Tests)")
    print("5. Run All of the Above")
    
    choice = sys.argv[1] if len(sys.argv) > 1 else "5"
    if choice in ("1", "5"):
        demo_worked_example()
    if choice in ("2", "5"):
        demo_eval_metrics()
    if choice in ("3", "5"):
        demo_experiments()
    if choice in ("4", "5"):
        run_cpp_unit_tests()
