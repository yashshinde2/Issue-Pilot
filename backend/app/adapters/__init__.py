"""Issue Pilot source adapters package."""
from app.adapters.base import (
    BaseIssueSourceAdapter,
    SourceAPIError,
    RateLimitExceededError,
    TransientAPIError,
    AuthenticationError,
    ResourceNotFoundError
)
from app.adapters.github_adapter import GitHubAdapter, github_adapter
from app.adapters.gitlab_adapter import GitLabAdapter, gitlab_adapter
from app.adapters.codeberg_adapter import CodebergAdapter, codeberg_adapter
from app.adapters.bugzilla_adapter import BugzillaAdapter, bugzilla_adapter

ADAPTERS = {
    "github": github_adapter,
    "gitlab": gitlab_adapter,
    "codeberg": codeberg_adapter,
    "bugzilla": bugzilla_adapter
}

def get_adapter(source_name: str) -> BaseIssueSourceAdapter:
    """Retrieve adapter singleton by source name ('github', 'gitlab', 'codeberg', 'bugzilla')."""
    src = (source_name or "github").lower().strip()
    if src in ADAPTERS:
        return ADAPTERS[src]
    raise ValueError(f"Unknown issue source platform: '{source_name}'. Supported: {list(ADAPTERS.keys())}")

__all__ = [
    "BaseIssueSourceAdapter",
    "SourceAPIError",
    "RateLimitExceededError",
    "TransientAPIError",
    "AuthenticationError",
    "ResourceNotFoundError",
    "GitHubAdapter",
    "github_adapter",
    "GitLabAdapter",
    "gitlab_adapter",
    "CodebergAdapter",
    "codeberg_adapter",
    "BugzillaAdapter",
    "bugzilla_adapter",
    "ADAPTERS",
    "get_adapter"
]
