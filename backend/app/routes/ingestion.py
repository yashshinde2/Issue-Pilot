from fastapi import APIRouter, HTTPException, Query, status
from typing import Dict, Any, Optional
from datetime import datetime, timezone

from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo
from app.adapters import (
    ADAPTERS,
    get_adapter,
    github_adapter,
    gitlab_adapter,
    codeberg_adapter,
    bugzilla_adapter
)
from app.ingestion.discovery import discovery_worker
from app.ingestion.refresher import refresh_worker
from app.ingestion.lifecycle import lifecycle_worker
from app.ingestion.scheduler import scheduler_service
from app.github_worker import github_worker

router = APIRouter(prefix="/api/ingestion", tags=["Ingestion & Candidate Pool"])


@router.post("/run")
def trigger_ingestion_job(per_page: int = 15):
    """
    Operator Control: Trigger bounded GitHub discovery and refresh job immediately.
    Backward-compatible with Milestones 1 and 2.
    """
    try:
        job_summary = github_worker.run_ingestion_job(max_pages_per_query=1, per_page=per_page)
        return {
            "message": "Ingestion job completed successfully",
            "job_summary": job_summary
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ingestion run failed: {str(e)}")


@router.post("/discover")
def trigger_discovery_job(
    max_issues: Optional[int] = Query(None, description="Max candidate issues to discover in this run"),
    pages_per_query: int = Query(settings.max_search_pages_per_run, ge=1, le=10),
    per_page: int = Query(settings.per_page_count, ge=1, le=100),
    resume: bool = Query(True, description="Resume from last checkpoint"),
    force: bool = Query(False, description="Bypass existing concurrency lock"),
    source: Optional[str] = Query("all", description="Platform: 'all', 'github', 'gitlab', 'codeberg', 'bugzilla'")
):
    """
    Trigger rotating candidate issue discovery across diverse technologies and platforms.
    Enforces pool size bounds, per-source request budgets, and repository diversity limits.
    """
    try:
        result = discovery_worker.run_discovery(
            max_issues_to_discover=max_issues,
            max_pages_per_query=pages_per_query,
            per_page=per_page,
            resume=resume,
            force=force,
            source=source
        )
        if result.get("status") == "locked":
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=result.get("message"))
        return result
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Discovery job failed: {str(e)}")


@router.post("/refresh")
def trigger_refresh_job(
    batch_size: int = Query(50, ge=1, le=200, description="Number of candidates to refresh"),
    max_age_days: int = Query(settings.refresh_cadence_days, ge=1, le=30),
    force: bool = Query(False, description="Bypass existing concurrency lock")
):
    """
    Refresh a bounded batch of stored candidates from their respective platform APIs.
    Verifies open/closed state, updates comments, and preserves ranking evidence.
    """
    try:
        result = refresh_worker.run_refresh(
            batch_size=batch_size,
            max_age_days=max_age_days,
            force=force
        )
        if result.get("status") == "locked":
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=result.get("message"))
        return result
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Refresh job failed: {str(e)}")


@router.post("/cleanup")
def trigger_cleanup_job(
    older_than_days: int = Query(settings.older_window_days, ge=1, le=365),
    force: bool = Query(False, description="Bypass existing concurrency lock")
):
    """
    Mark candidates not seen within older_window_days as stale.
    Does NOT delete historical recommendation or feedback records.
    """
    try:
        result = lifecycle_worker.run_cleanup(
            older_than_days=older_than_days,
            force=force
        )
        if result.get("status") == "locked":
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=result.get("message"))
        return result
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Cleanup job failed: {str(e)}")


@router.post("/run-all")
def trigger_full_cycle():
    """
    Run full ingestion pipeline sequentially: Discovery -> Refresh -> Stale Cleanup.
    """
    try:
        return scheduler_service.run_all_now()
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Cycle run failed: {str(e)}")


@router.get("/health")
def get_platforms_health():
    """
    Operator Control / Observability:
    Validate connectivity, auth status, permissions, API version, and rate limits
    for all four sources independently without write operations. Never exposes tokens.
    """
    report = {}
    active_sources = []
    degraded_sources = []

    for name, adapter in ADAPTERS.items():
        try:
            h = adapter.check_health()
            report[name] = h
            if h.get("ingestion_enabled"):
                active_sources.append(name)
            else:
                degraded_sources.append(name)
        except Exception as e:
            report[name] = {
                "source": name,
                "source_instance": getattr(adapter, "source_instance", name),
                "configured": bool(getattr(adapter, "_token", None) or getattr(adapter, "_base_url", None)),
                "reachable": False,
                "auth_status": "not_verified",
                "read_permission_status": "denied",
                "api_version": "unknown",
                "rate_limit_info": {"limit": "unknown", "remaining": "unknown"},
                "last_successful_check_at": getattr(adapter, "_last_successful_check", None),
                "error_message": f"Unexpected health check error: {str(e)}",
                "recommended_action": "Check configuration and network reachability.",
                "ingestion_enabled": False
            }
            degraded_sources.append(name)

    overall = "operational" if len(active_sources) == len(ADAPTERS) else ("degraded" if active_sources else "failing")
    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "overall_status": overall,
        "active_sources": active_sources,
        "degraded_sources": degraded_sources,
        "platforms": report
    }


@router.post("/health/check")
def run_manual_health_check():
    """
    Admin Action: Trigger immediate live backend API access check for all 4 configured platforms.
    """
    return get_platforms_health()


