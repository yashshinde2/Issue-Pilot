import sys
import os
import json

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from app.database import db
from app.routes.recommendations import get_personalized_recommendations, RecommendationRequest
from app.models import ContributorProfile, ContributorSkill

def test_recommendation_flow():
    db.connect()
    profile = ContributorProfile(
        user_id="test_contributor",
        skills={"Python": ContributorSkill(technology="Python", score=85.0, confidence=1.0)}
    )
    req = RecommendationRequest(
        profile=profile,
        prefer_live_atlas=True,
        demo_mode=False,
        target_count=5,
        verification_budget=15
    )
    res = get_personalized_recommendations(req)
    print("Run ID:", res["run_id"])
    print("Total Candidates Considered:", res["total_candidates_considered"])
    print("Total Ranked by C++ Engine:", res["total_ranked"])
    print("Candidates Live Verified:", res["candidates_verified"])
    print("Candidates Excluded (Closed/Assigned):", res["candidates_excluded"])
    print("Candidates Inconclusive:", res["candidates_inconclusive"])
    print("Delivered Recommendations Count:", res["delivered_count"])
    for src in ["gitlab", "codeberg", "bugzilla"]:
        req_src = RecommendationRequest(
            profile=profile,
            prefer_live_atlas=True,
            demo_mode=False,
            source_filter=src,
            target_count=2,
            verification_budget=5
        )
        res_src = get_personalized_recommendations(req_src)
        print(f"\n=== Recommendations for {src.upper()} ===")
        print(f"Total Considered: {res_src['total_candidates_considered']}, Delivered: {res_src['delivered_count']}, Verified: {res_src['candidates_verified']}")
        for idx, r in enumerate(res_src["recommendations"]):
            num = r.get("issue_number")
            repo = r.get("repository_full_name")
            title = r.get("title", "")[:50]
            status = r.get("verification_status")
            print(f"  {idx+1}. #{num} in {repo} -> {title} (Status: {status})")

if __name__ == "__main__":
    test_recommendation_flow()
