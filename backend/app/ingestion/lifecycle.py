"""
Stale candidate evaluation and lifecycle management worker.
Identifies candidates older than older_window_days (e.g. 90 days) and marks them stale.
Never deletes historical recommendations, audit records, or user feedback.
"""
import time
import logging
from datetime import datetime, timezone
from typing import Dict, Any, Optional

from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo

logger = logging.getLogger(__name__)


class LifecycleWorker:
    def run_cleanup(self, older_than_days: Optional[int] = None, force: bool = False) -> Dict[str, Any]:
        """
        Evaluate candidate pool recency.
        Transitions candidates older than older_window_days (default 90) to 'stale'.
        Preserves all historical recommendation snapshots, audit trails, and feedback events.
        """
        job_id = job_repo.acquire_job_lock("cleanup")
        if not job_id and not force:
            return {
                "status": "locked",
                "message": "Another lifecycle cleanup job is currently in progress.",
                "job_id": None
            }
        elif not job_id and force:
            job_id = f"cleanup_forced_{int(time.time())}"

        start_time = datetime.now(timezone.utc)
        cutoff_days = older_than_days or settings.older_window_days

        if not db.is_connected():
            job_repo.release_job_lock(job_id, status="failed", updates={"errors": ["MongoDB connection unavailable"]})
            return {"job_id": job_id, "status": "failed", "message": "MongoDB connection unavailable"}

        stale_candidates = issue_repo.get_stale_candidates(older_than_days=cutoff_days)
        stale_ids = [c["id"] for c in stale_candidates if "id" in c]

        modified_count = 0
        if stale_ids:
            modified_count = issue_repo.mark_issues_stale(stale_ids)
            logger.info("Marked %d candidate issues as stale (> %d days old).", modified_count, cutoff_days)

        finish_time = datetime.now(timezone.utc)
        summary = {
            "issues_evaluated": len(stale_candidates),
            "issues_marked_stale": modified_count,
            "cutoff_days": cutoff_days,
            "execution_metadata": {
                "duration_seconds": (finish_time - start_time).total_seconds()
            }
        }

        job_repo.release_job_lock(job_id, status="completed", updates=summary)

        return {
            "job_id": job_id,
            "job_type": "cleanup",
            "status": "completed",
            **summary
        }


lifecycle_worker = LifecycleWorker()