@router.get("/platforms")
def get_platform_details():
    """
    Detailed Per-Platform Status for Admin Dashboard:
    - Connection and authentication status
    - Last successful API check
    - Last successful ingestion run
    - Issues discovered, inserted, updated, and skipped
    - Candidate count in MongoDB Atlas
    - Rate-limit state and remaining budget
    - Ingestion enable/disable status
    """
    health_data = get_platforms_health()
    platforms_report = {}

    db_counts = {}
    if db.is_connected():
        try:
            for s in ["github", "gitlab", "codeberg", "bugzilla"]:
                db_counts[s] = issue_repo.count_by_source(s)
        except Exception:
            pass

    latest_job = job_repo.get_latest_job() if db.is_connected() else None
    latest_per_source = latest_job.get("per_source_summary", {}) if latest_job else {}

    for name, h in health_data["platforms"].items():
        src_job = latest_per_source.get(name, {})
        platforms_report[name] = {
            "name": name,
            "display_name": name.capitalize() if name != "cpp" else "C++",
            "source_instance": h.get("source_instance"),
            "configured": h.get("configured", False),
            "reachable": h.get("reachable", False),
            "auth_status": h.get("auth_status"),
            "read_permission_status": h.get("read_permission_status"),
            "api_version": h.get("api_version"),
            "rate_limit_info": h.get("rate_limit_info", {}),
            "last_successful_check_at": h.get("last_successful_check_at"),
            "error_message": h.get("error_message"),
            "recommended_action": h.get("recommended_action"),
            "ingestion_enabled": h.get("ingestion_enabled", False),
            "candidate_count_in_atlas": db_counts.get(name, 0),
            "last_run_stats": {
                "discovered": src_job.get("discovered", 0),
                "inserted": src_job.get("inserted", 0),
                "updated": src_job.get("updated", 0),
                "skipped": src_job.get("skipped", 0),
                "status": src_job.get("status", "idle")
            }
        }

    return {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "overall_status": health_data["overall_status"],
        "active_sources": health_data["active_sources"],
        "degraded_sources": health_data["degraded_sources"],
        "platforms": platforms_report
    }


@router.get("/status")
def get_pool_status():
    """
    Candidate Pool Observability:
    Returns candidate pool metrics, repository diversity, lifecycle breakdown,
    multi-platform breakdown, rate-limit status, and background scheduler state.
    """
    if not db.is_connected():
        return {
            "atlas_connected": False,
            "total_issues": 0,
            "open_issues": 0,
            "latest_job": None,
            "scheduler": scheduler_service.get_status(),
            "rate_limit": github_adapter.rate_limit_state,
            "platforms_health": get_platforms_health(),
            "message": "MongoDB Atlas connection not established"
        }

    try:
        pool_metrics = issue_repo.get_pool_metrics()
        latest_job = job_repo.get_latest_job()

        return {
            "atlas_connected": True,
            "total_issues": pool_metrics.get("total_candidates", 0),
            "open_issues": pool_metrics.get("open_candidates", 0),
            "closed_issues": pool_metrics.get("closed_candidates", 0),
            "status_breakdown": pool_metrics.get("status_breakdown", {}),
            "source_breakdown": pool_metrics.get("source_breakdown", {}),
            "unique_repositories": pool_metrics.get("unique_repositories", 0),
            "top_technologies": pool_metrics.get("top_technologies", []),
            "top_repositories": pool_metrics.get("top_repositories", []),
            "scheduler": scheduler_service.get_status(),
            "rate_limit": github_adapter.rate_limit_state,
            "platforms_health": get_platforms_health(),
            "latest_job": latest_job
        }
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))


@router.get("/rate-limit")
def get_rate_limits(source: Optional[str] = Query("all")):
    """
    Query API rate limit state. Supports source='all', 'github', 'gitlab', 'codeberg', 'bugzilla'.
    Never exposes tokens.
    """
    if source and source.lower() == "github":
        return github_adapter.get_rate_limit()
    
    res = {}
    for name, adapter in ADAPTERS.items():
        res[name] = adapter.get_rate_limit()
    return res


@router.get("/jobs")
def list_ingestion_jobs(
    limit: int = Query(20, ge=1, le=100),
    skip: int = Query(0, ge=0),
    job_type: Optional[str] = Query(None, description="Filter by job type: 'discovery', 'refresh', 'cleanup'")
):
    """
    List ingestion and refresh job records in reverse chronological order.
    """
    if not db.is_connected():
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="MongoDB Atlas unavailable")

    try:
        jobs = job_repo.list_jobs(limit=limit, skip=skip, job_type=job_type)
        return {"count": len(jobs), "jobs": jobs}
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))


@router.get("/jobs/{job_id}")
def get_job_details(job_id: str):
    """
    Inspect a specific ingestion job, its execution metrics, checkpoints, and errors.
    """
    if not db.is_connected():
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="MongoDB Atlas unavailable")

    job = job_repo.get_job(job_id)
    if not job:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Job '{job_id}' not found.")
    return job


@router.post("/scheduler/start")
def start_scheduler():
    """Start the periodic background ingestion scheduler."""
    started = scheduler_service.start()
    return {
        "status": "started" if started else "already_running",
        "scheduler": scheduler_service.get_status()
    }


@router.post("/scheduler/stop")
def stop_scheduler():
    """Stop the periodic background ingestion scheduler."""
    stopped = scheduler_service.stop()
    return {
        "status": "stopped",
        "scheduler": scheduler_service.get_status()
    }


@router.get("/scheduler/status")
def get_scheduler_status():
    """Check whether the background scheduler is active and inspect schedule cadences."""
    return scheduler_service.get_status()
