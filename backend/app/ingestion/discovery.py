"""
Bounded and diverse multi-platform issue discovery worker.
Populates shared candidate pool in MongoDB Atlas with rotating query topics,
checkpoint resumption, repository diversity limits, rate-limit awareness,
and dynamic allocation across GitHub, GitLab, Codeberg, and Bugzilla.
"""
import time
import logging
from datetime import datetime, timedelta, timezone
from typing import Dict, Any, Optional, List

from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo
from app.adapters import get_adapter, ADAPTERS, github_adapter
from app.adapters.base import RateLimitExceededError, SourceAPIError

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
        self._custom_adapter = adapter is not None
        self.adapter = adapter or github_adapter

    def run_discovery(
        self,
        max_issues_to_discover: Optional[int] = None,
        max_pages_per_query: Optional[int] = None,
        per_page: Optional[int] = None,
        resume: bool = True,
        force: bool = False,
        source: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Execute bounded, diverse candidate issue discovery across rotating topics and sources.
        - Enforces candidate pool bound (CANDIDATE_POOL_TARGET).
        - Enforces repository diversity limit (MAX_ISSUES_PER_REPO).
        - Saves checkpoints after every page for seamless resumption.
        - Respects rate limits, pausing safely if remaining budget is depleted.
        - Fault isolation: A failed source does not crash the others.
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
            if db.is_connected():
                job_repo.release_job_lock(job_id, status="completed", updates=summary)
            return {"job_id": job_id, "status": "completed", **summary}

        # Determine which sources to discover
        if self._custom_adapter:
            sources_to_run = [self.adapter]
        elif source and source.lower() != "all":
            sources_to_run = [get_adapter(source)]
        else:
            sources_to_run = [ADAPTERS["github"], ADAPTERS["gitlab"], ADAPTERS["codeberg"], ADAPTERS["bugzilla"]]

        total_discovered = 0
        total_inserted = 0
        total_updated = 0
        total_skipped = 0
        total_skipped_diversity = 0
        queries_run = []
        errors = []
        job_status = "completed"
        per_source_summary = {}

        # Calculate dynamic recent window cutoff
        recent_date = (start_time - timedelta(days=settings.recent_window_days)).strftime("%Y-%m-%d")

        # In-memory batch repo counts to enforce diversity limit during this run
        repo_counts: Dict[str, int] = {}

        for cur_adapter in sources_to_run:
            src_name = cur_adapter.source_name
            logger.info("Starting candidate discovery for platform: %s", src_name)

            # 1. Health check & Eligibility gate
            health = cur_adapter.check_health()
            if not health.get("ingestion_enabled"):
                logger.warning("Platform '%s' is not enabled for ingestion: %s", src_name, health.get("error_message"))
                per_source_summary[src_name] = {
                    "status": "skipped_unhealthy",
                    "reason": health.get("error_message") or "Health check failed",
                    "discovered": 0,
                    "inserted": 0,
                    "updated": 0,
                    "skipped": 0
                }
                if health.get("error_message"):
                    errors.append(f"[{src_name}] {health.get('error_message')}")
                continue

            # 2. Checkpoint resolution for this source
            start_topic_idx = 0
            start_page = 1
            if resume and db.is_connected():
                checkpoint = job_repo.get_latest_checkpoint("discovery")
                if checkpoint and checkpoint.get("source") == src_name:
                    start_topic_idx = checkpoint.get("topic_index", 0) % len(ROTATING_TOPICS)
                    start_page = checkpoint.get("next_page", 1)

            src_discovered = 0
            src_inserted = 0
            src_updated = 0
            src_skipped = 0
            src_skipped_diversity = 0

            # Reorder topics starting from checkpoint
            topics_to_run = list(enumerate(ROTATING_TOPICS))
            topics_to_run = topics_to_run[start_topic_idx:] + topics_to_run[:start_topic_idx]

            for topic_idx, (topic_key, topic_label, qualifier) in topics_to_run:
                if src_name == "github":
                    query = f"is:issue is:open no:assignee updated:>={recent_date} {qualifier}"
                elif src_name == "gitlab":
                    query = topic_key
                elif src_name == "codeberg":
                    query = topic_key
                else: # bugzilla
                    query = topic_key

                queries_run.append(f"[{src_name}] {query}")

                for page in range(start_page if topic_idx == start_topic_idx else 1, pages_per_query + 1):
                    if max_issues_to_discover and total_inserted >= max_issues_to_discover:
                        break
                    if (current_active_pool + total_inserted) >= target_pool_size:
                        break

                    try:
                        search_result = cur_adapter.search_issues(
                            query=query,
                            page=page,
                            per_page=items_per_page
                        )
                    except RateLimitExceededError as e:
                        logger.warning("[%s] Rate limit reached during discovery: %s", src_name, str(e))
                        errors.append(f"[{src_name}] RateLimitExceeded on '{topic_label}', p{page}: {str(e)}")
                        if db.is_connected():
                            job_repo.update_checkpoint(job_id, {
                                "source": src_name,
                                "topic_index": topic_idx,
                                "next_page": page,
                                "topic_name": topic_key,
                                "reset_timestamp": e.reset_timestamp,
                                "reason": "rate_limit_paused"
                            })
                        break
                    except SourceAPIError as e:
                        logger.warning("[%s] Source API error on topic '%s': %s", src_name, topic_label, str(e))
                        errors.append(f"[{src_name}] APIError on '{topic_label}', p{page}: {str(e)}")
                        break
                    except Exception as e:
                        logger.error("[%s] Unexpected discovery error: %s", src_name, str(e))
                        errors.append(f"[{src_name}] {str(e)}")
                        break

                    items = search_result.get("items", []) if isinstance(search_result, dict) else (search_result if isinstance(search_result, list) else [])
                    src_discovered += len(items)
                    total_discovered += len(items)

                    if not items:
                        break

                    for item in items:
                        # 1. Pull Request exclusion & Normalization
                        normalized = cur_adapter.normalize_issue(
                            item,
                            query_info={"query": query, "category": topic_label}
                        )
                        if not normalized:
                            src_skipped += 1
                            total_skipped += 1
                            continue

                        repo_full_name = normalized.get("repository_full_name") or f"{src_name}_general"

                        # 2. Repository Diversity Limit Check
                        if repo_full_name not in repo_counts and db.is_connected():
                            try:
                                repo_counts[repo_full_name] = issue_repo.count_by_repository(repo_full_name)
                            except Exception:
                                repo_counts[repo_full_name] = 0

                        if repo_counts.get(repo_full_name, 0) >= max_per_repo:
                            src_skipped_diversity += 1
                            total_skipped_diversity += 1
                            continue

                        # 3. Upsert into MongoDB Atlas Candidate Pool
                        if db.is_connected():
                            try:
                                saved = issue_repo.upsert_issue(normalized, preserve_unsupplied=True)
                                if saved.get("first_seen_at") == saved.get("last_seen_at"):
                                    src_inserted += 1
                                    total_inserted += 1
                                    repo_counts[repo_full_name] = repo_counts.get(repo_full_name, 0) + 1
                                else:
                                    src_updated += 1
                                    total_updated += 1
                            except Exception as e:
                                logger.error("[%s] Failed to upsert issue candidate: %s", src_name, str(e))
                                src_skipped += 1
                                total_skipped += 1
                        else:
                            src_inserted += 1
                            total_inserted += 1
                            repo_counts[repo_full_name] = repo_counts.get(repo_full_name, 0) + 1

                    # Checkpoint update
                    next_p = page + 1 if page < pages_per_query else 1
                    next_idx = topic_idx if page < pages_per_query else (topic_idx + 1) % len(ROTATING_TOPICS)
                    if db.is_connected():
                        job_repo.update_checkpoint(job_id, {
                            "source": src_name,
                            "topic_index": next_idx,
                            "next_page": next_p,
                            "topic_name": topic_key,
                            "issues_inserted_so_far": total_inserted,
                            "last_updated_at": datetime.now(timezone.utc).isoformat()
                        })

                    time.sleep(0.05)

            per_source_summary[src_name] = {
                "status": "completed",
                "discovered": src_discovered,
                "inserted": src_inserted,
                "updated": src_updated,
                "skipped": src_skipped,
                "skipped_diversity": src_skipped_diversity
            }

        # Calculate candidate distribution
        candidate_distribution = {}
        if db.is_connected():
            try:
                for s_key in ["github", "gitlab", "codeberg", "bugzilla"]:
                    candidate_distribution[s_key] = issue_repo.count_by_source(s_key)
            except Exception:
                pass

        finish_time = datetime.now(timezone.utc)
        if errors and total_inserted > 0:
            job_status = "partial_success"
        elif errors and total_inserted == 0 and not any(v.get("inserted", 0) > 0 for v in per_source_summary.values()):
            job_status = "failed"

        execution_summary = {
            "queries_executed": queries_run,
            "issues_discovered": total_discovered,
            "issues_inserted": total_inserted,
            "issues_updated": total_updated,
            "issues_skipped": total_skipped,
            "issues_skipped_diversity": total_skipped_diversity,
            "unique_repositories_in_batch": len(repo_counts),
            "per_source_summary": per_source_summary,
            "candidate_distribution": candidate_distribution,
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
