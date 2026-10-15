from fastapi import APIRouter, HTTPException, BackgroundTasks
from typing import Dict, Any
from app.github_worker import github_worker
from app.database import db

router = APIRouter(prefix="/api/ingestion", tags=["Ingestion & Candidate Pool"])

@router.post("/run")
def trigger_ingestion_job(per_page: int = 15):
    """
    Operator Control: Trigger bounded GitHub discovery and refresh job immediately.
    """
    try:
        job_summary = github_worker.run_ingestion_job(max_pages_per_query=1, per_page=per_page)
        return {
            "message": "Ingestion job completed successfully",
            "job_summary": job_summary
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ingestion run failed: {str(e)}")

@router.get("/status")
def get_pool_status():
    if db.db is None:
        return {
            "atlas_connected": False,
            "total_issues": 0,
            "open_issues": 0,
            "latest_job": None,
            "message": "MongoDB Atlas connection not established"
        }

    try:
        total_issues = db.db.issues.count_documents({})
        open_issues = db.db.issues.count_documents({"state": "open"})
        latest_job = db.db.jobs.find_one(sort=[("start_time", -1)])
        if latest_job:
            latest_job["_id"] = str(latest_job["_id"])

        # Tech breakdown sample
        tech_pipeline = [
            {"$unwind": "$detected_technologies"},
            {"$group": {"_id": "$detected_technologies", "count": {"$sum": 1}}},
            {"$sort": {"count": -1}},
            {"$limit": 8}
        ]
        tech_stats = list(db.db.issues.aggregate(tech_pipeline))

        return {
            "atlas_connected": True,
            "total_issues": total_issues,
            "open_issues": open_issues,
            "technology_breakdown": [{"tech": t["_id"], "count": t["count"]} for t in tech_stats],
            "latest_job": latest_job
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
