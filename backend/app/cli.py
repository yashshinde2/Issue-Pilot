"""
Issue Pilot CLI management tool for manual job execution, pool inspection, and scheduler control.
Usage:
    python -m app.cli discover [--max-issues N] [--pages N] [--per-page N] [--resume]
    python -m app.cli refresh [--batch-size N] [--max-age-days N]
    python -m app.cli cleanup [--older-than-days N]
    python -m app.cli run-all
    python -m app.cli status
    python -m app.cli scheduler [--daemon]
    python -m app.cli jobs [--limit N] [--job-id ID]
"""
import sys
import json
import time
import argparse
import logging
from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo
from app.adapters.github_adapter import github_adapter
from app.ingestion.discovery import discovery_worker
from app.ingestion.refresher import refresh_worker
from app.ingestion.lifecycle import lifecycle_worker
from app.ingestion.scheduler import scheduler_service

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("issue_pilot.cli")


def _ensure_db():
    if not db.is_connected():
        logger.info("Connecting to MongoDB Atlas...")
        connected = db.connect()
        if not connected:
            logger.warning("Warning: Could not connect to MongoDB Atlas. Check your .env credentials.")
            return False
    return True


def cmd_discover(args):
    _ensure_db()
    logger.info("Starting candidate issue discovery (max_issues=%s, pages=%s, resume=%s)...",
                args.max_issues, args.pages, args.resume)
    res = discovery_worker.run_discovery(
        max_issues_to_discover=args.max_issues,
        max_pages_per_query=args.pages,
        per_page=args.per_page,
        resume=args.resume,
        force=args.force
    )
    print(json.dumps(res, indent=2, default=str))


def cmd_refresh(args):
    _ensure_db()
    logger.info("Starting candidate issue refresh (batch_size=%d, max_age_days=%s)...",
                args.batch_size, args.max_age_days)
    res = refresh_worker.run_refresh(
        batch_size=args.batch_size,
        max_age_days=args.max_age_days,
        force=args.force
    )
    print(json.dumps(res, indent=2, default=str))


def cmd_cleanup(args):
    _ensure_db()
    logger.info("Starting stale candidate cleanup (older_than_days=%s)...", args.older_than_days)
    res = lifecycle_worker.run_cleanup(
        older_than_days=args.older_than_days,
        force=args.force
    )
    print(json.dumps(res, indent=2, default=str))


def cmd_run_all(args):
    _ensure_db()
    logger.info("Running full ingestion pipeline: Discovery -> Refresh -> Stale Cleanup...")
    res = scheduler_service.run_all_now()
    print(json.dumps(res, indent=2, default=str))


def cmd_status(args):
    _ensure_db()
    pool_metrics = {}
    if db.is_connected():
        pool_metrics = issue_repo.get_pool_metrics()

    sched_status = scheduler_service.get_status()
    rate_limit = github_adapter.get_rate_limit()

    output = {
        "database_connected": db.is_connected(),
        "database_name": settings.mongodb_database,
        "candidate_pool": pool_metrics,
        "scheduler": sched_status,
        "github_api": {
            "token_configured": bool(settings.github_token),
            "rate_limit": rate_limit
        }
    }
    print(json.dumps(output, indent=2, default=str))


def cmd_scheduler(args):
    _ensure_db()
    logger.info("Starting Issue Pilot background scheduler...")
    scheduler_service.start()
    print(json.dumps(scheduler_service.get_status(), indent=2, default=str))

    if args.daemon:
        try:
            logger.info("Scheduler running in daemon mode. Press Ctrl+C to stop.")
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            logger.info("Stopping scheduler...")
            scheduler_service.stop()
            logger.info("Scheduler stopped.")


def cmd_jobs(args):
    _ensure_db()
    if args.job_id:
        job = job_repo.get_job(args.job_id)
        print(json.dumps(job, indent=2, default=str))
    else:
        jobs = job_repo.list_jobs(limit=args.limit, job_type=args.type)
        print(json.dumps({"count": len(jobs), "jobs": jobs}, indent=2, default=str))


def main():
    parser = argparse.ArgumentParser(description="Issue Pilot Ingestion & Candidate Pool Management CLI")
    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    # discover
    p_disc = subparsers.add_parser("discover", help="Discover candidates across rotating topics")
    p_disc.add_argument("--max-issues", type=int, default=None, help="Max issues to discover in this run")
    p_disc.add_argument("--pages", type=int, default=settings.max_search_pages_per_run, help="Max pages per query")
    p_disc.add_argument("--per-page", type=int, default=settings.per_page_count, help="Items per page")
    p_disc.add_argument("--resume", action="store_true", default=True, help="Resume from last checkpoint")
    p_disc.add_argument("--no-resume", dest="resume", action="store_false", help="Start from beginning")
    p_disc.add_argument("--force", action="store_true", default=False, help="Bypass concurrency locks")

    # refresh
    p_ref = subparsers.add_parser("refresh", help="Refresh a batch of active candidates")
    p_ref.add_argument("--batch-size", type=int, default=50, help="Number of candidates to refresh")
    p_ref.add_argument("--max-age-days", type=int, default=settings.refresh_cadence_days, help="Age in days before refresh")
    p_ref.add_argument("--force", action="store_true", default=False, help="Bypass concurrency locks")

    # cleanup
    p_clean = subparsers.add_parser("cleanup", help="Evaluate and mark stale candidates")
    p_clean.add_argument("--older-than-days", type=int, default=settings.older_window_days, help="Cutoff days for stale")
    p_clean.add_argument("--force", action="store_true", default=False, help="Bypass concurrency locks")

    # run-all
    subparsers.add_parser("run-all", help="Run full pipeline: Discovery -> Refresh -> Cleanup")

    # status
    subparsers.add_parser("status", help="Print pool metrics, diversity, scheduler, and rate limits")

    # scheduler
    p_sched = subparsers.add_parser("scheduler", help="Run background scheduler")
    p_sched.add_argument("--daemon", action="store_true", default=False, help="Run continuously in foreground")

    # jobs
    p_jobs = subparsers.add_parser("jobs", help="List or inspect ingestion jobs")
    p_jobs.add_argument("--limit", type=int, default=20, help="Number of jobs to list")
    p_jobs.add_argument("--type", type=str, default=None, help="Filter by job type")
    p_jobs.add_argument("--job-id", type=str, default=None, help="Inspect specific job ID")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(1)

    cmds = {
        "discover": cmd_discover,
        "refresh": cmd_refresh,
        "cleanup": cmd_cleanup,
        "run-all": cmd_run_all,
        "status": cmd_status,
        "scheduler": cmd_scheduler,
        "jobs": cmd_jobs
    }

    cmd_fn = cmds.get(args.command)
    if cmd_fn:
        cmd_fn(args)


if __name__ == "__main__":
    main()
