"""
Bounded and diverse GitHub issue discovery worker.
Populates shared candidate pool in MongoDB Atlas with rotating query topics,
checkpoint resumption, repository diversity limits, and rate-limit awareness.
"""
import time
import logging
from datetime import datetime, timedelta, timezone
from typing import Dict, Any, Optional, List

from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo
from app.adapters.github_adapter import github_adapter, RateLimitExceededError, SourceAPIError

logger = logging.getLogger(__name__)

# Diverse rotating query topics across technologies, frameworks, and categories
ROTATING_TOPICS = [
    ("python", "Python", "language:Python"),
    ("typescript", "TypeScript", "language:TypeScript"),
    ("javascript", "JavaScript", "language:JavaScript"),
    ("react", "React", "react in:title,body"),
    ("cpp", "C++", "language:C++"),
    ("go", "Go", "language:Go"),
    ("rust", "Rust", "language:Rust"),
    ("java", "Java", "language:Java"),
    ("good_first_issue", "Good First Issue", "label:\"good first issue\" OR label:good-first-issue"),
    ("help_wanted", "Help Wanted", "label:\"help wanted\""),
    ("bug", "Bugs", "label:bug"),
    ("documentation", "Docs", "label:documentation OR label:docs"),
    ("performance", "Performance", "label:performance OR performance in:title"),
    ("security", "Security", "label:security OR vulnerability in:title")
]


