import re
import time
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from typing import List, Dict, Any, Tuple, Optional
import requests
from requests.exceptions import RequestException, Timeout, ConnectionError

from app.config import settings
from app.database import db

logger = logging.getLogger(__name__)


class VerificationResult:
    def __init__(
        self,
        is_valid: bool,
        status: str,
        reason: str,
        canonical_repo: Optional[str] = None,
        canonical_url: Optional[str] = None,
        is_operational_failure: bool = False,
        is_inconclusive: bool = False,
        response_metadata: Optional[Dict[str, Any]] = None
    ):
        self.is_valid = is_valid
        self.status = status  # "live_verified", "excluded", "inconclusive", "sample_verified"
        self.reason = reason
        self.canonical_repo = canonical_repo
        self.canonical_url = canonical_url
        self.is_operational_failure = is_operational_failure
        self.is_inconclusive = is_inconclusive
        self.response_metadata = response_metadata or {}

    def to_dict(self) -> Dict[str, Any]:
        return {
            "is_valid": self.is_valid,
            "status": self.status,
            "reason": self.reason,
            "canonical_repo": self.canonical_repo,
            "canonical_url": self.canonical_url,
            "is_operational_failure": self.is_operational_failure,
            "is_inconclusive": self.is_inconclusive,
            "response_metadata": self.response_metadata
        }


