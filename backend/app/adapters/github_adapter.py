"""
Official GitHub REST API adapter for Issue Pilot.
Implements bounded discovery, individual issue verification, exponential backoff,
rate-limit tracking, and canonical normalization.
"""
import re
import time
import logging
from datetime import datetime, timezone
from typing import Dict, Any, Optional, List
import requests
from requests.exceptions import RequestException, Timeout, ConnectionError

from app.config import settings
from app.adapters.base import (
    BaseIssueSourceAdapter,
    SourceAPIError,
    RateLimitExceededError,
    TransientAPIError,
    AuthenticationError,
    ResourceNotFoundError
)

logger = logging.getLogger(__name__)

# Technology keyword mappings for evidence extraction
TECH_KEYWORDS = {
    "python": ["python", "py", "pytest", "django", "flask", "fastapi", "pandas", "numpy", "pip"],
    "fastapi": ["fastapi", "starlette", "pydantic", "uvicorn", "asgi"],
    "mongodb": ["mongodb", "mongo", "pymongo", "nosql", "documentdb", "bson"],
    "javascript": ["javascript", "js", "ecmascript", "node", "npm", "express"],
    "typescript": ["typescript", "ts", "tsconfig", "tsx"],
    "react": ["react", "jsx", "tsx", "redux", "nextjs", "vite", "frontend"],
    "cpp": ["c++", "cpp", "cmake", "gcc", "clang", "boost", "stl"],
    "java": ["java", "spring", "maven", "gradle", "jvm", "hibernate"],
    "go": ["golang", "go", "goroutine", "gin"],
    "rust": ["rust", "cargo", "tokio", "serde", "crates"],
    "docker": ["docker", "container", "dockerfile", "compose"],
    "devops": ["kubernetes", "k8s", "terraform", "ci/cd", "github actions", "aws", "gcp"]
}


