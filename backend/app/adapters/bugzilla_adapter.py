"""
Official Bugzilla REST API adapter for Issue Pilot.
Implements bounded discovery, bug verification, conservative local budgeting,
and canonical normalization preserving product/component without fabricating repositories.
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


class BugzillaAdapter(BaseIssueSourceAdapter):
    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        session: Optional[requests.Session] = None,
        local_budget: Optional[int] = None,
        local_request_budget: Optional[int] = None
    ):
        raw_url = (base_url if base_url is not None else settings.bugzilla_base_url).strip()
        self._base_url = raw_url.rstrip("/")
        self._api_key = api_key if api_key is not None else settings.bugzilla_api_key
        self.session = session or requests.Session()

        headers = {
            "Accept": "application/json",
            "User-Agent": "IssuePilot-IngestionWorker/1.0"
        }
        if self._api_key:
            headers["X-BUGZILLA-API-KEY"] = self._api_key
        if hasattr(self.session, "headers") and hasattr(self.session.headers, "update"):
            self.session.headers.update(headers)

        # Conservative local request budget tracking for unexposed quotas
        budget_val = local_budget if local_budget is not None else local_request_budget
        self.local_budget_limit = budget_val if budget_val is not None else settings.bugzilla_local_request_budget
        self.local_requests_used = 0
        self.rate_limit_state: Dict[str, Any] = {
            "limit": "unknown",
            "remaining": "unknown",
            "reset": "unknown",
            "used": 0,
            "quota_status": "unexposed",
            "local_budget_limit": self.local_budget_limit,
            "local_budget_remaining": self.local_budget_limit
        }
        self._last_successful_check: Optional[str] = None

    def _check_rate_limit(self):
        if self.local_requests_used >= self.local_budget_limit:
            raise RateLimitExceededError(
                f"Bugzilla local request budget exhausted ({self.local_requests_used}/{self.local_budget_limit})",
                remaining=0
            )

    def _record_request(self):
        self.local_requests_used += 1
        self.rate_limit_state["used"] = self.local_requests_used
        self.rate_limit_state["local_budget_remaining"] = max(0, self.local_budget_limit - self.local_requests_used)

    @property
    def source_name(self) -> str:
        return "bugzilla"

    @property
    def source_instance(self) -> str:
        try:
            if not self._base_url:
                return "bugzilla-unconfigured"
            netloc = urllib.parse.urlparse(self._base_url).netloc
            return netloc or "bugzilla-instance"
        except Exception:
            return "bugzilla-instance"

    def _resolve_endpoint(self, path: str) -> str:
        """Resolve REST endpoint whether base_url already includes /rest or not."""
        clean_path = path.lstrip("/")
        if "/rest" in self._base_url:
            return f"{self._base_url}/{clean_path}"
        return f"{self._base_url}/rest/{clean_path}"

    def _execute_request(self, method: str, endpoint: str, params: Optional[dict] = None) -> requests.Response:
        """Execute HTTP request with local budget check, timeout, backoff, and sanitized error handling."""
        if not self._base_url:
            raise SourceAPIError("Bugzilla base URL is not configured.")

        # Check local request budget
        if self.local_requests_used >= self.local_budget_limit:
            logger.warning("Bugzilla local request budget (%d) reached. Pausing requests.", self.local_budget_limit)
            raise RateLimitExceededError(
                f"Bugzilla local request budget exhausted ({self.local_requests_used}/{self.local_budget_limit})",
                remaining=0
            )

        url = self._resolve_endpoint(endpoint)
        max_retries = max(1, settings.bugzilla_max_retries)
        last_exception = None

        request_params = dict(params or {})
        # Some Bugzilla deployments expect api_key as a query parameter
        if self._api_key and "api_key" not in request_params:
            request_params["api_key"] = self._api_key

        for attempt in range(max_retries):
            try:
                self.local_requests_used += 1
                self.rate_limit_state["used"] = self.local_requests_used
                self.rate_limit_state["local_budget_remaining"] = max(0, self.local_budget_limit - self.local_requests_used)

                resp = self.session.request(
                    method=method,
                    url=url,
                    params=request_params,
                    timeout=settings.bugzilla_request_timeout_seconds
                )

                # 1. Rate Limit / Throttling
                if resp.status_code == 429:
                    retry_after = resp.headers.get("retry-after")
                    reset_ts = int(time.time()) + int(retry_after) if retry_after and retry_after.isdigit() else int(time.time()) + 60
                    raise RateLimitExceededError("Bugzilla API rate limit exceeded (HTTP 429)", reset_timestamp=reset_ts, remaining=0)

                # 2. Authentication Errors
                if resp.status_code == 401:
                    raise AuthenticationError("Bugzilla authentication failed: Invalid or missing API key.")
                if resp.status_code == 403:
                    raise AuthenticationError("Bugzilla access forbidden: Insufficient permissions for requested bug or component.")

                # 3. Not Found
                if resp.status_code == 404:
                    raise ResourceNotFoundError(f"Bugzilla resource not found: {endpoint}")

                # 4. Transient 5xx Errors
                if resp.status_code in (500, 502, 503, 504):
                    if attempt < max_retries - 1:
                        backoff = 0.5 * (2 ** attempt)
                        time.sleep(backoff)
                        continue
                    raise TransientAPIError(f"Bugzilla transient error HTTP {resp.status_code}", status_code=resp.status_code)

                # 5. Check if Bugzilla JSON body contains error
                try:
                    data = resp.json()
                    if isinstance(data, dict) and data.get("error"):
                        err_code = data.get("code", 0)
                        err_msg = data.get("message", "Bugzilla reported an error")
                        if err_code in (101, 102, 32000): # Auth required / invalid key
                            raise AuthenticationError(f"Bugzilla auth error: {err_msg}")
                        if err_code in (100, 101):
                            raise ResourceNotFoundError(f"Bugzilla bug not found: {err_msg}")
                        raise SourceAPIError(f"Bugzilla error ({err_code}): {err_msg}")
                except (ValueError, TypeError):
                    pass

                if resp.status_code >= 400:
                    raise SourceAPIError(f"Bugzilla API error HTTP {resp.status_code}: {resp.text[:200]}", status_code=resp.status_code)

                return resp

            except (Timeout, ConnectionError) as e:
                last_exception = e
                if attempt < max_retries - 1:
                    backoff = 0.5 * (2 ** attempt)
                    time.sleep(backoff)
                else:
                    raise TransientAPIError(f"Bugzilla connection error/timeout after {max_retries} attempts: {str(e)}")
            except (RateLimitExceededError, AuthenticationError, ResourceNotFoundError, SourceAPIError):
                raise
            except Exception as e:
                raise SourceAPIError(f"Unexpected error communicating with Bugzilla: {str(e)}")

        if last_exception:
            raise TransientAPIError(f"Failed after retries: {str(last_exception)}")
        raise SourceAPIError("Bugzilla request terminated unexpectedly.")

    def search_issues(
        self,
        query: str,
        page: int = 1,
        per_page: int = 30,
        sort: str = "last_change_time",
        order: str = "desc"
    ) -> Dict[str, Any]:
        """
        Discover open bugs from Bugzilla using documented bug search endpoint:
        GET /rest/bug?status=CONFIRMED&status=IN_PROGRESS&status=NEW&status=UNCONFIRMED
        """
        offset = (page - 1) * per_page
        params = {
            "status": ["CONFIRMED", "IN_PROGRESS", "NEW", "UNCONFIRMED", "ASSIGNED"],
            "limit": per_page,
            "offset": offset,
            "order": f"{sort} {order}"
        }

        clean_query = query.strip() if query else ""
        if clean_query.startswith("label:"):
            clean_query = clean_query.replace("label:", "").strip(' "')
        elif clean_query.startswith("type:"):
            clean_query = clean_query.replace("type:", "").strip(' "')

        params["quicksearch"] = clean_query if clean_query else "open"

        try:
            resp = self._execute_request("GET", "bug", params=params)
            data = resp.json()
            bugs = data.get("bugs", []) if isinstance(data, dict) else []
            return {
                "total_count": len(bugs),
                "items": bugs,
                "rate_limit": dict(self.rate_limit_state)
            }
        except Exception as e:
            logger.warning("Could not execute Bugzilla bug search: %s", str(e))
            return {
                "total_count": 0,
                "items": [],
                "rate_limit": dict(self.rate_limit_state),
                "error": str(e)
            }

    def get_issue(self, owner: str, repo: str, issue_number: int) -> Optional[Dict[str, Any]]:
        """Retrieve individual bug candidate details for verification or refresh."""
        try:
            resp = self._execute_request("GET", f"bug/{issue_number}")
            data = resp.json()
            if isinstance(data, dict) and "bugs" in data and len(data["bugs"]) > 0:
                return data["bugs"][0]
            return None
        except ResourceNotFoundError:
            return None

    def get_rate_limit(self) -> Dict[str, Any]:
        """Report rate limit state as unknown with local budget metrics (never invent quota)."""
        return {
            "source": self.source_name,
            "source_instance": self.source_instance,
            "server_quota": "unknown",
            "limit": "unknown",
            "remaining": "unknown",
            "local_request_budget": self.local_budget_limit,
            "remaining_local_budget": max(0, self.local_budget_limit - self.local_requests_used),
            "requests_made": self.local_requests_used,
            "api": dict(self.rate_limit_state),
            "current_state": dict(self.rate_limit_state)
        }

    def check_health(self) -> Dict[str, Any]:
        """
        Execute independent, low-cost API health check for Bugzilla:
        1. Discover instance version via /version endpoint.
        2. Validate identity via /whoami endpoint when API key configured.
        3. Test bug retrieval via /bug endpoint.
        4. Accurately report unexposed server quota as unknown with local budget.
        """
        configured = bool(self._base_url)
        reachable = False
        auth_status = "not_required" if not self._api_key else "not_verified"
        read_permission = "unknown"
        error_msg = None
        recommended_action = None
        api_version = "unknown"
        rate_limit_info = dict(self.rate_limit_state)

        if not self._base_url or "your-bugzilla" in self._base_url:
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
                "error_message": f"Bugzilla host is unconfigured or points to placeholder ('{self._base_url}').",
                "recommended_action": "Set BUGZILLA_BASE_URL to a valid, reachable Bugzilla instance (e.g. https://bugzilla.mozilla.org).",
                "ingestion_enabled": False
            }

        # 1. Version and Reachability Check
        try:
            resp_ver = self._execute_request("GET", "version")
            reachable = True
            v_data = resp_ver.json()
            api_version = v_data.get("version", "5.0+")
        except Exception as e:
            error_msg = f"Failed to connect to Bugzilla instance ({self.source_instance}): {str(e)}"
            recommended_action = "Check network connectivity and verify BUGZILLA_BASE_URL host is reachable."
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

        # 2. Authentication / Identity Check
        if self._api_key:
            try:
                resp_whoami = self._execute_request("GET", "whoami")
                auth_status = "valid"
                read_permission = "granted"
            except AuthenticationError as e:
                auth_status = "invalid"
                read_permission = "denied"
                error_msg = str(e)
                recommended_action = "Check BUGZILLA_API_KEY in your configuration and ensure it is valid for this instance."
            except Exception as e:
                auth_status = "not_verified"
                error_msg = f"Bugzilla whoami identity check error: {str(e)}"
                recommended_action = "Verify API key permissions on the Bugzilla instance."
        else:
            auth_status = "not_required"
            read_permission = "granted"

        # 3. Bug Retrieval Test
        bug_reading_ok = False
        if auth_status != "invalid" and reachable:
            try:
                resp_bugs = self._execute_request("GET", "bug", params={"quicksearch": "open", "limit": 1})
                if resp_bugs.status_code == 200:
                    bug_reading_ok = True
                    if read_permission != "denied":
                        read_permission = "granted"
            except Exception as e:
                if not error_msg:
                    error_msg = f"Bugzilla bug retrieval check failed: {str(e)}"
                    recommended_action = "Check bug reading permissions on Bugzilla instance."

        ingestion_enabled = (
            settings.ingestion_enabled
            and reachable
            and (auth_status in ("valid", "not_required"))
            and (read_permission == "granted")
            and bug_reading_ok
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
        Normalize raw Bugzilla bug response into canonical IssueRecord schema.
        Preserves product, component, keywords without fabricating repositories.
        Represents unavailable maintainer/repo signals honestly for C++ engine weight renormalization.
        """
        try:
            bug_id = int(raw_item.get("id", 0))
            if not bug_id:
                return None

            summary = (raw_item.get("summary") or "").strip()
            bz_status = (raw_item.get("status") or "NEW").upper()
            resolution = (raw_item.get("resolution") or "").upper()

            # State mapping
            open_statuses = {"NEW", "UNCONFIRMED", "ASSIGNED", "CONFIRMED", "IN_PROGRESS", "REOPENED"}
            state = "open" if bz_status in open_statuses and not resolution else "closed"

            product = raw_item.get("product") or "Bugzilla"
            component = raw_item.get("component") or "General"

            # Canonical Bugzilla web URL
            base_web = self._base_url.replace("/rest", "").rstrip("/")
            canonical_url = f"{base_web}/show_bug.cgi?id={bug_id}"

            # Assigned
            assigned_to = raw_item.get("assigned_to")
            is_assigned = False
            assignees = []
            if assigned_to and "nobody@" not in assigned_to.lower():
                is_assigned = True
                assignees = [assigned_to]

            # Keywords and labels
            keywords = raw_item.get("keywords") or []
            labels = list(keywords)
            if product:
                labels.append(product)
            if component:
                labels.append(component)

            comments = int(raw_item.get("comments_count") or raw_item.get("comment_count") or 0)
            created_at = raw_item.get("creation_time") or raw_item.get("created_at")
            updated_at = raw_item.get("last_change_time") or raw_item.get("updated_at")

            # Technologies
            technologies = self._detect_technologies(summary, product, component, labels)
            detected_techs = [t["technology"] for t in technologies]

            # Difficulty evidence
            difficulty = self._estimate_difficulty(summary, labels, comments)

            # Responsiveness evidence: Unavailable on Bugzilla -> triggers C++ weight renormalization
            responsiveness = {
                "available": False,
                "response_time_score": 80.0,
                "response_rate_score": 80.0,
                "pr_review_score": 80.0,
                "recent_maintainer_act": 80.0
            }

            # Health evidence: Unavailable on Bugzilla -> triggers C++ weight renormalization
            health = {
                "available": False,
                "recent_commits": 80.0,
                "pr_activity": 80.0,
                "issue_activity": 80.0,
                "active_contributors": 80.0,
                "recent_releases": 80.0
            }

            # Freshness evidence
            freshness = self._compute_freshness(created_at, updated_at)

            now_iso = datetime.now(timezone.utc).isoformat()
            query_str = query_info.get("query") if query_info else None
            category_str = query_info.get("category") if query_info else None

            # Do NOT fabricate repository metadata for Bugzilla records
            return {
                "id": f"bz_{self.source_instance}_{bug_id}".replace("/", "_").replace(":", "_").replace(".", "_"),
                "native_id": str(bug_id),
                "source": "bugzilla",
                "source_instance": self.source_instance,
                "source_issue_id": str(bug_id),
                "repository_id": None,
                "repository_name": None,
                "repository_full_name": f"{product}/{component}",  # clean identifier for reporting without fabricating a git repo
                "repository_owner": None,
                "repository_url": None,
                "issue_number": bug_id,
                "title": summary,
                "body": f"Product: {product} | Component: {component} | Resolution: {resolution or 'None'}",
                "issue_url": canonical_url,
                "canonical_url": canonical_url,
                "product": product,
                "component": component,
                "labels": labels,
                "state": state,
                "is_assigned": is_assigned,
                "assignees": assignees,
                "comment_count": comments,
                "created_at": created_at,
                "updated_at": updated_at,
                "closed_at": updated_at if state == "closed" else None,
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
            logger.error("Failed to normalize Bugzilla bug item: %s", str(e))
            return None

    def _detect_technologies(self, summary: str, product: str, component: str, labels: List[str]) -> List[Dict[str, Any]]:
        text = f"{summary} {product} {component} {' '.join(labels)}".lower()
        results = []
        for tech, keywords in TECH_KEYWORDS.items():
            raw_score = 0
            for kw in keywords:
                if any(kw == l.lower() for l in labels):
                    raw_score += 4
                if re.search(r'\b' + re.escape(kw) + r'\b', summary.lower()):
                    raw_score += 3
                elif re.search(r'\b' + re.escape(kw) + r'\b', text):
                    raw_score += 1
            if raw_score > 0:
                results.append({
                    "technology": tech.capitalize() if tech != "cpp" else "C++",
                    "raw_relevance": min(5.0, float(raw_score))
                })

        if not results:
            results.append({"technology": "C++", "raw_relevance": 2.0})
        return results

    def _estimate_difficulty(self, summary: str, labels: List[str], comments: int) -> Dict[str, float]:
        text = summary.lower()
        lbls = [l.lower() for l in labels]

        sc = 50.0
        if any("good-first-bug" in l or "good_first_bug" in l or "mentor" in l or "beginner" in l for l in lbls):
            sc = 35.0
        elif any("refactor" in l or "rewrite" in l for l in lbls) or "refactor" in text:
            sc = 75.0

        tc = 50.0
        if "typo" in text or "doc" in text:
            tc = 25.0
        elif "crash" in text or "deadlock" in text or "security" in text or "memory leak" in text:
            tc = 80.0

        he = min(90.0, 40.0 + min(comments * 5.0, 45.0))
        di = 45.0
        if "api" in text or "interface" in text or "breaking" in text:
            di = 70.0
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


bugzilla_adapter = BugzillaAdapter()
