import logging
from datetime import datetime, timezone
from typing import List, Dict, Any, Optional
from fastapi import APIRouter, HTTPException, Query, status
from pydantic import BaseModel

from app.models import ContributorProfile, RankingWeights, IssueRecord
from app.database import db
from app.crud import issue_repo, user_repo, rec_repo, DatabaseConnectionError
from app.cpp_engine_client import cpp_client
from app.verifier import live_verifier
from app.routes.simulator import SAMPLE_ISSUES, SAMPLE_PROFILES

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/recommendations", tags=["Personalized Recommendations"])

class RecommendationRequest(BaseModel):
    user_id: Optional[str] = None
    profile: Optional[ContributorProfile] = None
    weights: Optional[RankingWeights] = None
    target_count: int = 5
    verification_budget: int = 30
    prefer_live_atlas: bool = True
    demo_mode: bool = False
    technology_filter: Optional[str] = None
    min_difficulty: Optional[float] = None
    max_difficulty: Optional[float] = None

@router.post("/personalized")
def get_personalized_recommendations(req: RecommendationRequest):
    """
    Generate personalized issue recommendations:
    1. Verify Atlas connectivity for live mode (fails clearly if unavailable).
    2. Resolve contributor profile (from Atlas or payload).
    3. Retrieve eligible candidates (from Atlas or demo pool).
    4. Score and rank candidates using authoritative C++ engine.
    5. Run live verification & shortlist backfill.
    6. Persist audit snapshots to MongoDB Atlas.
    """
    is_live = not req.demo_mode and req.prefer_live_atlas

    # 1. Clear failure for live mode if Atlas is down
    if is_live and not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas is unavailable. Live recommendation requires an active database connection. Set demo_mode=true or prefer_live_atlas=false to evaluate bundled demonstration candidates."
        )

    # 2. Resolve Contributor Profile
    profile = None
    if req.profile is not None:
        profile = req.profile
    elif req.user_id:
        if db.is_connected():
            user_doc = user_repo.get_user(req.user_id)
            if user_doc:
                user_doc.pop("_id", None)
                profile = ContributorProfile(**user_doc)
        
        if profile is None:
            if is_live:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND,
                    detail=f"User profile '{req.user_id}' not found in MongoDB Atlas. Create the profile first or specify demo_mode=true."
                )
            # Demo fallback
            profile = ContributorProfile(**SAMPLE_PROFILES[0])
    else:
        if is_live:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Either 'profile' or 'user_id' must be provided for live recommendation."
            )
        profile = ContributorProfile(**SAMPLE_PROFILES[0])

    # 3. Retrieve Candidate Pool
    candidates = []
    data_source = "atlas_candidates"

    if req.demo_mode or not req.prefer_live_atlas:
        # Offline/bundled demonstration candidates
        data_source = "sample_demonstration_candidates"
        candidates = [dict(i) for i in SAMPLE_ISSUES]
    else:
        # Live MongoDB Atlas Candidate Retrieval
        try:
            live_candidates = issue_repo.query_issues(
                state="open",
                is_assigned=False,
                technology=req.technology_filter,
                min_difficulty=req.min_difficulty,
                max_difficulty=req.max_difficulty,
                limit=100
            )
            candidates = live_candidates
        except DatabaseConnectionError as e:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=f"MongoDB Atlas connection failure: {str(e)}"
            )

        if not candidates:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="No eligible open candidates found in MongoDB Atlas matching the specified criteria. Ingest candidates first or set demo_mode=true."
            )

    # 4. Authoritative C++ Engine Scoring & Ranking
    payload = {
        "profile": profile.dict() if hasattr(profile, "dict") else profile.model_dump(),
        "weights": (req.weights.dict() if hasattr(req.weights, "dict") else req.weights.model_dump()) if req.weights else RankingWeights().dict(),
        "candidates": candidates
    }

    try:
        engine_response = cpp_client.execute_ranking(payload)
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"C++ ranking engine execution failed: {str(e)}"
        )

    ranked_candidates = engine_response.get("results", [])
    model_version = engine_response.get("algorithm_version", "1.0.0-phase1")

    # 5. Live Verification & Shortlist Backfill
    verified_recommendations = live_verifier.verify_and_backfill(
        ranked_candidates,
        target_count=req.target_count,
        max_budget=req.verification_budget
    )

    # 6. Save Audit Snapshots to MongoDB Atlas for Reproducibility
    now_iso = datetime.now(timezone.utc).isoformat()
    if db.is_connected() and not req.demo_mode:
        try:
            rec_repo.save_recommendations(
                user_id=profile.user_id,
                recommendations=verified_recommendations,
                algorithm_version=model_version
            )
        except Exception as e:
            logger.warning("Could not persist recommendation snapshot to Atlas: %s", str(e))

    return {
        "data_source": data_source,
        "algorithm_version": model_version,
        "user_id": profile.user_id,
        "total_candidates_considered": len(candidates),
        "total_ranked": len(ranked_candidates),
        "verified_recommendations": verified_recommendations,
        "generated_at": now_iso
    }

@router.get("/snapshots/{user_id}")
def get_user_snapshots(user_id: str, limit: int = 10):
    """
    Retrieve stored recommendation snapshots for a given user.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas is unavailable."
        )

    try:
        snapshots = rec_repo.get_user_recommendations(user_id, limit=limit)
        return {"snapshots": snapshots, "count": len(snapshots)}
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))