class GitHubAdapter(BaseIssueSourceAdapter):
    def __init__(self, token: Optional[str] = None, base_url: Optional[str] = None, session: Optional[requests.Session] = None):
        self._token = token if token is not None else settings.github_token
        self._base_url = (base_url or settings.github_api_base_url).rstrip("/")
        self.session = session or requests.Session()

        headers = {
            "Accept": "application/vnd.github.v3+json",
            "User-Agent": "IssuePilot-IngestionWorker/1.0"
        }
        if self._token:
            headers["Authorization"] = f"token {self._token}"
        if hasattr(self.session, "headers") and hasattr(self.session.headers, "update"):
            self.session.headers.update(headers)

        self.rate_limit_state: Dict[str, Any] = {
            "limit": 60 if not self._token else 5000,
            "remaining": 60 if not self._token else 5000,
            "reset": int(time.time()) + 3600,
            "used": 0,
            "resource": "core"
        }

    @property
    def source_name(self) -> str:
        return "github"

    def _update_rate_limit_from_headers(self, headers: Dict[str, str], resource: str = "core"):
        """Extract and store GitHub rate-limit headers."""
        try:
            if "x-ratelimit-limit" in headers:
                self.rate_limit_state["limit"] = int(headers["x-ratelimit-limit"])
            if "x-ratelimit-remaining" in headers:
                self.rate_limit_state["remaining"] = int(headers["x-ratelimit-remaining"])
            if "x-ratelimit-reset" in headers:
                self.rate_limit_state["reset"] = int(headers["x-ratelimit-reset"])
            if "x-ratelimit-used" in headers:
                self.rate_limit_state["used"] = int(headers["x-ratelimit-used"])
            if "x-ratelimit-resource" in headers:
                self.rate_limit_state["resource"] = headers["x-ratelimit-resource"]
            else:
                self.rate_limit_state["resource"] = resource
        except (ValueError, TypeError) as e:
            logger.debug("Could not parse rate-limit headers: %s", str(e))

    def _execute_request(self, method: str, endpoint: str, params: Optional[dict] = None) -> requests.Response:
        """
        Execute HTTP request with:
        - Rate limit pre-check against buffer.
        - Timeout configuration.
        - Exponential backoff retry on transient errors (429, 500, 502, 503, 504, timeout).
        - Sanitized error reporting (no tokens logged).
        """
        # Pre-check rate limit remaining budget
        if self.rate_limit_state.get("remaining", 999) <= settings.github_rate_limit_buffer:
            logger.warning(
                "GitHub API rate limit remaining (%d) <= buffer (%d). Deferring request.",
                self.rate_limit_state.get("remaining", 0),
                settings.github_rate_limit_buffer
            )
            raise RateLimitExceededError(
                f"GitHub API request budget exhausted (Remaining: {self.rate_limit_state.get('remaining', 0)})",
                reset_timestamp=self.rate_limit_state.get("reset"),
                remaining=self.rate_limit_state.get("remaining", 0)
            )

        url = f"{self._base_url}/{endpoint.lstrip('/')}"
        max_retries = max(1, settings.github_max_retries)
        last_exception = None

        for attempt in range(max_retries):
            try:
                resp = self.session.request(
                    method=method,
                    url=url,
                    params=params,
                    timeout=settings.github_request_timeout_seconds
                )

                resource = "search" if "search/" in endpoint else "core"
                self._update_rate_limit_from_headers(resp.headers, resource=resource)

                # 1. Rate Limit Exceeded
                if resp.status_code in (403, 429):
                    is_rate_limit = (
                        resp.status_code == 429
                        or "rate limit" in resp.text.lower()
                        or resp.headers.get("x-ratelimit-remaining") == "0"
                    )
                    if is_rate_limit:
                        reset_ts = self.rate_limit_state.get("reset")
                        retry_after = resp.headers.get("retry-after")
                        if retry_after and retry_after.isdigit():
                            reset_ts = int(time.time()) + int(retry_after)
                        raise RateLimitExceededError(
                            f"GitHub API rate limit exceeded (HTTP {resp.status_code})",
                            reset_timestamp=reset_ts,
                            remaining=0
                        )
                    if resp.status_code == 401 or resp.status_code == 403:
                        raise AuthenticationError(f"GitHub authentication error HTTP {resp.status_code}: {resp.text[:200]}")

                # 2. Not Found
                if resp.status_code == 404:
                    raise ResourceNotFoundError(f"GitHub resource not found: {endpoint}")

                # 3. Transient 5xx Errors
                if resp.status_code in (500, 502, 503, 504):
                    if attempt < max_retries - 1:
                        backoff = 0.5 * (2 ** attempt)
                        logger.warning("Transient GitHub error HTTP %d. Retrying in %.2fs (attempt %d/%d)...",
                                       resp.status_code, backoff, attempt + 1, max_retries)
                        time.sleep(backoff)
                        continue
                    raise TransientAPIError(f"GitHub transient error HTTP {resp.status_code}: {resp.text[:200]}", status_code=resp.status_code)

                # 4. Other Client Errors
                if resp.status_code >= 400:
                    raise SourceAPIError(f"GitHub API error HTTP {resp.status_code}: {resp.text[:200]}", status_code=resp.status_code)

                return resp

            except (Timeout, ConnectionError) as e:
                last_exception = e
                if attempt < max_retries - 1:
                    backoff = 0.5 * (2 ** attempt)
                    logger.warning("Network timeout/connection error connecting to GitHub. Retrying in %.2fs...", backoff)
                    time.sleep(backoff)
                else:
                    raise TransientAPIError(f"GitHub network timeout/connection failure after {max_retries} attempts: {str(e)}")
            except (RateLimitExceededError, AuthenticationError, ResourceNotFoundError, SourceAPIError):
                raise
            except Exception as e:
                raise SourceAPIError(f"Unexpected error communicating with GitHub: {str(e)}")

        if last_exception:
            raise TransientAPIError(f"Failed after retries: {str(last_exception)}")
        raise SourceAPIError("GitHub request terminated unexpectedly.")

    def search_issues(
        self,
        query: str,
        page: int = 1,
        per_page: int = 30,
        sort: str = "updated",
        order: str = "desc"
    ) -> Dict[str, Any]:
        """Query GitHub Search API."""
        params = {
            "q": query,
            "sort": sort,
            "order": order,
            "per_page": per_page,
            "page": page
        }
        resp = self._execute_request("GET", "search/issues", params=params)
        data = resp.json()
        return {
            "total_count": data.get("total_count", 0),
            "incomplete_results": data.get("incomplete_results", False),
            "items": data.get("items", []),
            "rate_limit": dict(self.rate_limit_state)
        }

    def get_issue(self, owner: str, repo: str, issue_number: int) -> Optional[Dict[str, Any]]:
        """Retrieve individual issue candidate details."""
        try:
            resp = self._execute_request("GET", f"repos/{owner}/{repo}/issues/{issue_number}")
            return resp.json()
        except ResourceNotFoundError:
            return None

    def get_rate_limit(self) -> Dict[str, Any]:
        """Fetch live rate limit status from GitHub /rate_limit endpoint."""
        try:
            resp = self._execute_request("GET", "rate_limit")
            data = resp.json()
            core = data.get("resources", {}).get("core", {})
            search = data.get("resources", {}).get("search", {})
            return {
                "core": {
                    "limit": core.get("limit", 60),
                    "remaining": core.get("remaining", 60),
                    "reset": core.get("reset", 0),
                    "used": core.get("used", 0)
                },
                "search": {
                    "limit": search.get("limit", 10),
                    "remaining": search.get("remaining", 10),
                    "reset": search.get("reset", 0),
                    "used": search.get("used", 0)
                },
                "current_state": dict(self.rate_limit_state)
            }
        except Exception as e:
            logger.warning("Could not refresh GitHub rate limit: %s", str(e))
            return {"current_state": dict(self.rate_limit_state), "error": str(e)}

    def normalize_issue(
        self,
        raw_item: Dict[str, Any],
        query_info: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        """
        Normalize raw GitHub issue response into canonical IssueRecord schema.
        Excludes pull requests.
        Calculates 6-factor evidence inputs with fallbacks.
        """
        try:
            # 1. Pull Request Exclusion
            if "pull_request" in raw_item and raw_item["pull_request"] is not None:
                return None

            # 2. Extract Identity & Repository
            html_url = raw_item.get("html_url", "")
            repo_url = raw_item.get("repository_url", "")
            
            repo_owner = "unknown"
            repo_name = "unknown"

            if repo_url:
                parts = repo_url.rstrip("/").split("/")
                if len(parts) >= 2:
                    repo_owner = parts[-2]
                    repo_name = parts[-1]
            elif "/issues/" in html_url:
                # Derive from html_url: https://github.com/owner/name/issues/123
                parts = html_url.split("/issues/")[0].rstrip("/").split("/")
                if len(parts) >= 2:
                    repo_owner = parts[-2]
                    repo_name = parts[-1]

            repo_full_name = f"{repo_owner}/{repo_name}"
            issue_number = int(raw_item.get("number", 0))

            title = (raw_item.get("title") or "").strip()
            body = (raw_item.get("body") or "").strip()
            state = (raw_item.get("state") or "open").lower()

            assignees = [a.get("login") for a in raw_item.get("assignees", []) if isinstance(a, dict) and a.get("login")]
            if not assignees and isinstance(raw_item.get("assignee"), dict) and raw_item["assignee"].get("login"):
                assignees = [raw_item["assignee"]["login"]]
            is_assigned = len(assignees) > 0

            comments = int(raw_item.get("comments", 0))
            labels = [lbl.get("name", "") for lbl in raw_item.get("labels", []) if isinstance(lbl, dict) and lbl.get("name")]

            created_at = raw_item.get("created_at")
            updated_at = raw_item.get("updated_at")
            closed_at = raw_item.get("closed_at")

            # 3. Technologies & Relevance Evidence
            technologies = self._detect_technologies(title, body, labels, repo_name)
            detected_techs = [t["technology"] for t in technologies]

            # 4. Difficulty Evidence Estimation
            difficulty = self._estimate_difficulty(title, body, labels, comments)

            # 5. Responsiveness Evidence
            responsiveness = {
                "available": True,
                "response_time_score": min(95.0, 60.0 + min(comments * 5.0, 30.0)),
                "response_rate_score": 85.0 if comments > 0 else 70.0,
                "pr_review_score": 80.0,
                "recent_maintainer_act": 85.0
            }

            # 6. Repository Health Evidence
            health = {
                "available": True,
                "recent_commits": 85.0,
                "pr_activity": 80.0,
                "issue_activity": min(95.0, 65.0 + min(comments * 4.0, 25.0)),
                "active_contributors": 75.0,
                "recent_releases": 80.0
            }

            # 7. Freshness Evidence
            freshness = self._compute_freshness(created_at, updated_at)

            now_iso = datetime.now(timezone.utc).isoformat()
            query_str = query_info.get("query") if query_info else None
            category_str = query_info.get("category") if query_info else None

            clean_repo_url = repo_url
            if not clean_repo_url and html_url:
                clean_repo_url = html_url.split("/issues/")[0]

            return {
                "id": f"gh_{repo_full_name}_{issue_number}".replace("/", "_"),
                "native_id": str(raw_item.get("id")),
                "source": "github",
                "repository_full_name": repo_full_name,
                "repository_owner": repo_owner,
                "repository_name": repo_name,
                "repository_url": clean_repo_url,
                "issue_number": issue_number,
                "title": title,
                "body": body[:2000], # store truncated body to avoid oversized documents
                "issue_url": html_url,
                "labels": labels,
                "state": state,
                "is_assigned": is_assigned,
                "assignees": assignees,
                "comment_count": comments,
                "created_at": created_at,
                "updated_at": updated_at,
                "closed_at": closed_at,
                "last_activity_at": updated_at or created_at,
                "repository_language": raw_item.get("repository", {}).get("language") if isinstance(raw_item.get("repository"), dict) else None,
                "discovery_query": query_str,
                "discovery_category": category_str,
                "technologies": technologies,
                "detected_technologies": detected_techs,
                "difficulty_evidence": difficulty,
                "responsiveness_evidence": responsiveness,
                "health_evidence": health,
                "freshness_evidence": freshness,
                "first_seen_at": now_iso,
                "last_seen_at": now_iso,
                "stale_status": "closed" if state == "closed" else "active",
                "refresh_status": "closed" if state == "closed" else "active"
            }
        except Exception as e:
            logger.error("Failed to normalize GitHub issue item: %s", str(e))
            return None

    def _detect_technologies(self, title: str, body: str, labels: List[str], repo: str) -> List[Dict[str, Any]]:
        text = f"{title} {body} {' '.join(labels)} {repo}".lower()
        results = []
        for tech, keywords in TECH_KEYWORDS.items():
            raw_score = 0
            for kw in keywords:
                if any(kw == l.lower() for l in labels):
                    raw_score += 4
                if re.search(r'\b' + re.escape(kw) + r'\b', title.lower()):
                    raw_score += 3
                elif re.search(r'\b' + re.escape(kw) + r'\b', text):
                    raw_score += 1
            if raw_score > 0:
                results.append({
                    "technology": tech.capitalize() if tech != "cpp" else "C++",
                    "raw_relevance": min(5.0, float(raw_score))
                })

        if not results:
            results.append({"technology": "Python", "raw_relevance": 2.0})
        return results

    def _estimate_difficulty(self, title: str, body: str, labels: List[str], comments: int) -> Dict[str, float]:
        text = f"{title} {body}".lower()
        lbls = [l.lower() for l in labels]

        # Scope Change Size
        sc = 50.0
        if any("good-first-issue" in l or "good first issue" in l or "beginner" in l for l in lbls):
            sc = 35.0
        elif any("refactor" in l or "migration" in l or "architecture" in l for l in lbls) or "refactor" in text:
            sc = 75.0

        # Technical Complexity
        tc = 50.0
        if "typo" in text or "doc" in text or "documentation" in text:
            tc = 25.0
        elif "concurrency" in text or "deadlock" in text or "security" in text or "async" in text:
            tc = 80.0

        # Historical Effort Proxy
        he = min(90.0, 40.0 + min(comments * 5.0, 45.0))

        # Dependency Impact
        di = 45.0
        if "api" in text or "database" in text or "breaking" in text:
            di = 75.0

        # Coordination Complexity
        cc = min(85.0, 35.0 + min(comments * 4.0, 40.0))

        return {
            "scope_change_size": sc,
            "technical_complexity": tc,
            "historical_effort": he,
            "dependency_arch_impact": di,
            "coordination_complexity": cc
        }

    def _compute_freshness(self, created_at: Optional[str], updated_at: Optional[str]) -> Dict[str, Any]:
        now = datetime.now(timezone.utc)
        age_days = 15
        act_days = 7

        if created_at:
            try:
                c_dt = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
                age_days = max(0, (now - c_dt).days)
            except Exception:
                pass

        if updated_at:
            try:
                u_dt = datetime.fromisoformat(updated_at.replace("Z", "+00:00"))
                act_days = max(0, (now - u_dt).days)
            except Exception:
                pass

        # Age score bucketed according to doc Section 13.2
        if age_days <= 1: age_score = 100.0
        elif age_days <= 3: age_score = 90.0
        elif age_days <= 7: age_score = 80.0
        elif age_days <= 14: age_score = 65.0
        elif age_days <= 30: age_score = 50.0
        elif age_days <= 90: age_score = 30.0
        else: age_score = 15.0

        # Activity score bucketed according to doc Section 13.3
        if act_days <= 1: act_score = 100.0
        elif act_days <= 3: act_score = 90.0
        elif act_days <= 7: act_score = 80.0
        elif act_days <= 14: act_score = 60.0
        elif act_days <= 30: act_score = 40.0
        else: act_score = 20.0

        return {
            "available": True,
            "age_score": age_score,
            "activity_score": act_score
        }


# Default singleton instance
github_adapter = GitHubAdapter()
