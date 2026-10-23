import uuid
import time
import logging
from datetime import datetime, timezone
from typing import List, Dict, Any, Optional
from fastapi import APIRouter, HTTPException, Query, status
from pydantic import BaseModel, Field

from app.models import ContributorProfile, RankingWeights, IssueRecord
from app.config import settings
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
    shortlist_size: Optional[int] = None
    verification_budget: int = 30
    prefer_live_atlas: bool = True
    demo_mode: bool = False
    technology_filter: Optional[str] = None
    source_filter: Optional[str] = None
    min_difficulty: Optional[float] = None
    max_difficulty: Optional[float] = None
    run_id: Optional[str] = None
    timeout_seconds: Optional[int] = None
    max_retries: Optional[int] = None


@router.post("/personalized")
def get_personalized_recommendations(req: RecommendationRequest):
    """
    Generate personalized issue recommendations:
    1. Verify Atlas connectivity for live mode (fails clearly if unavailable).
    2. Resolve contributor profile (from Atlas or payload).
    3. Retrieve eligible candidates (from Atlas candidate pool or demo pool).
    4. Score and rank candidates using authoritative C++ engine.
    5. Run live multi-platform verification & shortlist backfill in descending rank order.
    6. Persist complete audit snapshots idempotently to MongoDB Atlas.
    7. Return final recommendations, explanations, verification metadata, and operational status.
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

    # 3. Retrieve Candidate Pool from Shared MongoDB Atlas Pool
    candidates = []
    data_source = "atlas_candidates"

    if req.demo_mode or not req.prefer_live_atlas:
        # Offline/bundled demonstration candidates
        data_source = "sample_demonstration_candidates"
        candidates = [dict(i) for i in SAMPLE_ISSUES]
    else:
        # Live MongoDB Atlas Candidate Retrieval with Stage 0 eligibility filters
        try:
            live_candidates = issue_repo.query_issues(
                state="open",
                is_assigned=False,
                technology=req.technology_filter,
                min_difficulty=req.min_difficulty,
                max_difficulty=req.max_difficulty,
                source=req.source_filter,
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
    weights_dict = (
        (req.weights.model_dump() if hasattr(req.weights, "model_dump") else req.weights.dict())
        if req.weights
        else (RankingWeights().model_dump() if hasattr(RankingWeights(), "model_dump") else RankingWeights().dict())
    )
    profile_dict = profile.model_dump() if hasattr(profile, "model_dump") else profile.dict()

    payload = {
        "profile": profile_dict,
        "weights": weights_dict,
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
    effective_budget = req.shortlist_size if req.shortlist_size is not None else (
        req.verification_budget if req.verification_budget is not None else settings.verification_candidate_limit
    )

    delivered_recs, audited_records, metrics = live_verifier.verify_and_backfill(
        ranked_candidates,
        target_count=req.target_count,
        max_budget=effective_budget,
        demo_mode=req.demo_mode or not req.prefer_live_atlas,
        timeout=req.timeout_seconds,
        max_retries=req.max_retries
    )

    # 6. Save Audit Snapshots to MongoDB Atlas for Idempotency and Reproducibility
    run_id = req.run_id or f"run_{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:6]}"
    now_iso = datetime.now(timezone.utc).isoformat()

    if db.is_connected() and not req.demo_mode:
        try:
            rec_repo.save_recommendations(
                user_id=profile.user_id,
                recommendations=delivered_recs,
                algorithm_version=model_version,
                run_id=run_id,
                audited_candidates=audited_records
            )
        except Exception as e:
            logger.warning("Could not persist recommendation snapshot to Atlas: %s", str(e))

    return {
        "run_id": run_id,
        "data_source": data_source,
        "algorithm_version": model_version,
        "user_id": profile.user_id,
        "total_candidates_considered": len(candidates),
        "total_ranked": len(ranked_candidates),
        "shortlist_size": metrics.get("shortlist_size", effective_budget),
        "candidates_verified": metrics.get("candidates_verified", 0),
        "candidates_excluded": metrics.get("candidates_excluded", 0),
        "candidates_inconclusive": metrics.get("candidates_inconclusive", 0),
        "delivered_count": len(delivered_recs),
        "is_partial_result": metrics.get("is_partial_result", False),
        "partial_result_reason": metrics.get("partial_result_reason"),
        "warning": metrics.get("warning"),
        "duration_ms": metrics.get("duration_ms", 0),
        "recommendations": delivered_recs,
        "verified_recommendations": delivered_recs,  # backward compatibility
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


@router.get("/runs/{run_id}")
def get_run_audit(run_id: str):
    """
    Retrieve detailed audit snapshot records for a recommendation run.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas is unavailable."
        )

    try:
        records = rec_repo.get_run_recommendations(run_id)
        return {
            "run_id": run_id,
            "total_records": len(records),
            "delivered": [r for r in records if r.get("decision") == "included"],
            "rejected": [r for r in records if r.get("decision") == "rejected"],
            "skipped": [r for r in records if r.get("decision") == "skipped"],
            "inconclusive": [r for r in records if r.get("decision") == "inconclusive"],
            "audit_trail": records
        }
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))