class DiscoveryWorker:
    def __init__(self, adapter=None):
        self.adapter = adapter or github_adapter

    def run_discovery(
        self,
        max_issues_to_discover: Optional[int] = None,
        max_pages_per_query: Optional[int] = None,
        per_page: Optional[int] = None,
        resume: bool = True,
        force: bool = False
    ) -> Dict[str, Any]:
        """
        Execute bounded, diverse candidate issue discovery across rotating topics.
        - Enforces candidate pool bound (CANDIDATE_POOL_TARGET).
        - Enforces repository diversity limit (MAX_ISSUES_PER_REPO).
        - Saves checkpoints after every page for seamless resumption.
        - Respects rate limits, pausing safely if remaining budget is depleted.
        """
        if db.is_connected():
            job_id = job_repo.acquire_job_lock("discovery", force=force)
            if not job_id:
                return {
                    "status": "locked",
                    "message": "Another discovery job is currently in progress. Please wait for it to complete or release the lock.",
                    "job_id": None
                }
        else:
            job_id = f"discovery_offline_{int(time.time())}"

        start_time = datetime.now(timezone.utc)
        target_pool_size = settings.candidate_pool_target
        max_per_repo = settings.max_issues_per_repo
        pages_per_query = max_pages_per_query or settings.max_search_pages_per_run
        items_per_page = per_page or settings.per_page_count

        # Check existing active candidate pool size
        current_active_pool = 0
        if db.is_connected():
            try:
                current_active_pool = issue_repo.count_issues({
                    "state": "open",
                    "stale_status": {"$in": ["active", "needs_refresh"]}
                })
            except Exception as e:
                logger.warning("Could not check active candidate count: %s", str(e))

        if current_active_pool >= target_pool_size:
            logger.info("Candidate pool target reached (%d/%d). Halting discovery.", current_active_pool, target_pool_size)
            summary = {
                "issues_discovered": 0,
                "issues_inserted": 0,
                "issues_updated": 0,
                "issues_skipped": 0,
                "issues_skipped_diversity": 0,
                "active_pool_size": current_active_pool,
                "target_pool_size": target_pool_size,
                "note": "Candidate pool target reached; discovery halted."
            }
            job_repo.release_job_lock(job_id, status="completed", updates=summary)
            return {"job_id": job_id, "status": "completed", **summary}

        # Resolve checkpoint for resumption
        start_topic_idx = 0
        start_page = 1
        if resume and db.is_connected():
            checkpoint = job_repo.get_latest_checkpoint("discovery")
            if checkpoint:
                start_topic_idx = checkpoint.get("topic_index", 0) % len(ROTATING_TOPICS)
                start_page = checkpoint.get("next_page", 1)
                logger.info("Resuming discovery job from topic index %d (%s), page %d",
                            start_topic_idx, ROTATING_TOPICS[start_topic_idx][0], start_page)

        total_discovered = 0
        total_inserted = 0
        total_updated = 0
        total_skipped = 0
        total_skipped_diversity = 0
        queries_run = []
        errors = []
        job_status = "completed"

        # Calculate dynamic recent window cutoff
        recent_date = (start_time - timedelta(days=settings.recent_window_days)).strftime("%Y-%m-%d")

        # In-memory batch repo counts to enforce diversity limit during this run
        repo_counts: Dict[str, int] = {}

        # Reorder topics starting from checkpoint
        topics_to_run = list(enumerate(ROTATING_TOPICS))
        topics_to_run = topics_to_run[start_topic_idx:] + topics_to_run[:start_topic_idx]

        for topic_idx, (topic_key, topic_label, qualifier) in topics_to_run:
            query = f"is:issue is:open no:assignee updated:>={recent_date} {qualifier}"
            queries_run.append(query)
            logger.info("Executing discovery query for topic '%s': %s", topic_label, query)

            for page in range(start_page if topic_idx == start_topic_idx else 1, pages_per_query + 1):
                # Check bounds
                if max_issues_to_discover and total_inserted >= max_issues_to_discover:
                    break
                if (current_active_pool + total_inserted) >= target_pool_size:
                    logger.info("Candidate pool target reached (%d). Stopping.", target_pool_size)
                    break

                try:
                    search_result = self.adapter.search_issues(
                        query=query,
                        page=page,
                        per_page=items_per_page
                    )
                except RateLimitExceededError as e:
                    logger.warning("GitHub rate limit reached during discovery: %s", str(e))
                    errors.append(f"RateLimitExceeded on topic '{topic_label}', page {page}: {str(e)}")
                    job_status = "rate_limited"
                    # Checkpoint immediately so next scheduled or manual run resumes here
                    if db.is_connected():
                        job_repo.update_checkpoint(job_id, {
                            "topic_index": topic_idx,
                            "next_page": page,
                            "topic_name": topic_key,
                            "reset_timestamp": e.reset_timestamp,
                            "reason": "rate_limit_paused"
                        })
                    break
                except SourceAPIError as e:
                    logger.warning("GitHub source API error on topic '%s': %s", topic_label, str(e))
                    errors.append(f"APIError on topic '{topic_label}', page {page}: {str(e)}")
                    break
                except Exception as e:
                    logger.error("Unexpected discovery error: %s", str(e))
                    errors.append(str(e))
                    break

                items = search_result.get("items", []) if isinstance(search_result, dict) else (search_result if isinstance(search_result, list) else [])
                total_discovered += len(items)

                if not items:
                    break # No more results for this query

                for item in items:
                    # 1. Pull Request exclusion & Normalization
                    normalized = self.adapter.normalize_issue(
                        item,
                        query_info={"query": query, "category": topic_label}
                    )
                    if not normalized:
                        total_skipped += 1
                        continue

                    repo_full_name = normalized.get("repository_full_name", "")

                    # 2. Repository Diversity Limit Check
                    if repo_full_name not in repo_counts and db.is_connected():
                        try:
                            repo_counts[repo_full_name] = issue_repo.count_by_repository(repo_full_name)
                        except Exception:
                            repo_counts[repo_full_name] = 0

                    if repo_counts.get(repo_full_name, 0) >= max_per_repo:
                        total_skipped_diversity += 1
                        continue

                    # 3. Upsert into MongoDB Atlas Candidate Pool
                    if db.is_connected():
                        try:
                            saved = issue_repo.upsert_issue(normalized, preserve_unsupplied=True)
                            # Check whether inserted or updated
                            if saved.get("first_seen_at") == saved.get("last_seen_at"):
                                total_inserted += 1
                                repo_counts[repo_full_name] = repo_counts.get(repo_full_name, 0) + 1
                            else:
                                total_updated += 1
                        except Exception as e:
                            logger.error("Failed to upsert issue candidate: %s", str(e))
                            total_skipped += 1
                    else:
                        total_inserted += 1
                        repo_counts[repo_full_name] = repo_counts.get(repo_full_name, 0) + 1

                # Update checkpoint after each successfully processed page
                next_p = page + 1 if page < pages_per_query else 1
                next_idx = topic_idx if page < pages_per_query else (topic_idx + 1) % len(ROTATING_TOPICS)
                if db.is_connected():
                    job_repo.update_checkpoint(job_id, {
                        "topic_index": next_idx,
                        "next_page": next_p,
                        "topic_name": topic_key,
                        "issues_inserted_so_far": total_inserted,
                        "last_updated_at": datetime.now(timezone.utc).isoformat()
                    })

                # Modest pause between pages to adhere to secondary rate limits
                time.sleep(0.1)

            if job_status == "rate_limited":
                break

        finish_time = datetime.now(timezone.utc)
        if errors and total_inserted > 0 and job_status != "rate_limited":
            job_status = "partial_success"
        elif errors and total_inserted == 0 and job_status != "rate_limited":
            job_status = "failed"

        execution_summary = {
            "queries_executed": queries_run,
            "issues_discovered": total_discovered,
            "issues_inserted": total_inserted,
            "issues_updated": total_updated,
            "issues_skipped": total_skipped,
            "issues_skipped_diversity": total_skipped_diversity,
            "unique_repositories_in_batch": len(repo_counts),
            "errors": errors,
            "rate_limit_info": self.adapter.rate_limit_state,
            "execution_metadata": {
                "duration_seconds": (finish_time - start_time).total_seconds(),
                "target_pool_size": target_pool_size,
                "max_per_repo": max_per_repo
            }
        }

        if db.is_connected():
            job_repo.release_job_lock(job_id, status=job_status, updates=execution_summary)

        return {
            "job_id": job_id,
            "job_type": "discovery",
            "status": job_status,
            **execution_summary
        }


discovery_worker = DiscoveryWorker()
