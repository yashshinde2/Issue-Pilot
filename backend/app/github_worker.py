"""
GitHub Worker module for Issue Pilot.
Provides backward-compatible interfaces to the modular ingestion package:
- DiscoveryWorker (app.ingestion.discovery)
- RefreshWorker (app.ingestion.refresher)
- LifecycleWorker (app.ingestion.lifecycle)
- GitHubAdapter (app.adapters.github_adapter)
"""
from typing import Dict, Any, Optional, List
from app.adapters.github_adapter import (
    github_adapter,
    TECH_KEYWORDS
)
from app.ingestion.discovery import discovery_worker, ROTATING_TOPICS
from app.ingestion.refresher import refresh_worker
from app.ingestion.lifecycle import lifecycle_worker


class GitHubWorker:
    def __init__(self, adapter=None):
        self.adapter = adapter or github_adapter

    def run_ingestion_job(self, max_pages_per_query: int = 1, per_page: int = 15, resume: bool = False) -> Dict[str, Any]:
        """
        Executes bounded discovery job across rotating topics.
        Maintains backward compatibility with Milestone 1 & 2 routes.
        """
        return discovery_worker.run_discovery(
            max_pages_per_query=max_pages_per_query,
            per_page=per_page,
            resume=resume,
            force=True
        )

    def run_refresh_job(self, batch_size: int = 50) -> Dict[str, Any]:
        """Refreshes a bounded batch of stored candidates."""
        return refresh_worker.run_refresh(batch_size=batch_size, force=True)

    def run_cleanup_job(self, older_than_days: Optional[int] = None) -> Dict[str, Any]:
        """Evaluates and marks stale candidates without deleting historical records."""
        return lifecycle_worker.run_cleanup(older_than_days=older_than_days, force=True)

    def normalize_github_issue(self, item: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """Normalize raw item via authoritative adapter."""
        return self.adapter.normalize_issue(item)


github_worker = GitHubWorker()
