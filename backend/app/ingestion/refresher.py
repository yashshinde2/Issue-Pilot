"""
Candidate refresh worker.
Refreshes a bounded batch of stored issue candidates directly from GitHub API,
verifying open/closed status, updating comments/assignees, preserving existing ranking evidence,
and recording status and rate limits.
"""
import time
import logging
from datetime import datetime, timezone
from typing import Dict, Any, Optional, List

from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo
from app.adapters.github_adapter import github_adapter, RateLimitExceededError, SourceAPIError

logger = logging.getLogger(__name__)


class RefreshWorker:
    def __init__(self, adapter=None):
        self.adapter = adapter or github_adapter

    def run_refresh(
        self,
        batch_size: int = 50,
        max_age_days: Optional[int] = None,
        force: bool = False
    ) -> Dict[str, Any]:
        """
        Refresh a bounded batch of stored issue candidates.
        - Prioritizes candidates with oldest last_seen_at or marked 'needs_refresh'.
        - Verifies individual issue state against official GitHub API.
        - Marks closed issues as 'closed' (kept for history, excluded from recommendations).
        - Marks missing (404) issues as 'unavailable'.
        - Preserves ranking evidence if API response lacks replacement data.
        """
        job_id = job_repo.acquire_job_lock("refresh")
        if not job_id and not force:
            return {
                "status": "locked",
                "message": "Another refresh job is currently running.",
                "job_id": None
            }
        elif not job_id and force:
            job_id = f"refresh_forced_{int(time.time())}"

        start_time = datetime.now(timezone.utc)
        refresh_age = max_age_days or settings.refresh_cadence_days

        if not db.is_connected():
            job_repo.release_job_lock(job_id, status="failed", updates={"errors": ["MongoDB connection unavailable"]})
            return {"job_id": job_id, "status": "failed", "message": "MongoDB connection unavailable"}

        candidates = issue_repo.get_candidates_for_refresh(limit=batch_size, max_age_days=refresh_age)
        if not candidates:
            summary = {
                "issues_refreshed": 0,
                "issues_marked_closed": 0,
                "issues_marked_unavailable": 0,
                "message": "No candidates currently require refresh."
            }
            job_repo.release_job_lock(job_id, status="completed", updates=summary)
            return {"job_id": job_id, "status": "completed", **summary}

        total_refreshed = 0
        total_closed = 0
        total_unavailable = 0
        errors = []
        job_status = "completed"

        for candidate in candidates:
            repo_owner = candidate.get("repository_owner")
            repo_name = candidate.get("repository_name")
            repo_full_name = candidate.get("repository_full_name", "")
            issue_number = candidate.get("issue_number")

            if not repo_owner or not repo_name:
                parts = repo_full_name.split("/")
                if len(parts) == 2:
                    repo_owner, repo_name = parts
                else:
                    logger.warning("Invalid repo identity on candidate %s", candidate.get("id"))
                    continue

            try:
                # Query GitHub for canonical current state
                fresh_data = self.adapter.get_issue(repo_owner, repo_name, issue_number)

                if fresh_data is None:
                    # 404 on GitHub -> mark unavailable
                    issue_repo.mark_issue_unavailable(candidate["id"])
                    total_unavailable += 1
                    continue

                fresh_state = (fresh_data.get("state") or "open").lower()
                if fresh_state == "closed":
                    # Mark closed with timestamp (kept for history, excluded from recommendations)
                    closed_at = fresh_data.get("closed_at")
                    issue_repo.mark_issue_closed(candidate["id"], closed_at=closed_at)
                    total_closed += 1
                else:
                    # Normalize fresh data
                    normalized = self.adapter.normalize_issue(fresh_data)
                    if not normalized:
                        # e.g., converted to PR
                        issue_repo.mark_issue_closed(candidate["id"])
                        total_closed += 1
                        continue

                    # Preserve ranking evidence from existing record if fresh data omitted them
                    for evidence_field in ["difficulty_evidence", "responsiveness_evidence", "health_evidence", "freshness_evidence"]:
                        if evidence_field in candidate and (not normalized.get(evidence_field) or not normalized[evidence_field].get("available", True)):
                            normalized[evidence_field] = candidate[evidence_field]

                    # Preserve technologies if existing had higher relevance or detected list
                    if candidate.get("technologies") and not normalized.get("technologies"):
                        normalized["technologies"] = candidate["technologies"]
                        normalized["detected_technologies"] = candidate.get("detected_technologies", [])

                    now_iso = datetime.now(timezone.utc).isoformat()
                    normalized["last_verified_at"] = now_iso
                    normalized["last_seen_at"] = now_iso
                    normalized["stale_status"] = "active"
                    normalized["refresh_status"] = "active"

                    issue_repo.upsert_issue(normalized, preserve_unsupplied=True)
                    total_refreshed += 1

                # Modest pause
                time.sleep(0.2)

            except RateLimitExceededError as e:
                logger.warning("Rate limit reached during refresh: %s", str(e))
                errors.append(str(e))
                job_status = "rate_limited"
                job_repo.update_checkpoint(job_id, {
                    "last_candidate_id": candidate.get("id"),
                    "reason": "rate_limit_paused"
                })
                break
            except Exception as e:
                logger.error("Error refreshing candidate %s: %s", candidate.get("id"), str(e))
                errors.append(f"Candidate {candidate.get('id')}: {str(e)}")

        finish_time = datetime.now(timezone.utc)
        if errors and total_refreshed > 0 and job_status != "rate_limited":
            job_status = "partial_success"
        elif errors and total_refreshed == 0 and job_status != "rate_limited":
            job_status = "failed"

        execution_summary = {
            "issues_considered": len(candidates),
            "issues_updated": total_refreshed,
            "issues_marked_closed": total_closed,
            "issues_marked_unavailable": total_unavailable,
            "errors": errors,
            "rate_limit_info": self.adapter.rate_limit_state,
            "execution_metadata": {
                "duration_seconds": (finish_time - start_time).total_seconds(),
                "batch_size": batch_size,
                "max_age_days": refresh_age
            }
        }

        job_repo.release_job_lock(job_id, status=job_status, updates=execution_summary)

        return {
            "job_id": job_id,
            "job_type": "refresh",
            "status": job_status,
            **execution_summary
        }


refresh_worker = RefreshWorker()
