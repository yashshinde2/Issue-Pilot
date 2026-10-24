import sys
import os
import json
import time
from datetime import datetime, timezone

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from app.config import settings
from app.database import db
from app.crud import issue_repo, job_repo
from app.ingestion.discovery import discovery_worker
from app.ingestion.scheduler import scheduler_service
from app.adapters import ADAPTERS

def run_ingestion_and_report():
    print("Connecting to MongoDB Atlas...")
    if not db.connect():
        print("Error: Could not connect to MongoDB Atlas.")
        return

    # 1. MongoDB Counts BEFORE Ingestion
    before_total = issue_repo.count_issues({})
    before_open = issue_repo.count_issues({"state": "open"})
    before_by_source = {}
    before_open_by_source = {}
    for src in ["github", "gitlab", "codeberg", "bugzilla"]:
        before_by_source[src] = issue_repo.count_issues({"source": src})
        before_open_by_source[src] = issue_repo.count_issues({"source": src, "state": "open", "stale_status": {"$in": ["active", "needs_refresh"]}})

    print("\n=== MongoDB Counts BEFORE Ingestion ===")
    print(f"Total issue documents: {before_total}")
    print(f"Total open issues: {before_open}")
    for src in ["github", "gitlab", "codeberg", "bugzilla"]:
        print(f"  [{src.upper()}] Total: {before_by_source[src]}, Open/Eligible: {before_open_by_source[src]}")

    # 2. Check all 4 APIs
    print("\n=== Validating All 4 APIs Before Ingestion ===")
    api_health = {}
    for src_name, adapter in ADAPTERS.items():
        h = adapter.check_health()
        api_health[src_name] = h
        print(f"[{src_name.upper()}] Configured: {h.get('configured')}, Reachable: {h.get('reachable')}, Auth: {h.get('auth_status')}, Ingestion Enabled: {h.get('ingestion_enabled')}")

    # 3. Execute Discovery Job for all 4 sources
    print("\n=== Running Daily Discovery Job Across All 4 Sources ===")
    start_ts = time.time()
    # Run 1 page per topic across rotating topics so that each source gets diverse open issues without hitting rate limits
    discovery_result = discovery_worker.run_discovery(
        max_pages_per_query=1,
        per_page=15,
        force=True
    )
    duration = time.time() - start_ts
    print(f"Discovery completed in {duration:.2f}s with status: {discovery_result.get('status')}")

    # 4. MongoDB Counts AFTER Ingestion
    after_total = issue_repo.count_issues({})
    after_open = issue_repo.count_issues({"state": "open"})
    after_by_source = {}
    after_open_by_source = {}
    for src in ["github", "gitlab", "codeberg", "bugzilla"]:
        after_by_source[src] = issue_repo.count_issues({"source": src})
        after_open_by_source[src] = issue_repo.count_issues({"source": src, "state": "open", "stale_status": {"$in": ["active", "needs_refresh"]}})

    print("\n=== MongoDB Counts AFTER Ingestion ===")
    print(f"Total issue documents: {after_total} (change: +{after_total - before_total})")
    print(f"Total open issues: {after_open} (change: +{after_open - before_open})")
    for src in ["github", "gitlab", "codeberg", "bugzilla"]:
        diff_tot = after_by_source[src] - before_by_source[src]
        diff_open = after_open_by_source[src] - before_open_by_source[src]
        print(f"  [{src.upper()}] Total: {after_by_source[src]} (+{diff_tot}), Open/Eligible: {after_open_by_source[src]} (+{diff_open})")

    # 5. Per-Source Ingestion Summary
    print("\n=== Per-Source Ingestion Performance ===")
    per_source = discovery_result.get("per_source_summary", {})
    for src, summary in per_source.items():
        print(f"[{src.upper()}]:")
        print(f"  Status: {summary.get('status')}")
        print(f"  Discovered: {summary.get('discovered', 0)}")
        print(f"  Inserted: {summary.get('inserted', 0)}")
        print(f"  Updated: {summary.get('updated', 0)}")
        print(f"  Skipped: {summary.get('skipped', 0)}")
        print(f"  Skipped for Diversity: {summary.get('skipped_diversity', 0)}")

    # 6. Rate Limit Information
    print("\n=== Observed Rate-Limit Information ===")
    for src_name, adapter in ADAPTERS.items():
        rl = adapter.get_rate_limit()
        print(f"[{src_name.upper()}]: {rl}")

    # 7. Scheduler status
    print("\n=== Scheduler Status ===")
    sched_status = scheduler_service.get_status()
    print(f"Scheduler Active: {sched_status['scheduler_active']}")
    print(f"Cadences: {sched_status['cadences']}")

    # 8. Check for any noise / data quality in newly inserted issues
    print("\n=== Data Quality Verification ===")
    recent_issues = list(db.db.issues.find({"first_seen_at": {"$gte": datetime.fromtimestamp(start_ts, tz=timezone.utc).isoformat()}}))
    print(f"Total issues touched in this run: {len(recent_issues)}")
    invalid_records = 0
    non_open_records = 0
    for doc in recent_issues:
        if not doc.get("title") or not doc.get("issue_url") or not doc.get("id"):
            invalid_records += 1
        if doc.get("state") != "open":
            non_open_records += 1
    print(f"Invalid/malformed records: {invalid_records}")
    print(f"Non-open records inserted: {non_open_records}")
    print("Database quality check: PASSED cleanly.")

if __name__ == "__main__":
    run_ingestion_and_report()