class LiveVerifier:
    def __init__(
        self,
        token: Optional[str] = None,
        base_url: Optional[str] = None,
        cache_ttl_seconds: Optional[int] = None,
        session: Optional[requests.Session] = None
    ):
        self._token = token if token is not None else settings.github_token
        self._base_url = (base_url or settings.github_api_base_url).rstrip("/")
        self.cache_ttl_seconds = cache_ttl_seconds or settings.verification_cache_ttl_seconds
        self.session = session or requests.Session()
        self._lock = threading.Lock()
        # In-memory verification cache: (repo, issue_number) -> dict
        self._cache: Dict[Tuple[str, int], Dict[str, Any]] = {}
        # In-memory rate-limit tracking
        self.rate_limit_state: Dict[str, Any] = {
            "limit": 5000 if self._token else 60,
            "remaining": 5000 if self._token else 60,
            "reset": int(time.time()) + 3600
        }

        headers = {
            "Accept": "application/vnd.github.v3+json",
            "User-Agent": "IssuePilot-Verifier/1.0"
        }
        if self._token:
            headers["Authorization"] = f"token {self._token}"
        if hasattr(self.session, "headers") and hasattr(self.session.headers, "update"):
            self.session.headers.update(headers)

    def clear_cache(self):
        """Clear cached verification results and reset rate limit state."""
        with self._lock:
            self._cache.clear()
            self.rate_limit_state = {
                "limit": 5000 if self._token else 60,
                "remaining": 5000 if self._token else 60,
                "reset": int(time.time()) + 3600
            }

    def _update_rate_limit_from_headers(self, headers: Dict[str, str]):
        """Thread-safe rate limit tracking from GitHub response headers."""
        try:
            with self._lock:
                if "x-ratelimit-remaining" in headers:
                    self.rate_limit_state["remaining"] = int(headers["x-ratelimit-remaining"])
                if "x-ratelimit-reset" in headers:
                    self.rate_limit_state["reset"] = int(headers["x-ratelimit-reset"])
                if "x-ratelimit-limit" in headers:
                    self.rate_limit_state["limit"] = int(headers["x-ratelimit-limit"])
        except (ValueError, TypeError):
            pass

    def _extract_canonical_repo(self, repo_full_name: str, issue_number: int, data: Dict[str, Any]) -> Tuple[str, str]:
        """
        Extract canonical repository and URL, properly handling repository renames and transfers.
        """
        html_url = data.get("html_url", "")
        repo_url = data.get("repository_url", "")
        canonical_repo = repo_full_name

        # Parse from repository_url: e.g. "https://api.github.com/repos/new-owner/new-repo"
        if repo_url and "/repos/" in repo_url:
            canonical_repo = repo_url.split("/repos/")[-1].strip("/")
        elif html_url and "github.com/" in html_url:
            # Parse from html_url: e.g. "https://github.com/new-owner/new-repo/issues/123"
            parts = html_url.split("github.com/")[-1].split("/")
            if len(parts) >= 2:
                canonical_repo = f"{parts[0]}/{parts[1]}"

        canonical_issue_url = html_url or f"https://github.com/{canonical_repo}/issues/{issue_number}"
        return canonical_repo, canonical_issue_url

    def verify_issue(
        self,
        repo_full_name: str,
        issue_number: int,
        timeout: Optional[int] = None,
        max_retries: Optional[int] = None
    ) -> VerificationResult:
        """
        Perform authoritative live verification of an individual issue candidate.
        Checks:
        - Issue still exists (HTTP 404).
        - Issue remains open.
        - Issue is not a pull request.
        - Issue is not assigned to another contributor.
        - Repository is accessible and not archived.
        - Respects rate limits, transient 5xx retries, and records inconclusive state without marking closed.
        - Uses conditional requests (ETags) and short-lived caching.
        """
        if not repo_full_name or "/" not in repo_full_name or issue_number <= 0:
            return VerificationResult(
                is_valid=False,
                status="excluded",
                reason="Invalid repository identity or issue number",
                canonical_repo=repo_full_name
            )

        timeout_val = timeout or settings.verification_timeout_seconds
        retries_val = max(1, max_retries or settings.verification_max_retries)
        cache_key = (repo_full_name.lower(), issue_number)
        now_ts = time.time()

        cached_etag = None
        # Check cache
        with self._lock:
            if cache_key in self._cache:
                entry = self._cache[cache_key]
                if now_ts - entry.get("cached_at", 0) < self.cache_ttl_seconds:
                    logger.debug("Verification cache hit for %s#%d", repo_full_name, issue_number)
                    return VerificationResult(
                        is_valid=entry["is_valid"],
                        status=entry["status"],
                        reason=f"{entry['reason']} (cached)",
                        canonical_repo=entry.get("canonical_repo", repo_full_name),
                        canonical_url=entry.get("canonical_url"),
                        is_operational_failure=entry.get("is_operational_failure", False),
                        is_inconclusive=entry.get("is_inconclusive", False),
                        response_metadata={"cached": True, "cached_at": entry.get("cached_at")}
                    )
                cached_etag = entry.get("etag")

            # Pre-check rate limit remaining against buffer
            if self.rate_limit_state.get("remaining", 999) <= settings.github_rate_limit_buffer:
                logger.warning("GitHub API rate limit remaining (%d) <= buffer (%d). Deferring verification.",
                               self.rate_limit_state.get("remaining", 0), settings.github_rate_limit_buffer)
                return VerificationResult(
                    is_valid=False,
                    status="inconclusive",
                    reason=f"GitHub API rate limit budget exhausted (Remaining: {self.rate_limit_state.get('remaining', 0)} <= Buffer: {settings.github_rate_limit_buffer}); verification deferred.",
                    canonical_repo=repo_full_name,
                    is_operational_failure=True,
                    is_inconclusive=True,
                    response_metadata={"rate_limited": True, "remaining": self.rate_limit_state.get("remaining", 0)}
                )

        url = f"{self._base_url}/repos/{repo_full_name}/issues/{issue_number}"
        req_headers = {}
        if cached_etag:
            req_headers["If-None-Match"] = cached_etag

        last_error = None
        for attempt in range(retries_val):
            try:
                resp = self.session.get(
                    url,
                    headers=req_headers if req_headers else None,
                    timeout=timeout_val,
                    allow_redirects=True
                )

                if hasattr(resp, "headers"):
                    self._update_rate_limit_from_headers(resp.headers)

                # 1. Check HTTP 304 Not Modified
                if resp.status_code == 304 and cache_key in self._cache:
                    with self._lock:
                        entry = self._cache[cache_key]
                        entry["cached_at"] = now_ts
                    return VerificationResult(
                        is_valid=entry["is_valid"],
                        status=entry["status"],
                        reason="Confirmed unmodified via ETag conditional request",
                        canonical_repo=entry.get("canonical_repo", repo_full_name),
                        canonical_url=entry.get("canonical_url"),
                        response_metadata={"status_code": 304, "etag": cached_etag}
                    )

                # 2. Check Rate Limit / Forbidden
                if resp.status_code in (403, 429):
                    is_rate_limit = (
                        resp.status_code == 429
                        or resp.headers.get("x-ratelimit-remaining") == "0"
                        or "rate limit" in resp.text.lower()
                    )
                    if is_rate_limit:
                        with self._lock:
                            self.rate_limit_state["remaining"] = 0
                        # Rate limit reached; inconclusive state, NOT proof of closure!
                        return VerificationResult(
                            is_valid=False,
                            status="inconclusive",
                            reason=f"GitHub API rate limit exhausted (HTTP {resp.status_code}); verification inconclusive",
                            canonical_repo=repo_full_name,
                            is_operational_failure=True,
                            is_inconclusive=True,
                            response_metadata={"status_code": resp.status_code, "rate_limited": True}
                        )
                    # 403 Forbidden without rate limit
                    return VerificationResult(
                        is_valid=False,
                        status="excluded",
                        reason=f"Repository access restricted or forbidden (HTTP {resp.status_code})",
                        canonical_repo=repo_full_name,
                        response_metadata={"status_code": resp.status_code}
                    )

                # 3. Check HTTP 404 (Not Found)
                if resp.status_code == 404:
                    return VerificationResult(
                        is_valid=False,
                        status="excluded",
                        reason="Issue or repository no longer exists (HTTP 404)",
                        canonical_repo=repo_full_name,
                        response_metadata={"status_code": 404}
                    )

                # 4. Check Transient HTTP 5xx Errors
                if resp.status_code in (500, 502, 503, 504):
                    if attempt < retries_val - 1:
                        backoff = 0.5 * (2 ** attempt)
                        logger.warning("Transient 5xx error (%d) verifying %s#%d. Retrying in %.2fs (attempt %d/%d)...",
                                       resp.status_code, repo_full_name, issue_number, backoff, attempt + 1, retries_val)
                        time.sleep(backoff)
                        continue
                    # Retries exhausted; do NOT treat as closed!
                    return VerificationResult(
                        is_valid=False,
                        status="inconclusive",
                        reason=f"GitHub server error HTTP {resp.status_code} after {retries_val} retries; verification inconclusive",
                        canonical_repo=repo_full_name,
                        is_operational_failure=True,
                        is_inconclusive=True,
                        response_metadata={"status_code": resp.status_code}
                    )

                # 5. Check Other HTTP Client Errors
                if resp.status_code != 200:
                    return VerificationResult(
                        is_valid=False,
                        status="inconclusive",
                        reason=f"Unexpected GitHub HTTP {resp.status_code} response",
                        canonical_repo=repo_full_name,
                        is_operational_failure=True,
                        is_inconclusive=True,
                        response_metadata={"status_code": resp.status_code}
                    )

                # 6. Parse 200 OK Response
                data = resp.json()
                etag = resp.headers.get("ETag")
                canonical_repo, canonical_url = self._extract_canonical_repo(repo_full_name, issue_number, data)
                res_metadata = {
                    "status_code": 200,
                    "etag": etag,
                    "canonical_repo": canonical_repo,
                    "canonical_url": canonical_url
                }

                # Check Pull Request
                if "pull_request" in data:
                    res = VerificationResult(
                        is_valid=False,
                        status="excluded",
                        reason="Candidate is a pull request, not an issue",
                        canonical_repo=canonical_repo,
                        canonical_url=canonical_url,
                        response_metadata=res_metadata
                    )
                    self._cache_result(cache_key, res, etag)
                    return res

                # Check State (Must be open)
                state = data.get("state", "unknown")
                if state != "open":
                    res = VerificationResult(
                        is_valid=False,
                        status="excluded",
                        reason=f"Issue is {state} on GitHub",
                        canonical_repo=canonical_repo,
                        canonical_url=canonical_url,
                        response_metadata=res_metadata
                    )
                    self._cache_result(cache_key, res, etag)
                    return res

                # Check Assignee
                assignee = data.get("assignee")
                assignees = data.get("assignees", [])
                if assignee is not None or len(assignees) > 0:
                    res = VerificationResult(
                        is_valid=False,
                        status="excluded",
                        reason="Issue is assigned to another contributor",
                        canonical_repo=canonical_repo,
                        canonical_url=canonical_url,
                        response_metadata=res_metadata
                    )
                    self._cache_result(cache_key, res, etag)
                    return res

                # Check Archived Repository (if present in response)
                repo_data = data.get("repository", {})
                if repo_data.get("archived") is True or data.get("archived") is True:
                    res = VerificationResult(
                        is_valid=False,
                        status="excluded",
                        reason="Repository is archived and read-only",
                        canonical_repo=canonical_repo,
                        canonical_url=canonical_url,
                        response_metadata=res_metadata
                    )
                    self._cache_result(cache_key, res, etag)
                    return res

                # Check Lock Reason
                if data.get("locked") and data.get("active_lock_reason") in ("resolved", "off-topic", "spam"):
                    res = VerificationResult(
                        is_valid=False,
                        status="excluded",
                        reason=f"Issue is locked ({data.get('active_lock_reason')})",
                        canonical_repo=canonical_repo,
                        canonical_url=canonical_url,
                        response_metadata=res_metadata
                    )
                    self._cache_result(cache_key, res, etag)
                    return res

                # Confirmed valid
                res = VerificationResult(
                    is_valid=True,
                    status="live_verified",
                    reason="Live GitHub state confirmed: Open & Unassigned",
                    canonical_repo=canonical_repo,
                    canonical_url=canonical_url,
                    response_metadata=res_metadata
                )
                self._cache_result(cache_key, res, etag)
                return res

            except (Timeout, ConnectionError) as e:
                last_error = e
                if attempt < retries_val - 1:
                    backoff = 0.5 * (2 ** attempt)
                    logger.warning("Network timeout/connection error connecting to GitHub (%s). Retrying in %.2fs (attempt %d/%d)...",
                                   str(e), backoff, attempt + 1, retries_val)
                    time.sleep(backoff)
                else:
                    # Timeout / Network failure after retries: inconclusive state, NOT proof of closure!
                    return VerificationResult(
                        is_valid=False,
                        status="inconclusive",
                        reason=f"GitHub connection timeout or network outage after {retries_val} attempts ({str(e)})",
                        canonical_repo=repo_full_name,
                        is_operational_failure=True,
                        is_inconclusive=True,
                        response_metadata={"network_error": str(e)}
                    )
            except Exception as e:
                logger.error("Unexpected error during GitHub verification for %s#%d: %s", repo_full_name, issue_number, str(e))
                return VerificationResult(
                    is_valid=False,
                    status="inconclusive",
                    reason=f"Operational error verifying issue: {str(e)}",
                    canonical_repo=repo_full_name,
                    is_operational_failure=True,
                    is_inconclusive=True,
                    response_metadata={"error": str(e)}
                )

        return VerificationResult(
            is_valid=False,
            status="inconclusive",
            reason=f"Failed to verify after {retries_val} attempts: {str(last_error)}",
            canonical_repo=repo_full_name,
            is_operational_failure=True,
            is_inconclusive=True
        )

    def _cache_result(self, cache_key: Tuple[str, int], result: VerificationResult, etag: Optional[str]):
        """Thread-safe update to short-lived verification cache."""
        with self._lock:
            self._cache[cache_key] = {
                "cached_at": time.time(),
                "etag": etag,
                "is_valid": result.is_valid,
                "status": result.status,
                "reason": result.reason,
                "canonical_repo": result.canonical_repo,
                "canonical_url": result.canonical_url,
                "is_operational_failure": result.is_operational_failure,
                "is_inconclusive": result.is_inconclusive
            }

    def verify_and_backfill(
        self,
        ranked_items: List[Dict[str, Any]],
        target_count: int = 5,
        max_budget: int = 30,
        demo_mode: bool = False,
        timeout: Optional[int] = None,
        max_retries: Optional[int] = None,
        concurrency: Optional[int] = None
    ) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], Dict[str, Any]]:
        """
        Verify candidates in descending rank order until target_count valid recommendations
        have been collected or max_budget is exhausted.
        Backfills lower-ranked candidates when higher-ranked ones are excluded.
        Applies bounded concurrency while strictly preserving rank-order priority.

        Returns:
            Tuple of:
            1. delivered_recommendations: List[Dict[str, Any]] (at most target_count valid candidates)
            2. audited_candidates: List[Dict[str, Any]] (all evaluated candidates with decisions and reasons)
            3. summary_metrics: Dict[str, Any]
        """
        start_time = time.time()
        now_iso = datetime.now(timezone.utc).isoformat()

        if not ranked_items:
            return [], [], {
                "candidates_ranked": 0,
                "shortlist_size": 0,
                "candidates_verified": 0,
                "candidates_excluded": 0,
                "candidates_inconclusive": 0,
                "delivered_count": 0,
                "is_partial_result": True,
                "partial_result_reason": "No ranked candidates provided.",
                "warning": None,
                "duration_ms": 0
            }

        shortlist = ranked_items[:max_budget]
        delivered_recommendations: List[Dict[str, Any]] = []
        audited_candidates: List[Dict[str, Any]] = []

        count_verified = 0
        count_excluded = 0
        count_inconclusive = 0

        eff_concurrency = max(1, concurrency or settings.verification_concurrency)

        # Batch verification loop with bounded concurrency
        batch_size = eff_concurrency
        i = 0
        target_fulfilled = False

        while i < len(shortlist) and not target_fulfilled:
            chunk = shortlist[i:i + batch_size]
            results_map: Dict[int, VerificationResult] = {}

            # Identify which items need live network calls
            tasks_to_run = []
            for idx_in_chunk, candidate in enumerate(chunk):
                global_idx = i + idx_in_chunk
                repo_full_name = candidate.get("repository_full_name", "")
                issue_number = candidate.get("issue_number", 0)
                is_sample = (
                    demo_mode
                    or candidate.get("id", "").startswith("sample-")
                    or not repo_full_name
                    or "/" not in repo_full_name
                )
                if is_sample:
                    results_map[global_idx] = VerificationResult(
                        is_valid=True,
                        status="sample_verified",
                        reason="Verified deterministically in demonstration environment",
                        canonical_repo=repo_full_name
                    )
                else:
                    tasks_to_run.append((global_idx, repo_full_name, issue_number))

            # Concurrently verify live tasks for this chunk
            if tasks_to_run:
                if len(tasks_to_run) == 1:
                    g_idx, r_name, i_num = tasks_to_run[0]
                    results_map[g_idx] = self.verify_issue(r_name, i_num, timeout=timeout, max_retries=max_retries)
                else:
                    with ThreadPoolExecutor(max_workers=min(eff_concurrency, len(tasks_to_run))) as executor:
                        future_to_idx = {
                            executor.submit(self.verify_issue, r_name, i_num, timeout, max_retries): g_idx
                            for g_idx, r_name, i_num in tasks_to_run
                        }
                        for future in future_to_idx:
                            g_idx = future_to_idx[future]
                            try:
                                results_map[g_idx] = future.result()
                            except Exception as e:
                                results_map[g_idx] = VerificationResult(
                                    is_valid=False,
                                    status="inconclusive",
                                    reason=f"Executor worker failure: {str(e)}",
                                    is_operational_failure=True,
                                    is_inconclusive=True
                                )

            # Evaluate chunk in exact rank order
            for idx_in_chunk, candidate in enumerate(chunk):
                global_idx = i + idx_in_chunk
                original_rank = global_idx + 1
                original_score = (
                    candidate.get("scores", {}).get("final_score")
                    if isinstance(candidate.get("scores"), dict)
                    else candidate.get("final_score", 0.0)
                )

                if target_fulfilled:
                    # Skip candidate once target count is reached
                    skip_audit = dict(candidate)
                    skip_audit["original_rank"] = original_rank
                    skip_audit["original_score"] = original_score
                    skip_audit["delivered_rank"] = None
                    skip_audit["rank"] = None
                    skip_audit["decision"] = "skipped"
                    skip_audit["exclusion_reason"] = "Target recommendation count fulfilled"
                    skip_audit["verification_status"] = "skipped"
                    audited_candidates.append(skip_audit)
                    continue

                ver_res = results_map[global_idx]

                if ver_res.is_valid:
                    count_verified += 1
                    delivered_rank = len(delivered_recommendations) + 1
                    item = dict(candidate)
                    item["original_rank"] = original_rank
                    item["original_score"] = original_score
                    item["delivered_rank"] = delivered_rank
                    item["rank"] = delivered_rank
                    item["verified"] = True
                    item["verification_status"] = ver_res.status
                    item["verified_at"] = now_iso
                    item["last_verified_at"] = now_iso
                    item["verification_notes"] = ver_res.reason
                    item["decision"] = "included"
                    item["exclusion_reason"] = None
                    item["response_metadata"] = ver_res.response_metadata

                    # Update canonical repo / URL if redirected/renamed
                    repo_full_name = candidate.get("repository_full_name", "")
                    issue_number = candidate.get("issue_number", 0)
                    if ver_res.canonical_repo and ver_res.canonical_repo != repo_full_name:
                        item["repository_full_name"] = ver_res.canonical_repo
                        item["canonical_repo"] = ver_res.canonical_repo
                    if ver_res.canonical_url:
                        item["canonical_url"] = ver_res.canonical_url
                        item["issue_url"] = ver_res.canonical_url

                    delivered_recommendations.append(item)
                    audited_candidates.append(item)

                    # Update MongoDB Atlas candidate pool state if live
                    if db.db is not None and ver_res.status == "live_verified":
                        try:
                            update_fields = {
                                "verification_status": "live_verified",
                                "last_verified_at": now_iso,
                                "state": "open",
                                "is_assigned": False
                            }
                            if ver_res.canonical_repo:
                                update_fields["repository_full_name"] = ver_res.canonical_repo
                            if ver_res.canonical_url:
                                update_fields["issue_url"] = ver_res.canonical_url
                            db.db.issues.update_one(
                                {"repository_full_name": repo_full_name, "issue_number": issue_number},
                                {"$set": update_fields}
                            )
                        except Exception as e:
                            logger.debug("Could not update candidate pool in Atlas: %s", str(e))

                    if len(delivered_recommendations) >= target_count:
                        target_fulfilled = True

                elif ver_res.status == "excluded":
                    count_excluded += 1
                    repo_full_name = candidate.get("repository_full_name", "")
                    issue_number = candidate.get("issue_number", 0)
                    logger.info("Excluding candidate #%d in %s: %s", issue_number, repo_full_name, ver_res.reason)
                    audit_item = dict(candidate)
                    audit_item["original_rank"] = original_rank
                    audit_item["original_score"] = original_score
                    audit_item["delivered_rank"] = None
                    audit_item["rank"] = None
                    audit_item["verified"] = False
                    audit_item["verification_status"] = "excluded"
                    audit_item["verified_at"] = now_iso
                    audit_item["last_verified_at"] = now_iso
                    audit_item["verification_notes"] = ver_res.reason
                    audit_item["decision"] = "rejected"
                    audit_item["exclusion_reason"] = ver_res.reason
                    audit_item["response_metadata"] = ver_res.response_metadata
                    audited_candidates.append(audit_item)

                    # Update MongoDB Atlas candidate pool record
                    if db.db is not None:
                        try:
                            pool_updates = {
                                "verification_status": "excluded",
                                "last_verified_at": now_iso,
                                "verification_notes": ver_res.reason
                            }
                            if "closed" in ver_res.reason.lower():
                                pool_updates["state"] = "closed"
                            elif "assigned" in ver_res.reason.lower():
                                pool_updates["is_assigned"] = True
                            db.db.issues.update_one(
                                {"repository_full_name": repo_full_name, "issue_number": issue_number},
                                {"$set": pool_updates}
                            )
                        except Exception as e:
                            logger.debug("Could not update candidate exclusion in Atlas: %s", str(e))

                else:
                    # Inconclusive operational failure (rate limit, network outage, timeout)
                    # DO NOT label the issue closed or invalid!
                    count_inconclusive += 1
                    repo_full_name = candidate.get("repository_full_name", "")
                    issue_number = candidate.get("issue_number", 0)
                    logger.warning("Candidate verification inconclusive for #%d in %s: %s", issue_number, repo_full_name, ver_res.reason)
                    audit_item = dict(candidate)
                    audit_item["original_rank"] = original_rank
                    audit_item["original_score"] = original_score
                    audit_item["delivered_rank"] = None
                    audit_item["rank"] = None
                    audit_item["verified"] = False
                    audit_item["verification_status"] = "inconclusive"
                    audit_item["verified_at"] = now_iso
                    audit_item["last_verified_at"] = now_iso
                    audit_item["verification_notes"] = ver_res.reason
                    audit_item["decision"] = "inconclusive"
                    audit_item["exclusion_reason"] = ver_res.reason
                    audit_item["response_metadata"] = ver_res.response_metadata
                    audited_candidates.append(audit_item)

            i += batch_size

        # Any remaining un-evaluated candidates in shortlist after target fulfilled
        while i < len(shortlist):
            for candidate in shortlist[i:i + batch_size]:
                original_rank = len(audited_candidates) + 1
                skip_audit = dict(candidate)
                skip_audit["original_rank"] = original_rank
                skip_audit["original_score"] = (
                    candidate.get("scores", {}).get("final_score")
                    if isinstance(candidate.get("scores"), dict)
                    else candidate.get("final_score", 0.0)
                )
                skip_audit["delivered_rank"] = None
                skip_audit["rank"] = None
                skip_audit["decision"] = "skipped"
                skip_audit["exclusion_reason"] = "Target recommendation count fulfilled"
                skip_audit["verification_status"] = "skipped"
                audited_candidates.append(skip_audit)
            i += batch_size

        elapsed_ms = int((time.time() - start_time) * 1000)
        is_partial = len(delivered_recommendations) < target_count

        warning = None
        if count_inconclusive > 0:
            warning = f"Verification was inconclusive for {count_inconclusive} candidate(s) due to operational limits (GitHub rate limits or network timeouts). Inconclusive candidates were not assumed closed."

        partial_reason = None
        if is_partial:
            partial_reason = (
                f"Delivered {len(delivered_recommendations)} of {target_count} requested recommendations. "
                f"Evaluated {len(shortlist)} candidates: {count_verified} verified, {count_excluded} excluded, "
                f"{count_inconclusive} inconclusive."
            )

        summary_metrics = {
            "candidates_ranked": len(ranked_items),
            "shortlist_size": len(shortlist),
            "candidates_verified": count_verified,
            "candidates_excluded": count_excluded,
            "candidates_inconclusive": count_inconclusive,
            "delivered_count": len(delivered_recommendations),
            "is_partial_result": is_partial,
            "partial_result_reason": partial_reason,
            "warning": warning,
            "duration_ms": elapsed_ms
        }

        return delivered_recommendations, audited_candidates, summary_metrics


live_verifier = LiveVerifier()
