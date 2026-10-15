import logging
from datetime import datetime, timezone
from typing import List, Dict, Any, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel
from app.models import ContributorProfile, RankingWeights, IssueRecord
from app.database import db
from app.cpp_engine_client import cpp_client
from app.verifier import live_verifier
from app.routes.simulator import SAMPLE_ISSUES

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/recommendations", tags=["Personalized Recommendations"])

class RecommendationRequest(BaseModel):
    profile: ContributorProfile
    weights: Optional[RankingWeights] = None
    target_count: int = 5
    verification_budget: int = 30
    prefer_live_atlas: bool = True

@router.post("/personalized")
def get_personalized_recommendations(req: RecommendationRequest):
    candidates = []
    data_source = "atlas_candidates"

    # 1. Candidate Retrieval from MongoDB Atlas
    if req.prefer_live_atlas and db.db is not None:
        try:
            cursor = db.db.issues.find({"state": "open", "is_assigned": False}).limit(100)
            for doc in cursor:
                doc["_id"] = str(doc.get("_id", ""))
                candidates.append(doc)
        except Exception as e:
            logger.warning("Atlas query failed: %s; falling back to bundled candidates", str(e))

    if not candidates:
        # Graceful fallback to bundled candidates if pool is not yet populated
        data_source = "sample_demonstration_candidates"
        candidates = SAMPLE_ISSUES

    # 2. Authoritative C++ Engine Scoring & Ranking
    payload = {
        "profile": req.profile.dict(),
        "weights": req.weights.dict() if req.weights else RankingWeights().dict(),
        "candidates": candidates
    }

    try:
        engine_response = cpp_client.execute_ranking(payload)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"C++ engine failed to rank candidates: {str(e)}")

    ranked_candidates = engine_response.get("results", [])
    model_version = engine_response.get("algorithm_version", "1.0.0-phase1")

    # 3. Live Verification & Shortlist Backfill
    verified_recommendations = live_verifier.verify_and_backfill(
        ranked_candidates,
        target_count=req.target_count,
        max_budget=req.verification_budget
    )

    # 4. Save Audit Snapshots to MongoDB Atlas for Reproducibility
    now_iso = datetime.now(timezone.utc).isoformat()
    if db.db is not None:
        try:
            for rec in verified_recommendations:
                snapshot = {
                    "user_id": req.profile.user_id,
                    "issue_id": rec.get("id"),
                    "native_id": str(rec.get("issue_number")),
                    "repository_full_name": rec.get("repository_full_name"),
                    "final_score": rec.get("scores", {}).get("final_score"),
                    "rank": rec.get("rank"),
                    "scores": rec.get("scores"),
                    "effective_weights": rec.get("effective_weights"),
                    "weighted_contributions": rec.get("weighted_contributions"),
                    "missing_components": rec.get("missing_components"),
                    "explanation": rec.get("explanation"),
                    "verification_notes": rec.get("verification_notes"),
                    "algorithm_version": model_version,
                    "generated_at": now_iso
                }
                db.db.recommendations.insert_one(snapshot)
        except Exception as e:
            logger.warning("Could not persist recommendation snapshot: %s", str(e))

    return {
        "data_source": data_source,
        "algorithm_version": model_version,
        "total_candidates_considered": len(candidates),
        "total_ranked": len(ranked_candidates),
        "verified_recommendations": verified_recommendations,
        "generated_at": now_iso
    }

@router.get("/snapshots/{user_id}")
def get_user_snapshots(user_id: str, limit: int = 10):
    if db.db is None:
        return {"snapshots": [], "status": "atlas_unavailable"}
    try:
        cursor = db.db.recommendations.find({"user_id": user_id}).sort("generated_at", -1).limit(limit)
        snapshots = []
        for doc in cursor:
            doc["_id"] = str(doc["_id"])
            snapshots.append(doc)
        return {"snapshots": snapshots, "count": len(snapshots)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
