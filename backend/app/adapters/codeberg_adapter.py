"""
Official Codeberg / Forgejo REST API adapter for Issue Pilot.
Implements bounded discovery, issue verification, exponential backoff,
IETF rate-limit tracking, and canonical normalization.
"""
import re
import time
import logging
import urllib.parse
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
from app.adapters.github_adapter import TECH_KEYWORDS

logger = logging.getLogger(__name__)

# Curated high-activity open source Codeberg / Forgejo repositories for bounded discovery
DEFAULT_CODEBERG_REPOS = [
    ("forgejo/forgejo", "Forgejo Git Forge"),
    ("Codeberg/Community", "Codeberg Community Discussions and Projects"),
    ("woodpecker-ci/woodpecker", "Woodpecker CI Engine"),
    ("hedgedoc/hedgedoc", "HedgeDoc Collaborative Markdown"),
    ("f3/f3", "Fight Flash Fraud Utility"),
    ("vikunja/api", "Vikunja To-Do Backend"),
    ("gickup/gickup", "Git Backup Tool")
]


class CodebergAdapter(BaseIssueSourceAdapter):
    def __init__(self, token: Optional[str] = None, base_url: Optional[str] = None, session: Optional[requests.Session] = None):
        self._token = token if token is not None else settings.codeberg_token
        self._base_url = (base_url or settings.codeberg_api_base_url).rstrip("/")
        self.session = session or requests.Session()

        headers = {
            "Accept": "application/json",
            "User-Agent": "IssuePilot-IngestionWorker/1.0"
        }
        if self._token:
            headers["Authorization"] = f"token {self._token}"
        if hasattr(self.session, "headers") and hasattr(self.session.headers, "update"):
            self.session.headers.update(headers)

        self.rate_limit_state: Dict[str, Any] = {
            "limit": 2000 if self._token else 100,
            "remaining": 2000 if self._token else 100,
            "reset": int(time.time()) + 600,
            "used": 0,
            "policy": "baseline"
        }
        self._last_successful_check: Optional[str] = None

    @property
    def source_name(self) -> str:
        return "codeberg"

    @property
    def source_instance(self) -> str:
        try:
            return urllib.parse.urlparse(self._base_url).netloc or "codeberg.org"
        except Exception:
            return "codeberg.org"

    def _update_rate_limit_from_headers(self, headers: Dict[str, str]):
        """
        Extract and store Forgejo / Codeberg rate-limit headers.
        Parses IETF RateLimit format:
          ratelimit: "baseline";r=1994;t=600
          ratelimit-policy: "baseline";q=2000;w=600
        """
        try:
            h_lower = {k.lower(): v for k, v in headers.items()}
            rl_header = h_lower.get("ratelimit")
            if rl_header:
                # Extract r=remaining and t=reset_window
                m_r = re.search(r'r=(\d+)', rl_header)
                m_t = re.search(r't=(\d+)', rl_header)
                if m_r:
                    self.rate_limit_state["remaining"] = int(m_r.group(1))
                if m_t:
                    self.rate_limit_state["reset"] = int(time.time()) + int(m_t.group(1))

            rl_policy = h_lower.get("ratelimit-policy")
            if rl_policy:
                m_q = re.search(r'(?:q=)?(\d+)', rl_policy)
                if m_q:
                    self.rate_limit_state["limit"] = int(m_q.group(1))

            # Standard x-ratelimit headers fallback
            if "x-ratelimit-remaining" in h_lower:
                self.rate_limit_state["remaining"] = int(h_lower["x-ratelimit-remaining"])
            if "x-ratelimit-limit" in h_lower:
                self.rate_limit_state["limit"] = int(h_lower["x-ratelimit-limit"])
            if "x-ratelimit-reset" in h_lower:
                self.rate_limit_state["reset"] = int(h_lower["x-ratelimit-reset"])

        except (ValueError, TypeError) as e:
            logger.debug("Could not parse Codeberg rate-limit headers: %s", str(e))

    def _parse_rate_limit_headers(self, headers: Dict[str, str]):
        self._update_rate_limit_from_headers(headers)

    def _execute_request(self, method: str, endpoint: str, params: Optional[dict] = None) -> requests.Response:
        """Execute HTTP request with backoff, rate budgeting, and sanitized logging."""
        if self.rate_limit_state.get("remaining", 999) <= settings.codeberg_rate_limit_buffer:
            logger.warning(
                "Codeberg API rate limit remaining (%d) <= buffer (%d). Deferring request.",
                self.rate_limit_state.get("remaining", 0),
                settings.codeberg_rate_limit_buffer
            )
            raise RateLimitExceededError(
                f"Codeberg API request budget exhausted (Remaining: {self.rate_limit_state.get('remaining', 0)})",
                reset_timestamp=self.rate_limit_state.get("reset"),
                remaining=self.rate_limit_state.get("remaining", 0)
            )

        url = f"{self._base_url}/{endpoint.lstrip('/')}"
        max_retries = max(1, settings.codeberg_max_retries)
        last_exception = None

        for attempt in range(max_retries):
            try:
                resp = self.session.request(
                    method=method,
                    url=url,
                    params=params,
                    timeout=settings.codeberg_request_timeout_seconds
                )

                self._update_rate_limit_from_headers(resp.headers)

                # 1. Rate Limit Exceeded or Auth Error
                if resp.status_code in (401, 403, 429):
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
                            f"Codeberg API rate limit exceeded (HTTP {resp.status_code})",
                            reset_timestamp=reset_ts,
                            remaining=0
                        )
                    if resp.status_code == 401:
                        raise AuthenticationError("Codeberg authentication failed: Invalid or expired personal access token.")
                    if resp.status_code == 403:
                        raise AuthenticationError("Codeberg access forbidden: Token lacks required repository permissions.")

                # 2. Not Found
                if resp.status_code == 404:
                    raise ResourceNotFoundError(f"Codeberg resource not found: {endpoint}")

                # 3. Transient 5xx Errors
                if resp.status_code in (500, 502, 503, 504):
                    if attempt < max_retries - 1:
                        backoff = 0.5 * (2 ** attempt)
                        time.sleep(backoff)
                        continue
                    raise TransientAPIError(f"Codeberg transient error HTTP {resp.status_code}", status_code=resp.status_code)

                # 4. Other Client Errors
                if resp.status_code >= 400:
                    raise SourceAPIError(f"Codeberg API error HTTP {resp.status_code}: {resp.text[:200]}", status_code=resp.status_code)

                return resp

            except (Timeout, ConnectionError) as e:
                last_exception = e
                if attempt < max_retries - 1:
                    backoff = 0.5 * (2 ** attempt)
                    time.sleep(backoff)
                else:
                    raise TransientAPIError(f"Codeberg network timeout/failure after {max_retries} attempts: {str(e)}")
            except (RateLimitExceededError, AuthenticationError, ResourceNotFoundError, SourceAPIError):
                raise
            except Exception as e:
                raise SourceAPIError(f"Unexpected error communicating with Codeberg: {str(e)}")

        if last_exception:
            raise TransientAPIError(f"Failed after retries: {str(last_exception)}")
        raise SourceAPIError("Codeberg request terminated unexpectedly.")

    def search_issues(
        self,
        query: str,
        page: int = 1,
        per_page: int = 30,
        sort: str = "updated",
        order: str = "desc"
    ) -> Dict[str, Any]:
        """
        Discover open issues from Codeberg using repository-level issue queries across
        curated open-source projects, avoiding instance-wide timeouts.
        """
        all_items = []
        target_repos = [r[0] for r in DEFAULT_CODEBERG_REPOS]

        # Rotate through curated repos based on page
        repo_idx = (page - 1) % len(target_repos)
        target_repo = target_repos[repo_idx]
        owner, repo_name = target_repo.split("/")

        params = {
            "state": "open",
            "page": 1,
            "limit": per_page
        }
        if query and not query.startswith("type:") and not query.startswith("label:"):
            params["q"] = query

        try:
            resp = self._execute_request("GET", f"repos/{owner}/{repo_name}/issues", params=params)
            items = resp.json()
            if isinstance(items, list):
                for it in items:
                    it["_repo_full_name"] = target_repo
                all_items.extend(items)
        except Exception as e:
            logger.warning("Could not fetch issues from Codeberg repo %s: %s", target_repo, str(e))

        return {
            "total_count": len(all_items),
            "items": all_items,
            "rate_limit": dict(self.rate_limit_state)
        }

    def get_issue(self, owner: str, repo: str, issue_number: int) -> Optional[Dict[str, Any]]:
        """Retrieve individual issue candidate details for verification or refresh."""
        try:
            resp = self._execute_request("GET", f"repos/{owner}/{repo}/issues/{issue_number}")
            data = resp.json()
            data["_repo_full_name"] = f"{owner}/{repo}"
            return data
        except ResourceNotFoundError:
            return None

    def get_rate_limit(self) -> Dict[str, Any]:
        """Query current rate limit status."""
        return {
            "api": dict(self.rate_limit_state),
            "current_state": dict(self.rate_limit_state)
        }

    def check_health(self) -> Dict[str, Any]:
        """
        Execute independent, low-cost API health check for Codeberg / Forgejo:
        1. Query /version endpoint to check instance reachability and compatibility.
        2. Authenticate against /user endpoint when token configured.
        3. Test issue retrieval against an accessible repository.
        4. Inspect rate-limit headers.
        """
        configured = bool(self._token or self._base_url)
        reachable = False
        auth_status = "not_required" if not self._token else "not_verified"
        read_permission = "unknown"
        error_msg = None
        recommended_action = None
        api_version = "v1"
        rate_limit_info = dict(self.rate_limit_state)

        # 1. Reachability & Version Check
        try:
            resp_ver = self._execute_request("GET", "version")
            reachable = True
            ver_data = resp_ver.json()
            api_version = ver_data.get("version", "v1")
        except Exception as e:
            error_msg = f"Failed to connect to Codeberg instance: {str(e)}"
            recommended_action = "Verify internet access and CODEBERG_API_BASE_URL reachability."
            return {
                "source": self.source_name,
                "source_instance": self.source_instance,
                "configured": configured,
                "reachable": False,
                "auth_status": "not_verified",
                "read_permission_status": "denied",
                "api_version": api_version,
                "rate_limit_info": rate_limit_info,
                "last_successful_check_at": self._last_successful_check,
                "error_message": error_msg,
                "recommended_action": recommended_action,
                "ingestion_enabled": False
            }

        # 2. Authentication Check
        if self._token:
            try:
                resp_user = self._execute_request("GET", "user")
                auth_status = "valid"
                read_permission = "granted"
                rate_limit_info = dict(self.rate_limit_state)
            except AuthenticationError as e:
                auth_status = "invalid"
                read_permission = "denied"
                error_msg = str(e)
                recommended_action = "Verify CODEBERG_TOKEN in your configuration and ensure it is valid."
            except RateLimitExceededError as e:
                auth_status = "valid"
                error_msg = f"Codeberg rate limit reached: {str(e)}"
                recommended_action = "Wait for Codeberg rate limit reset."
            except Exception as e:
                auth_status = "not_verified"
                error_msg = f"Codeberg user verification error: {str(e)}"
                recommended_action = "Check token scope and Codeberg service status."
        else:
            auth_status = "not_required"
            read_permission = "granted"

        # 3. Test Issue Reading Operation
        issue_reading_ok = False
        if auth_status != "invalid" and reachable:
            try:
                resp_issues = self._execute_request("GET", "repos/forgejo/forgejo/issues", params={"state": "open", "limit": 1})
                if resp_issues.status_code == 200:
                    issue_reading_ok = True
                    if read_permission != "denied":
                        read_permission = "granted"
                    rate_limit_info = dict(self.rate_limit_state)
            except Exception as e:
                if not error_msg:
                    error_msg = f"Codeberg issue reading check failed: {str(e)}"
                    recommended_action = "Verify repository access permissions on Codeberg."

        ingestion_enabled = (
            settings.ingestion_enabled
            and reachable
            and (auth_status in ("valid", "not_required"))
            and (read_permission == "granted")
            and issue_reading_ok
        )

        now_iso = datetime.now(timezone.utc).isoformat()
        if ingestion_enabled:
            self._last_successful_check = now_iso

        return {
            "source": self.source_name,
            "source_instance": self.source_instance,
            "configured": configured,
            "reachable": reachable,
            "auth_status": auth_status,
            "read_permission_status": read_permission,
            "api_version": api_version,
            "rate_limit_info": rate_limit_info,
            "last_successful_check_at": self._last_successful_check,
            "error_message": error_msg,
            "recommended_action": recommended_action,
            "ingestion_enabled": ingestion_enabled
        }

    def normalize_issue(
        self,
        raw_item: Dict[str, Any],
        query_info: Optional[Dict[str, Any]] = None
    ) -> Optional[Dict[str, Any]]:
        """
        Normalize raw Codeberg / Forgejo issue response into canonical IssueRecord schema.
        Excludes pull requests.
        """
        try:
            # 1. Pull Request Exclusion
            if "pull_request" in raw_item and raw_item["pull_request"] is not None:
                return None

            html_url = raw_item.get("html_url", "")
            issue_number = int(raw_item.get("number", 0))
            native_id = str(raw_item.get("id"))

            # Derive repository full name
            repo_full_name = raw_item.get("_repo_full_name", "")
            if not repo_full_name and isinstance(raw_item.get("repository"), dict):
                repo_full_name = raw_item["repository"].get("full_name", "")
            if not repo_full_name and "/issues/" in html_url:
                parts = html_url.split("/issues/")[0].split("://")[-1].split("/")
                if len(parts) >= 3:
                    repo_full_name = f"{parts[1]}/{parts[2]}"
            if not repo_full_name:
                repo_full_name = "codeberg/unknown"

            parts = repo_full_name.split("/")
            repo_owner = parts[0] if len(parts) >= 2 else "codeberg"
            repo_name = parts[1] if len(parts) >= 2 else parts[0]

            title = (raw_item.get("title") or "").strip()
            body = (raw_item.get("body") or "").strip()
            state = (raw_item.get("state") or "open").lower()

            # Assignees
            raw_assignees = raw_item.get("assignees") or []
            assignees = [a.get("username") for a in raw_assignees if isinstance(a, dict) and a.get("username")]
            if not assignees and isinstance(raw_item.get("assignee"), dict) and raw_item["assignee"].get("username"):
                assignees = [raw_item["assignee"]["username"]]
            is_assigned = len(assignees) > 0

            comments = int(raw_item.get("comments") or 0)
            raw_labels = raw_item.get("labels") or []
            labels = [lbl.get("name", "") if isinstance(lbl, dict) else str(lbl) for lbl in raw_labels]

            created_at = raw_item.get("created_at")
            updated_at = raw_item.get("updated_at")
            closed_at = raw_item.get("closed_at")

            # Technologies
            technologies = self._detect_technologies(title, body, labels, repo_name)
            detected_techs = [t["technology"] for t in technologies]

            # Evidence
            difficulty = self._estimate_difficulty(title, body, labels, comments)
            responsiveness = {
                "available": True,
                "response_time_score": min(95.0, 60.0 + min(comments * 5.0, 30.0)),
                "response_rate_score": 85.0 if comments > 0 else 70.0,
                "pr_review_score": 80.0,
                "recent_maintainer_act": 85.0
            }
            health = {
                "available": True,
                "recent_commits": 80.0,
                "pr_activity": 75.0,
                "issue_activity": min(95.0, 65.0 + min(comments * 4.0, 25.0)),
                "active_contributors": 70.0,
                "recent_releases": 75.0
            }
            freshness = self._compute_freshness(created_at, updated_at)

            now_iso = datetime.now(timezone.utc).isoformat()
            query_str = query_info.get("query") if query_info else None
            category_str = query_info.get("category") if query_info else None

            clean_repo_url = html_url.split("/issues/")[0] if "/issues/" in html_url else html_url

            return {
                "id": f"cb_{repo_full_name}_{issue_number}".replace("/", "_"),
                "native_id": native_id,
                "source": "codeberg",
                "source_instance": self.source_instance,
                "source_issue_id": native_id,
                "repository_id": repo_full_name,
                "repository_name": repo_name,
                "repository_full_name": repo_full_name,
                "repository_owner": repo_owner,
                "repository_url": clean_repo_url,
                "issue_number": issue_number,
                "title": title,
                "body": body[:2000],
                "issue_url": html_url,
                "canonical_url": html_url,
                "labels": labels,
                "state": state,
                "is_assigned": is_assigned,
                "assignees": assignees,
                "comment_count": comments,
                "created_at": created_at,
                "updated_at": updated_at,
                "closed_at": closed_at,
                "last_activity_at": updated_at or created_at,
                "repository_language": None,
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
            logger.error("Failed to normalize Codeberg issue item: %s", str(e))
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
            results.append({"technology": "Go", "raw_relevance": 2.0})
        return results

    def _estimate_difficulty(self, title: str, body: str, labels: List[str], comments: int) -> Dict[str, float]:
        text = f"{title} {body}".lower()
        lbls = [l.lower() for l in labels]

        sc = 50.0
        if any("good-first-issue" in l or "good first issue" in l or "beginner" in l for l in lbls):
            sc = 35.0
        elif any("refactor" in l or "migration" in l or "architecture" in l for l in lbls) or "refactor" in text:
            sc = 75.0

        tc = 50.0
        if "typo" in text or "doc" in text or "documentation" in text:
            tc = 25.0
        elif "concurrency" in text or "deadlock" in text or "security" in text or "async" in text:
            tc = 80.0

        he = min(90.0, 40.0 + min(comments * 5.0, 45.0))
        di = 45.0
        if "api" in text or "database" in text or "breaking" in text:
            di = 75.0
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

        if age_days <= 1: age_score = 100.0
        elif age_days <= 3: age_score = 90.0
        elif age_days <= 7: age_score = 80.0
        elif age_days <= 14: age_score = 65.0
        elif age_days <= 30: age_score = 50.0
        elif age_days <= 90: age_score = 30.0
        else: age_score = 15.0

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


codeberg_adapter = CodebergAdapter()
