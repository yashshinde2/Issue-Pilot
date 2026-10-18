"""
Base adapter interface for issue source providers (GitHub, GitLab, etc.).
Allows extensible source ingestion for future platforms while keeping platform-specific
logic decoupled from the ranking engine and core storage.
"""
from abc import ABC, abstractmethod
from typing import Dict, Any, Optional, List


class SourceAPIError(Exception):
    """Base exception for issue source API errors."""
    def __init__(self, message: str, status_code: Optional[int] = None, details: Optional[Dict[str, Any]] = None):
        super().__init__(message)
        self.status_code = status_code
        self.details = details or {}


class RateLimitExceededError(SourceAPIError):
    """Raised when the issue source API rate limit has been exceeded or remaining budget is too low."""
    def __init__(self, message: str, reset_timestamp: Optional[int] = None, remaining: int = 0):
        super().__init__(message, status_code=429)
        self.reset_timestamp = reset_timestamp
        self.remaining = remaining


class TransientAPIError(SourceAPIError):
    """Raised on transient network or 5xx server errors that warrant exponential backoff retry."""
    pass


class AuthenticationError(SourceAPIError):
    """Raised on invalid credentials or permissions (HTTP 401/403 forbidden)."""
    pass


class ResourceNotFoundError(SourceAPIError):
    """Raised when an issue or repository cannot be found (HTTP 404)."""
    pass


class BaseIssueSourceAdapter(ABC):
    """
    Abstract interface for issue discovery and state verification source adapters.
    """

    @property
    @abstractmethod
    def source_name(self) -> str:
        """Name of the issue source provider (e.g. 'github')."""
        pass

    @abstractmethod
    def search_issues(
        self,
        query: str,
        page: int = 1,
        per_page: int = 30,
        sort: str = "updated",
        order: str = "desc"
    ) -> Dict[str, Any]:
        """
        Execute issue discovery search query.
        Returns dict containing:
          - 'total_count': total matching items
          - 'items': list of raw issue items
          - 'rate_limit': current rate limit metadata
        """
        pass

    @abstractmethod
    def get_issue(self, owner: str, repo: str, issue_number: int) -> Optional[Dict[str, Any]]:
        """
        Retrieve individual issue details for candidate refresh.
        Returns raw issue dictionary or None if not found.
        """
        pass

    @abstractmethod
    def get_rate_limit(self) -> Dict[str, Any]:
        """
        Query current rate limit status.
        Returns dictionary with limit, remaining, reset, used.
        """
        pass

    @abstractmethod
    def normalize_issue(
        self,
        raw_item: Dict[str, Any],
        query_info: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        """
        Normalize a raw provider response into the canonical IssueRecord schema.
        Returns None if item should be excluded (e.g. pull requests).
        """
        pass
