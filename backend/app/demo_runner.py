"""
Issue Pilot - Milestone 5: Final-Year Demonstration Mode.
Repeatable, credential-free demonstration executing the exact 9-step sequence:
1. Load known contributor profile and candidate pool.
2. Display six ranking components and configured weights (totaling 100%).
3. Calculate authoritative C++ scores and display initial ranked candidates.
4. Modify contributor skill/capability and show resulting score changes.
5. Demonstrate missing-data weight renormalization.
6. Simulate GitHub verification invalidation and demonstrate shortlist backfilling.
7. Display final recommendations with score explanations and effective weights.
8. Submit contributor feedback and confirm persistence.
9. Verify persisted feedback and audit snapshot survive application reload.
"""

import sys
import copy
import json
import time
from typing import Dict, Any, List

from app.config import settings
from app.database import db
from app.cpp_engine_client import cpp_client
from app.verifier import live_verifier
from app.crud import user_repo, rec_repo, feedback_repo
from app.routes.simulator import SAMPLE_PROFILES, SAMPLE_ISSUES


def print_step_banner(step_num: int, title: str):
    print("\n" + "=" * 80)
    print(f"  STEP {step_num}: {title.upper()}")
    print("=" * 80)


def run_demonstration(verbose: bool = True) -> Dict[str, Any]:
    """Execute the complete 9-step demonstration workflow."""
    results = {}

    # -------------------------------------------------------------------------
    # STEP 1: Load a known contributor profile and sample issues
    # -------------------------------------------------------------------------
    if not db.is_connected():
        db.connect()

    print_step_banner(1, "Load Known Contributor Profile and Sample Issues")
    profile = copy.deepcopy(SAMPLE_PROFILES[0])  # contributor-fullstack
    candidates = copy.deepcopy(SAMPLE_ISSUES)
    sample6 = {
        "id": "sample-6",
        "native_id": "1006",
        "repository_full_name": "psf/requests",
        "issue_number": 6124,
        "title": "Support HTTP/2 ALPN negotiation in default SSL context",
        "technology": "Python",
        "technologies": [{"technology": "Python", "weight": 1.0}],
        "difficulty_evidence": {
            "technical_complexity": 70.0,
            "scope_breadth": 65.0,
            "domain_depth": 72.0,
            "available": True
        },
        "responsiveness": {"first_response_time": 85.0, "review_latency": 80.0, "closure_rate": 82.0},
        "repo_health": {"maintenance_intent": 85.0, "activity_level": 88.0, "documentation_quality": 90.0},
        "feedback": {"accepted_count": 8, "rejected_count": 2, "completed_count": 6},
        "freshness": {"age_days": 10, "last_activity_days": 2, "freshness_score": 88.0},
        "issue_url": "https://github.com/psf/requests/issues/6124"
    }
    candidates.append(sample6)

    print(f"[DEMO DATA] Loaded Profile: {profile['user_id']}")
    print(f"  Capability: {profile['capability']}/100")
    print(f"  Skills: {', '.join(profile['skills'].keys())}")
    print(f"  Feedback History: {profile['accepted_count']} accepted, {profile['rejected_count']} rejected, {profile['completed_count']} completed")
    print(f"[DEMO DATA] Candidate Pool Size: {len(candidates)} issues loaded")
    results["step1"] = {"profile_id": profile["user_id"], "candidate_count": len(candidates)}

    # -------------------------------------------------------------------------
    # STEP 2: Display the six ranking components and configured weights
    # -------------------------------------------------------------------------
    print_step_banner(2, "Display Six Ranking Components and Configured Weights")
    weights = {
        "skill_match": 0.30,
        "difficulty_match": 0.20,
        "responsiveness": 0.15,
        "repo_health": 0.15,
        "feedback": 0.10,
        "freshness": 0.10
    }
    total_w = sum(weights.values())
    print("Component Weights (Must sum exactly to 1.000 / 100%):")
    for k, v in weights.items():
        print(f"  - {k:<20}: {v * 100:>5.1f}%")
    print(f"  TOTAL WEIGHT         : {total_w * 100:>5.1f}%")
    assert abs(total_w - 1.0) < 0.0001, "Weights must sum to 1.0"
    results["step2"] = {"weights": weights, "total_weight": total_w}

    # -------------------------------------------------------------------------
    # STEP 3: Show calculated final scores and ranked candidates
    # -------------------------------------------------------------------------
    print_step_banner(3, "Authoritative C++ Scoring & Initial Ranking")
    payload = {
        "profile": profile,
        "weights": weights,
        "candidates": candidates
    }
    resp1 = cpp_client.execute_ranking(payload)
    ranked1 = resp1.get("results", [])

    print(f"Algorithm Version: {resp1.get('algorithm_version')}")
    print(f"{'Rank':<5} | {'Issue Title':<50} | {'Final Score':<12} | {'Skill':<8} | {'Diff':<8}")
    print("-" * 90)
    for r in ranked1[:5]:
        scores = r.get("scores", {})
        title = (r.get("title") or "")[:48]
        print(f"#{r.get('rank'):<4} | {title:<50} | {scores.get('final_score', 0):>10.2f}% | {scores.get('skill_match', 0):>6.1f}% | {scores.get('difficulty_match', 0):>6.1f}%")
    results["step3"] = {"top_candidate": ranked1[0]["id"], "top_score": ranked1[0]["scores"]["final_score"]}

    # -------------------------------------------------------------------------
    # STEP 4: Change a skill or capability and show resulting score changes
    # -------------------------------------------------------------------------
    print_step_banner(4, "What-If Sensitivity: Modify Contributor Skills")
    modified_profile = copy.deepcopy(profile)
    # Lower Python skill to demonstrate sensitivity
    modified_profile["skills"]["Python"]["score"] = 30.0
    print(f"Sensitivity Modification: Lowered 'Python' skill score from 90.0 to 30.0")

    payload_mod = {
        "profile": modified_profile,
        "weights": weights,
        "candidates": candidates
    }
    resp2 = cpp_client.execute_ranking(payload_mod)
    ranked2 = resp2.get("results", [])

    print(f"{'Issue ID':<12} | {'Original Score':<16} | {'New Score':<12} | {'Delta':<8} | {'Original Skill':<15} | {'New Skill'}")
    print("-" * 90)
    for r_orig in ranked1[:3]:
        r_new = next(x for x in ranked2 if x["id"] == r_orig["id"])
        s_orig = r_orig["scores"]["final_score"]
        s_new = r_new["scores"]["final_score"]
        delta = s_new - s_orig
        sk_orig = r_orig["scores"]["skill_match"]
        sk_new = r_new["scores"]["skill_match"]
        print(f"{r_orig['id']:<12} | {s_orig:>14.2f}% | {s_new:>10.2f}% | {delta:>+6.2f}% | {sk_orig:>13.1f}% | {sk_new:>8.1f}%")

    top_orig = ranked1[0]
    top_new = next(x for x in ranked2 if x["id"] == top_orig["id"])
    assert top_new["scores"]["skill_match"] < top_orig["scores"]["skill_match"], "Skill match must decrease"
    results["step4"] = {"original_score": top_orig["scores"]["final_score"], "new_score": top_new["scores"]["final_score"]}

    # -------------------------------------------------------------------------
    # STEP 5: Demonstrate missing-data weight renormalization
    # -------------------------------------------------------------------------
    print_step_banner(5, "Missing-Data Handling & Weight Renormalization")
    test_candidate = copy.deepcopy(candidates[0])
    # Mark difficulty as missing
    test_candidate["difficulty_evidence"]["available"] = False
    test_candidate["difficulty_evidence"]["technical_complexity"] = 0.0

    payload_missing = {
        "profile": profile,
        "weights": weights,
        "candidates": [test_candidate]
    }
    resp_missing = cpp_client.execute_ranking(payload_missing)
    missing_res = resp_missing["results"][0]

    print(f"Evaluated Issue: {missing_res.get('title')}")
    print(f"Missing Components: {missing_res.get('missing_components')}")
    print("Effective Weights after Renormalization (excluding Difficulty Match 20%):")
    eff_w = missing_res.get("effective_weights", {})
    for comp, w_eff in eff_w.items():
        print(f"  - {comp:<20}: {w_eff * 100:>5.2f}%")
    eff_sum = sum(eff_w.values())
    print(f"  TOTAL EFFECTIVE WEIGHT : {eff_sum * 100:>5.2f}%")
    assert any("difficulty" in str(c).lower() for c in missing_res.get("missing_components", [])), "Difficulty match must be flagged missing"
    assert abs(eff_sum - 1.0) < 0.001, "Effective weights must sum to 100%"
    results["step5"] = {"missing_components": missing_res.get("missing_components"), "effective_weights": eff_w}

    # -------------------------------------------------------------------------
    # STEP 6: Invalidate top candidate and demonstrate shortlist backfilling
    # -------------------------------------------------------------------------
    print_step_banner(6, "Simulated GitHub Verification & Shortlist Backfill")
    # Take initial ranked list
    # Mark candidate #1 as closed in verification
    top_candidate_to_fail = ranked1[0]
    print(f"[DEMO SIMULATION] Simulated GitHub status check on #{top_candidate_to_fail.get('issue_number')}:")
    print(f"  Result: Closed on GitHub (Excluded from final delivery)")

    # Execute verify_and_backfill with simulated closed status
    target_count = 5
    simulated_pool = copy.deepcopy(ranked1)
    # Give the first candidate a closed simulated flag
    simulated_pool[0]["is_closed_simulated"] = True
    simulated_pool[0]["verification_status"] = "excluded"

    delivered, audited, metrics = live_verifier.verify_and_backfill(
        ranked_items=simulated_pool,
        target_count=target_count,
        max_budget=len(simulated_pool),
        demo_mode=True
    )

    print(f"\nVerification Results:")
    print(f"  Verified Count   : {metrics.get('candidates_verified')}")
    print(f"  Excluded Count   : {metrics.get('candidates_excluded')}")
    print(f"  Delivered Count  : {len(delivered)} (Target: {target_count})")
    print(f"\nBackfill Demonstration:")
    print(f"  Original #1 Issue ({top_candidate_to_fail['id']}) was replaced by lower-ranked candidate.")
    for d in delivered:
        print(f"  Delivered Rank #{d.get('rank')}: {d.get('id')} (Original Rank: #{d.get('original_rank')}) - {d.get('title')[:45]}")

    assert metrics.get("candidates_excluded") == 1, "Candidate #1 must be excluded"
    assert len(delivered) == target_count, f"Target count of {target_count} must be delivered via backfill"
    assert delivered[0]["id"] != top_candidate_to_fail["id"], "Top delivered issue cannot be the excluded candidate"

    results["step6"] = {
        "delivered_count": len(delivered),
        "excluded_count": metrics.get("candidates_excluded"),
        "top_delivered_id": delivered[0]["id"]
    }

    # -------------------------------------------------------------------------
    # STEP 7: Display final recommendations and explanations
    # -------------------------------------------------------------------------
    print_step_banner(7, "Final Personalized Recommendations & Explanations")
    for d in delivered[:3]:
        print(f"\nRecommendation #{d.get('rank')}: {d.get('title')}")
        print(f"  Repository : {d.get('repository_full_name')}")
        print(f"  Issue URL  : {d.get('issue_url')}")
        print(f"  Final Score: {d.get('scores', {}).get('final_score', 0):.2f}%")
        print(f"  Explanation: {d.get('explanation')}")

    results["step7"] = {"recommendations": [{"id": d["id"], "score": d.get("scores", {}).get("final_score")} for d in delivered]}

    # -------------------------------------------------------------------------
    # STEP 8: Submit feedback and confirm persistence
    # -------------------------------------------------------------------------
    print_step_banner(8, "Feedback Submission and Persistence")
    demo_user_id = "demo_student_contributor"
    # Ensure demo user exists
    user_repo.upsert_user({
        "user_id": demo_user_id,
        "capability": 76.0,
        "skills": ["Python", "FastAPI"]
    })

    chosen_issue = delivered[0]["id"]
    print(f"Submitting 'accepted' feedback event for issue '{chosen_issue}'...")

    feedback_doc = feedback_repo.record_feedback({
        "user_id": demo_user_id,
        "issue_id": chosen_issue,
        "event_type": "accepted",
        "recommendation_snapshot": delivered[0]
    })
    print(f"Feedback Event Recorded Successfully:")
    print(f"  User ID        : {feedback_doc.get('user_id')}")
    print(f"  Issue ID       : {feedback_doc.get('issue_id')}")
    print(f"  Event Type     : {feedback_doc.get('event_type')}")
    print(f"  Timestamp      : {feedback_doc.get('event_timestamp')}")

    user_after = user_repo.get_user(demo_user_id)
    print(f"Updated User Profile Counter:")
    print(f"  Accepted Count : {user_after.get('accepted_count', 0)}")
    print(f"  Has History    : {user_after.get('has_feedback_history')}")
    assert user_after.get("accepted_count", 0) >= 1
    results["step8"] = {"event_type": "accepted", "user_accepted_count": user_after.get("accepted_count")}

    # -------------------------------------------------------------------------
    # STEP 9: Application restart simulation & persistence verification
    # -------------------------------------------------------------------------
    print_step_banner(9, "Application Restart & Data Integrity Verification")
    print("Simulating application restart by disconnecting and reconnecting database...")
    db.disconnect()
    reconnected = db.connect()
    print(f"Database Reconnected: {reconnected}")

    persisted_user = user_repo.get_user(demo_user_id)
    persisted_fb = feedback_repo.get_user_feedback(demo_user_id)
    print(f"Retrieved Records Post-Restart:")
    print(f"  Persisted User Profile Found : {persisted_user is not None}")
    print(f"  Recorded Feedback Events     : {len(persisted_fb.get('events', []))}")
    print(f"  Latest Feedback Event Type   : {persisted_fb['events'][0]['event_type'] if persisted_fb.get('events') else 'None'}")

    assert persisted_user is not None, "Persisted profile must survive restart"
    assert len(persisted_fb.get("events", [])) >= 1, "Persisted feedback events must survive restart"
    results["step9"] = {
        "restart_verified": True,
        "feedback_event_count": len(persisted_fb.get("events", []))
    }

    print("\n" + "=" * 80)
    print("  FINAL-YEAR PROJECT DEMONSTRATION COMPLETED SUCCESSFULLY (9/9 STEPS)")
    print("=" * 80 + "\n")

    return results


if __name__ == "__main__":
    run_demonstration()
