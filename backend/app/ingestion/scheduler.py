"""
Background scheduler service for Issue Pilot ingestion and refresh pipelines.
Provides in-process threading-based scheduling or Redis/RQ hooks.
Accurately reports scheduler status (never claims active if not running).
"""
import time
import threading
import logging
from datetime import datetime, timedelta, timezone
from typing import Dict, Any, Optional

from app.config import settings
from app.ingestion.discovery import discovery_worker
from app.ingestion.refresher import refresh_worker
from app.ingestion.lifecycle import lifecycle_worker

logger = logging.getLogger(__name__)


class SchedulerService:
    def __init__(self):
        self._thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._is_running: bool = False

        self.last_run_times: Dict[str, Optional[str]] = {
            "discovery": None,
            "refresh": None,
            "cleanup": None
        }
        self.next_run_times: Dict[str, Optional[str]] = {
            "discovery": None,
            "refresh": None,
            "cleanup": None
        }
        self.run_counts: Dict[str, int] = {
            "discovery": 0,
            "refresh": 0,
            "cleanup": 0
        }

    @property
    def is_running(self) -> bool:
        return self._is_running and self._thread is not None and self._thread.is_alive()

    def start(self) -> bool:
        """Start the background scheduler thread if not already running."""
        if self.is_running:
            logger.info("Scheduler service is already running.")
            return False

        self._stop_event.clear()
        self._is_running = True
        self._thread = threading.Thread(target=self._run_loop, name="IssuePilot-Scheduler", daemon=True)
        self._thread.start()
        logger.info("Issue Pilot background scheduler service started.")
        return True

    def stop(self, timeout: float = 5.0) -> bool:
        """Stop the background scheduler thread."""
        if not self.is_running:
            self._is_running = False
            return True

        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=timeout)
        self._is_running = False
        logger.info("Issue Pilot background scheduler service stopped.")
        return True

    def get_status(self) -> Dict[str, Any]:
        """
        Accurate status report of the scheduler service.
        Truthful reporting: scheduler_active is False if the thread is not currently running.
        """
        active = self.is_running
        now_dt = datetime.now(timezone.utc)

        cadences = {
            "discovery_interval_hours": settings.discovery_cadence_hours,
            "refresh_interval_days": settings.refresh_cadence_days,
            "cleanup_interval_days": settings.refresh_cadence_days,
            "target_pool_size": settings.candidate_pool_target,
            "recent_window_days": settings.recent_window_days,
            "older_window_days": settings.older_window_days
        }

        return {
            "scheduler_active": active,
            "engine": "in_process_thread",
            "cadences": cadences,
            "last_run_times": self.last_run_times,
            "next_run_times": self.next_run_times if active else {k: None for k in self.next_run_times},
            "run_counts": self.run_counts
        }

    def run_all_now(self) -> Dict[str, Any]:
        """Manual orchestration: execute discovery, refresh, and cleanup in sequence."""
        logger.info("Executing full manual ingestion cycle...")
        disc_res = discovery_worker.run_discovery()
        ref_res = refresh_worker.run_refresh()
        clean_res = lifecycle_worker.run_cleanup()

        now_iso = datetime.now(timezone.utc).isoformat()
        self.last_run_times["discovery"] = now_iso
        self.last_run_times["refresh"] = now_iso
        self.last_run_times["cleanup"] = now_iso

        self.run_counts["discovery"] += 1
        self.run_counts["refresh"] += 1
        self.run_counts["cleanup"] += 1

        return {
            "cycle_completed_at": now_iso,
            "discovery": disc_res,
            "refresh": ref_res,
            "cleanup": clean_res
        }

    def _run_loop(self):
        """Scheduler periodic loop with configurable cadences."""
        # Calculate intervals in seconds
        disc_interval = max(60, settings.discovery_cadence_hours * 3600)
        ref_interval = max(60, settings.refresh_cadence_days * 86400)
        clean_interval = max(60, settings.refresh_cadence_days * 86400)

        last_disc_time = time.time() - disc_interval # trigger on start
        last_ref_time = time.time() - ref_interval
        last_clean_time = time.time() - clean_interval

        while not self._stop_event.is_set():
            now = time.time()
            now_dt = datetime.now(timezone.utc)

            # Update next expected run times
            self.next_run_times["discovery"] = (datetime.fromtimestamp(last_disc_time + disc_interval, tz=timezone.utc)).isoformat()
            self.next_run_times["refresh"] = (datetime.fromtimestamp(last_ref_time + ref_interval, tz=timezone.utc)).isoformat()
            self.next_run_times["cleanup"] = (datetime.fromtimestamp(last_clean_time + clean_interval, tz=timezone.utc)).isoformat()

            # 1. Daily Recent Discovery
            if (now - last_disc_time) >= disc_interval:
                try:
                    logger.info("Scheduler triggering daily discovery job...")
                    discovery_worker.run_discovery()
                    last_disc_time = now
                    self.last_run_times["discovery"] = now_dt.isoformat()
                    self.run_counts["discovery"] += 1
                except Exception as e:
                    logger.error("Scheduler discovery job error: %s", str(e))

            # 2. Daily Bounded Candidate Refresh
            if (now - last_ref_time) >= ref_interval:
                try:
                    logger.info("Scheduler triggering candidate refresh job...")
                    refresh_worker.run_refresh()
                    last_ref_time = now
                    self.last_run_times["refresh"] = now_dt.isoformat()
                    self.run_counts["refresh"] += 1
                except Exception as e:
                    logger.error("Scheduler refresh job error: %s", str(e))

            # 3. Older Candidate Rotation / Stale Evaluation
            if (now - last_clean_time) >= clean_interval:
                try:
                    logger.info("Scheduler triggering lifecycle cleanup job...")
                    lifecycle_worker.run_cleanup()
                    last_clean_time = now
                    self.last_run_times["cleanup"] = now_dt.isoformat()
                    self.run_counts["cleanup"] += 1
                except Exception as e:
                    logger.error("Scheduler cleanup job error: %s", str(e))

            # Sleep in small increments to respond quickly to stop signals
            self._stop_event.wait(timeout=10.0)

        self._is_running = False


# Default scheduler singleton
scheduler_service = SchedulerService()
