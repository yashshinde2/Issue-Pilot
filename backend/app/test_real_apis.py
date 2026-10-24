import sys
import os
import json
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from app.config import settings
# Reload settings to pick up updated .env
from app.config import Settings
fresh_settings = Settings()

from app.adapters.github_adapter import GitHubAdapter
from app.adapters.gitlab_adapter import GitLabAdapter
from app.adapters.codeberg_adapter import CodebergAdapter
from app.adapters.bugzilla_adapter import BugzillaAdapter

def run_tests():
    results = {}
    
    # 1. GitHub Test
    print("=== Testing GitHub API ===")
    gh = GitHubAdapter(token=fresh_settings.github_token, base_url=fresh_settings.github_api_base_url)
    gh_health = gh.check_health()
    print("GitHub Health:", {k: v for k, v in gh_health.items() if k != "token"})
    
    print("Testing GitHub Issue Search...")
    gh_search = gh.search_issues("is:issue is:open no:assignee language:python", page=1, per_page=5)
    gh_items = gh_search.get("items", [])
    print(f"GitHub Search returned {len(gh_items)} items. Total: {gh_search.get('total_count')}")
    gh_sample_normalized = None
    if gh_items:
        gh_sample_normalized = gh.normalize_issue(gh_items[0], query_info={"category": "Python"})
        print("GitHub Sample normalized URL:", gh_sample_normalized.get("canonical_url") if gh_sample_normalized else None)
        print("GitHub Sample required fields:", {k: bool(gh_sample_normalized.get(k)) for k in ["id", "title", "issue_url", "source", "state", "repository_full_name"]})
    print("GitHub Rate Limit:", gh.get_rate_limit())
    
    # Test Pagination
    gh_search_p2 = gh.search_issues("is:issue is:open no:assignee language:python", page=2, per_page=2)
    print(f"GitHub Pagination Page 2 returned {len(gh_search_p2.get('items', []))} items")

    results["github"] = {
        "health": gh_health,
        "items_count": len(gh_items),
        "total_count": gh_search.get("total_count"),
        "pagination_ok": len(gh_search_p2.get("items", [])) > 0,
        "sample_valid": bool(gh_sample_normalized and gh_sample_normalized.get("canonical_url")),
        "rate_limit": gh.get_rate_limit()
    }

    # 2. GitLab Test
    print("\n=== Testing GitLab API ===")
    gl = GitLabAdapter(token=fresh_settings.gitlab_token, base_url=fresh_settings.gitlab_api_base_url)
    gl_health = gl.check_health()
    print("GitLab Health:", {k: v for k, v in gl_health.items() if k != "token"})
    
    print("Testing GitLab Issue Search...")
    gl_search = gl.search_issues("python", page=1, per_page=5)
    gl_items = gl_search.get("items", [])
    print(f"GitLab Search returned {len(gl_items)} items. Total: {gl_search.get('total_count')}")
    gl_sample_normalized = None
    if gl_items:
        gl_sample_normalized = gl.normalize_issue(gl_items[0], query_info={"category": "Python"})
        print("GitLab Sample normalized URL:", gl_sample_normalized.get("canonical_url") if gl_sample_normalized else None)
        print("GitLab Sample required fields:", {k: bool(gl_sample_normalized.get(k)) for k in ["id", "title", "issue_url", "source", "state", "repository_full_name"]})
    print("GitLab Rate Limit:", gl.get_rate_limit())

    gl_search_p2 = gl.search_issues("python", page=2, per_page=2)
    print(f"GitLab Pagination Page 2 returned {len(gl_search_p2.get('items', []))} items")

    results["gitlab"] = {
        "health": gl_health,
        "items_count": len(gl_items),
        "total_count": gl_search.get("total_count"),
        "pagination_ok": len(gl_search_p2.get("items", [])) > 0,
        "sample_valid": bool(gl_sample_normalized and gl_sample_normalized.get("canonical_url")),
        "rate_limit": gl.get_rate_limit()
    }

    # 3. Codeberg Test
    print("\n=== Testing Codeberg / Forgejo API ===")
    cb = CodebergAdapter(token=fresh_settings.codeberg_token, base_url=fresh_settings.codeberg_api_base_url)
    cb_health = cb.check_health()
    print("Codeberg Health:", {k: v for k, v in cb_health.items() if k != "token"})
    
    print("Testing Codeberg Issue Search...")
    cb_search = cb.search_issues("python", page=1, per_page=5)
    cb_items = cb_search.get("items", [])
    print(f"Codeberg Search returned {len(cb_items)} items. Total: {cb_search.get('total_count')}")
    cb_sample_normalized = None
    if cb_items:
        cb_sample_normalized = cb.normalize_issue(cb_items[0], query_info={"category": "Python"})
        print("Codeberg Sample normalized URL:", cb_sample_normalized.get("canonical_url") if cb_sample_normalized else None)
        print("Codeberg Sample required fields:", {k: bool(cb_sample_normalized.get(k)) for k in ["id", "title", "issue_url", "source", "state", "repository_full_name"]})
    print("Codeberg Rate Limit:", cb.get_rate_limit())

    cb_search_p2 = cb.search_issues("python", page=2, per_page=2)
    print(f"Codeberg Pagination Page 2 returned {len(cb_search_p2.get('items', []))} items")

    results["codeberg"] = {
        "health": cb_health,
        "items_count": len(cb_items),
        "total_count": cb_search.get("total_count"),
        "pagination_ok": len(cb_search_p2.get("items", [])) > 0,
        "sample_valid": bool(cb_sample_normalized and cb_sample_normalized.get("canonical_url")),
        "rate_limit": cb.get_rate_limit()
    }

    # 4. Bugzilla Test
    print("\n=== Testing Bugzilla API ===")
    bz = BugzillaAdapter(base_url=fresh_settings.bugzilla_base_url, api_key=fresh_settings.bugzilla_api_key)
    bz_health = bz.check_health()
    print("Bugzilla Health:", {k: v for k, v in bz_health.items() if k != "token" and k != "api_key"})
    
    print("Testing Bugzilla Bug Search...")
    bz_search = bz.search_issues("crash", page=1, per_page=5)
    bz_items = bz_search.get("items", [])
    print(f"Bugzilla Search returned {len(bz_items)} items. Total: {bz_search.get('total_count')}")
    bz_sample_normalized = None
    if bz_items:
        bz_sample_normalized = bz.normalize_issue(bz_items[0], query_info={"category": "Crash"})
        print("Bugzilla Sample normalized URL:", bz_sample_normalized.get("canonical_url") if bz_sample_normalized else None)
        print("Bugzilla Sample required fields:", {k: bool(bz_sample_normalized.get(k)) for k in ["id", "title", "issue_url", "source", "state", "product"]})
    print("Bugzilla Rate Limit / Budget:", bz.get_rate_limit())

    bz_search_p2 = bz.search_issues("crash", page=2, per_page=2)
    print(f"Bugzilla Pagination Page 2 returned {len(bz_search_p2.get('items', []))} items")

    results["bugzilla"] = {
        "health": bz_health,
        "items_count": len(bz_items),
        "total_count": bz_search.get("total_count"),
        "pagination_ok": len(bz_search_p2.get("items", [])) > 0,
        "sample_valid": bool(bz_sample_normalized and bz_sample_normalized.get("canonical_url")),
        "rate_limit": bz.get_rate_limit()
    }

    print("\n=== Summary of All 4 APIs ===")
    for k, v in results.items():
        h = v["health"]
        print(f"[{k.upper()}] Configured: {h.get('configured')}, Reachable: {h.get('reachable')}, Auth: {h.get('auth_status')}, Issues fetched: {v['items_count']}, Pagination: {v['pagination_ok']}, Normalized sample valid: {v['sample_valid']}")

if __name__ == "__main__":
    run_tests()
