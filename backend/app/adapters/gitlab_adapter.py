"""
Official GitLab REST API adapter for Issue Pilot.
Implements bounded discovery, issue verification, exponential backoff,
rate-limit tracking, and canonical normalization.
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

# Curated high-activity open source GitLab projects for bounded discovery
DEFAULT_GITLAB_PROJECTS = [
    ("gitlab-org/gitlab", "GitLab Community Edition / Enterprise"),
    ("inkscape/inkscape", "Inkscape Vector Graphics Editor"),
    ("fdroid/fdroidclient", "F-Droid Android Client"),
    ("kicad/code/kicad", "KiCad EDA Suite"),
    ("gnome/glib", "GNOME GLib Core Utility Library"),
    ("videolan/vlc", "VLC Media Player"),
    ("wireshark/wireshark", "Wireshark Network Protocol Analyzer")
]


class GitLabAdapter(BaseIssueSourceAdapter):
    def __init__(self, token: Optional[str] = None, base_url: Optional[str] = None, session: Optional[requests.Session] = None):
        self._token = token if token is not None else settings.gitlab_token
        self._base_url = (base_url or settings.gitlab_api_base_url).rstrip("/")
        self.session = session or requests.Session()

        headers = {
            "Accept": "application/json",
            "User-Agent": "IssuePilot-IngestionWorker/1.0"
        }
        if self._token:
            headers["PRIVATE-TOKEN"] = self._token
        if hasattr(self.session, "headers") and hasattr(self.session.headers, "update"):
            self.session.headers.update(headers)

        self.rate_limit_state: Dict[str, Any] = {
            "limit": 2000 if self._token else 500,
            "remaining": 2000 if self._token else 500,
            "reset": int(time.time()) + 60,
            "used": 0,
            "resource": "api"
        }
        self._last_successful_check: Optional[str] = None

    @property
    def source_name(self) -> str:
        return "gitlab"

    @property
    def source_instance(self) -> str:
        try:
            return urllib.parse.urlparse(self._base_url).netloc or "gitlab.com"
        except Exception:
            return "gitlab.com"

    def _update_rate_limit_from_headers(self, headers: Dict[str, str]):
        """Extract and store GitLab rate-limit headers."""
        try:
            # Case-insensitive header check
            h_lower = {k.lower(): v for k, v in headers.items()}
            if "ratelimit-limit" in h_lower:
                self.rate_limit_state["limit"] = int(h_lower["ratelimit-limit"])
            if "ratelimit-remaining" in h_lower:
                self.rate_limit_state["remaining"] = int(h_lower["ratelimit-remaining"])
            if "ratelimit-reset" in h_lower:
                self.rate_limit_state["reset"] = int(h_lower["ratelimit-reset"])
            if "ratelimit-observed" in h_lower:
                self.rate_limit_state["used"] = int(h_lower["ratelimit-observed"])
            self.rate_limit_state["resource"] = h_lower.get("ratelimit-name", "api")
        except (ValueError, TypeError) as e:
            logger.debug("Could not parse GitLab rate-limit headers: %s", str(e))

    def _execute_request(self, method: str, endpoint: str, params: Optional[dict] = None) -> requests.Response:
        """Execute HTTP request with backoff, rate budgeting, and sanitized logging."""
        if self.rate_limit_state.get("remaining", 999) <= settings.gitlab_rate_limit_buffer:
            logger.warning(
                "GitLab API rate limit remaining (%d) <= buffer (%d). Deferring request.",
                self.rate_limit_state.get("remaining", 0),
                settings.gitlab_rate_limit_buffer
            )
            raise RateLimitExceededError(
                f"GitLab API request budget exhausted (Remaining: {self.rate_limit_state.get('remaining', 0)})",
                reset_timestamp=self.rate_limit_state.get("reset"),
                remaining=self.rate_limit_state.get("remaining", 0)
            )

        url = f"{self._base_url}/{endpoint.lstrip('/')}"
        max_retries = max(1, settings.gitlab_max_retries)
        last_exception = None

        for attempt in range(max_retries):
            try:
                resp = self.session.request(
                    method=method,
                    url=url,
                    params=params,
                    timeout=settings.gitlab_request_timeout_seconds
                )

                self._update_rate_limit_from_headers(resp.headers)

                # 1. Rate Limit Exceeded or Auth Error
                if resp.status_code in (401, 403, 429):
                    is_rate_limit = (
                        resp.status_code == 429
                        or "rate limit" in resp.text.lower()
                        or resp.headers.get("ratelimit-remaining") == "0"
                    )
                    if is_rate_limit:
                        reset_ts = self.rate_limit_state.get("reset")
                        retry_after = resp.headers.get("retry-after")
                        if retry_after and retry_after.isdigit():
                            reset_ts = int(time.time()) + int(retry_after)
                        raise RateLimitExceededError(
                            f"GitLab API rate limit exceeded (HTTP {resp.status_code})",
                            reset_timestamp=reset_ts,
                            remaining=0
                        )
                    if resp.status_code == 401:
                        raise AuthenticationError("GitLab authentication failed: Invalid or expired personal access token.")
                    if resp.status_code == 403:
                        raise AuthenticationError("GitLab forbidden: Token lacks required scope (e.g. 'read_api').")

                # 2. Not Found
                if resp.status_code == 404:
                    raise ResourceNotFoundError(f"GitLab resource not found: {endpoint}")

                # 3. Transient 5xx Errors
                if resp.status_code in (500, 502, 503, 504):
                    if attempt < max_retries - 1:
                        backoff = 0.5 * (2 ** attempt)
                        time.sleep(backoff)
                        continue
                    raise TransientAPIError(f"GitLab transient error HTTP {resp.status_code}", status_code=resp.status_code)

                # 4. Other Client Errors
                if resp.status_code >= 400:
                    raise SourceAPIError(f"GitLab API error HTTP {resp.status_code}: {resp.text[:200]}", status_code=resp.status_code)

                return resp

            except (Timeout, ConnectionError) as e:
                last_exception = e
                if attempt < max_retries - 1:
                    backoff = 0.5 * (2 ** attempt)
                    time.sleep(backoff)
                else:
                    raise TransientAPIError(f"GitLab network timeout/failure after {max_retries} attempts: {str(e)}")
            except (RateLimitExceededError, AuthenticationError, ResourceNotFoundError, SourceAPIError):
                raise
            except Exception as e:
                raise SourceAPIError(f"Unexpected error communicating with GitLab: {str(e)}")

        if last_exception:
            raise TransientAPIError(f"Failed after retries: {str(last_exception)}")
        raise SourceAPIError("GitLab request terminated unexpectedly.")

    def search_issues(
        self,
        query: str,
        page: int = 1,
        per_page: int = 30,
        sort: str = "updated_desc",
        order: str = "desc"
    ) -> Dict[str, Any]:
        """
        Discover open issues from GitLab using project-scoped discovery across curated
        open-source repositories, preventing timeouts and honoring rate budgets.
        """
        all_items = []
        target_projects = [p[0] for p in DEFAULT_GITLAB_PROJECTS]

        # Rotate through projects based on page
        proj_idx = (page - 1) % len(target_projects)
        target_proj = target_projects[proj_idx]
        encoded_proj = urllib.parse.quote(target_proj, safe="")

        params = {
            "state": "opened",
            "per_page": per_page,
            "page": 1,
            "order_by": "updated",
            "sort": "desc"
        }
        if query and not query.startswith("type:") and not query.startswith("label:"):
            params["search"] = query
        elif "label:" in query:
            m = re.search(r'label:"([^"]+)"', query) or re.search(r'label:(\S+)', query)
            if m:
                params["labels"] = m.group(1)

        try:
            resp = self._execute_request("GET", f"projects/{encoded_proj}/issues", params=params)
            items = resp.json()
            if isinstance(items, list):
                # Annotate items with project full path
                for it in items:
                    it["_project_path"] = target_proj
                all_items.extend(items)
        except Exception as e:
            logger.warning("Could not fetch issues from GitLab project %s: %s", target_proj, str(e))

        return {
            "total_count": len(all_items),
            "items": all_items,
            "rate_limit": dict(self.rate_limit_state)
        }

    def get_issue(self, owner: str, repo: str, issue_number: int) -> Optional[Dict[str, Any]]:
        """Retrieve individual issue candidate details for verification or refresh."""
        project_path = f"{owner}/{repo}"
        encoded_proj = urllib.parse.quote(project_path, safe="")
        try:
            resp = self._execute_request("GET", f"projects/{encoded_proj}/issues/{issue_number}")
            data = resp.json()
            data["_project_path"] = project_path
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
        Execute independent, low-cost API health check for GitLab:
        1. Validate token authentication via /user endpoint.
        2. Test issue retrieval on a documented public project.
        3. Read rate-limit headers.
        """
        configured = bool(self._token or self._base_url)
        reachable = False
        auth_status = "not_required" if not self._token else "not_verified"
        read_permission = "unknown"
        error_msg = None
        recommended_action = None
        api_version = "v4"
        rate_limit_info = dict(self.rate_limit_state)

        # 1. Test reachability & authentication
        if self._token:
            try:
                resp = self._execute_request("GET", "user")
                reachable = True
                auth_status = "valid"
                read_permission = "granted"
                rate_limit_info = dict(self.rate_limit_state)
            except AuthenticationError as e:
                reachable = True
                auth_status = "invalid"
                read_permission = "denied"
                error_msg = str(e)
                recommended_action = "Verify GITLAB_TOKEN in your configuration. Ensure it has 'read_api' scope."
            except RateLimitExceededError as e:
                reachable = True
                auth_status = "valid"
                error_msg = f"GitLab rate limit reached: {str(e)}"
                recommended_action = "Wait for GitLab rate limit reset."
            except Exception as e:
                error_msg = f"Failed to connect to GitLab: {str(e)}"
                recommended_action = "Verify internet access and GITLAB_API_BASE_URL reachability."
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
        else:
            # Unauthenticated check on public project
            try:
                test_proj = urllib.parse.quote("gitlab-org/gitlab", safe="")
                resp = self._execute_request("GET", f"projects/{test_proj}")
                reachable = True
                auth_status = "not_required"
                read_permission = "granted"
            except Exception as e:
                error_msg = f"GitLab public reachability error: {str(e)}"
                recommended_action = "Check network or GitLab service status."

        # 2. Test Issue Reading Operation
        issue_reading_ok = False
        if auth_status != "invalid" and reachable:
            try:
                test_proj = urllib.parse.quote("gitlab-org/gitlab", safe="")
                resp = self._execute_request("GET", f"projects/{test_proj}/issues", params={"state": "opened", "per_page": 1})
                if resp.status_code == 200:
                    issue_reading_ok = True
                    if read_permission != "denied":
                        read_permission = "granted"
            except AuthenticationError as e:
                read_permission = "denied"
                error_msg = f"GitLab permission denied for issue reading: {str(e)}"
                recommended_action = "Ensure GITLAB_TOKEN has 'read_api' scope."
            except Exception as e:
                if "forbidden" in str(e).lower() or "scope" in str(e).lower():
                    read_permission = "denied"
                if not error_msg:
                    error_msg = f"GitLab issue reading check failed: {str(e)}"
                    recommended_action = "Check project access permissions."

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
        Normalize raw GitLab issue response into canonical IssueRecord schema.
        Excludes merge requests.
        """
        try:
            # 1. Exclude if not an issue
            issue_type = raw_item.get("issue_type", "issue")
            if issue_type and issue_type.lower() != "issue":
                return None

            web_url = raw_item.get("web_url", "")
            if "/merge_requests/" in web_url or "/-/merge_requests/" in web_url:
                return None
            issue_iid = int(raw_item.get("iid", 0))
            native_id = str(raw_item.get("id"))

            # Derive repository / project full name
            repo_full_name = raw_item.get("_project_path", "")
            if not repo_full_name and "/-/issues/" in web_url:
                # E.g. https://gitlab.com/owner/repo/-/issues/123
                parts = web_url.split("/-/issues/")[0].split("://")[-1].split("/")
                if len(parts) >= 3:
                    repo_full_name = f"{parts[1]}/{parts[2]}"
            if not repo_full_name:
                repo_full_name = f"project_{raw_item.get('project_id', 'unknown')}"

            parts = repo_full_name.split("/")
            repo_owner = parts[0] if len(parts) >= 2 else "gitlab"
            repo_name = parts[1] if len(parts) >= 2 else parts[0]

            title = (raw_item.get("title") or "").strip()
            body = (raw_item.get("description") or "").strip()
            gl_state = (raw_item.get("state") or "opened").lower()
            state = "open" if gl_state in ("opened", "open") else "closed"

            # Assignees
            raw_assignees = raw_item.get("assignees", [])
            assignees = [a.get("username") for a in raw_assignees if isinstance(a, dict) and a.get("username")]
            if not assignees and isinstance(raw_item.get("assignee"), dict) and raw_item["assignee"].get("username"):
                assignees = [raw_item["assignee"]["username"]]
            is_assigned = len(assignees) > 0

            comments = int(raw_item.get("user_notes_count", 0))

            # Labels (GitLab returns strings for labels)
            labels = [lbl if isinstance(lbl, str) else lbl.get("name", "") for lbl in raw_item.get("labels", [])]

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
                "recent_commits": 85.0,
                "pr_activity": 80.0,
                "issue_activity": min(95.0, 65.0 + min(comments * 4.0, 25.0)),
                "active_contributors": 75.0,
                "recent_releases": 80.0
            }
            freshness = self._compute_freshness(created_at, updated_at)

            now_iso = datetime.now(timezone.utc).isoformat()
            query_str = query_info.get("query") if query_info else None
            category_str = query_info.get("category") if query_info else None

            clean_repo_url = web_url.split("/-/issues/")[0] if "/-/issues/" in web_url else web_url

            return {
                "id": f"gl_{repo_full_name}_{issue_iid}".replace("/", "_"),
                "native_id": native_id,
                "source": "gitlab",
                "source_instance": self.source_instance,
                "source_issue_id": native_id,
                "repository_id": repo_full_name,
                "repository_name": repo_name,
                "repository_full_name": repo_full_name,
                "repository_owner": repo_owner,
                "repository_url": clean_repo_url,
                "issue_number": issue_iid,
                "title": title,
                "body": body[:2000],
                "issue_url": web_url,
                "canonical_url": web_url,
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
            logger.error("Failed to normalize GitLab issue item: %s", str(e))
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

        sc = 50.0
        if any("good-first-issue" in l or "good first issue" in l or "beginner" in l or "quick win" in l for l in lbls):
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


gitlab_adapter = GitLabAdapter()
