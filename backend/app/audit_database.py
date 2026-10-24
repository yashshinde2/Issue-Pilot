import sys
import os
import json
from collections import defaultdict
from bson import ObjectId

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from app.config import settings
from app.database import db

def audit_database():
    if not db.connect():
        print("Failed to connect to MongoDB Atlas.")
        return
    
    database = db.db
    print(f"Connected to database: {database.name}")
    
    # List collections
    collections = database.list_collection_names()
    print("Collections found:", collections)
    
    # Audit each collection counts
    col_counts = {}
    for col_name in ["issues", "users", "recommendations", "feedback", "jobs"]:
        if col_name in collections:
            col_counts[col_name] = database[col_name].count_documents({})
        else:
            col_counts[col_name] = 0
    print("Collection document counts:", col_counts)

    # 1. Indexes on issues
    issue_indexes = list(database.issues.list_indexes())
    index_names = [idx.get("name") for idx in issue_indexes]
    print("\nExisting indexes on 'issues':")
    for idx in issue_indexes:
        print(f" - {idx.get('name')}: keys={idx.get('key')}, unique={idx.get('unique', False)}, sparse={idx.get('sparse', False)}")

    # 2. Inspect all issue documents
    total_issues = col_counts["issues"]
    print(f"\nTotal issue documents: {total_issues}")

    if total_issues == 0:
        print("Issues collection is currently empty.")
        return

    by_source = defaultdict(int)
    by_state = defaultdict(int)
    by_stale_status = defaultdict(int)
    by_source_and_state = defaultdict(int)
    
    duplicate_ids = defaultdict(list)
    duplicate_natural_keys = defaultdict(list)
    
    missing_required_fields = []
    invalid_urls = []
    mock_or_demo_records = []
    conflicting_identities = []
    closed_or_ineligible_in_active_pool = []
    
    cursor = database.issues.find({})
    for doc in cursor:
        doc_id = str(doc.get("_id"))
        custom_id = doc.get("id")
        source = doc.get("source")
        state = doc.get("state")
        stale_status = doc.get("stale_status")
        repo = doc.get("repository_full_name")
        issue_num = doc.get("issue_number")
        url = doc.get("canonical_url") or doc.get("issue_url") or ""
        title = doc.get("title") or ""
        
        by_source[str(source)] += 1
        by_state[str(state)] += 1
        by_stale_status[str(stale_status)] += 1
        by_source_and_state[f"{source}:{state}"] += 1
        
        # Check duplicates
        if custom_id:
            duplicate_ids[custom_id].append(doc_id)
        if repo and issue_num is not None:
            natural_key = f"{source}::{repo}::{issue_num}"
            duplicate_natural_keys[natural_key].append(doc_id)
            
        # Check required fields: id, title, state, source, issue_url
        missing = []
        for req_field in ["id", "title", "state", "source", "issue_url"]:
            if not doc.get(req_field):
                missing.append(req_field)
        if missing:
            missing_required_fields.append({"_id": doc_id, "custom_id": custom_id, "missing": missing})
            
        # Check invalid URLs
        if not url or not (url.startswith("http://") or url.startswith("https://")):
            invalid_urls.append({"_id": doc_id, "custom_id": custom_id, "url": url})
            
        # Check placeholder or demo/mock records
        is_demo = False
        if any(marker in title.lower() for marker in ["[mock]", "[demo]", "sample issue", "test issue", "placeholder"]):
            is_demo = True
        if any(marker in str(custom_id).lower() for marker in ["mock", "demo_issue", "sample"]):
            is_demo = True
        if "example.com" in url or "your-bugzilla" in url or "localhost" in url:
            is_demo = True
        if is_demo:
            mock_or_demo_records.append({"_id": doc_id, "custom_id": custom_id, "title": title, "url": url})
            
        # Check conflicting identities (e.g. source says github but url is gitlab or vice versa)
        if source == "github" and "github.com" not in url and url:
            conflicting_identities.append({"_id": doc_id, "source": source, "url": url})
        elif source == "gitlab" and "gitlab.com" not in url and url:
            conflicting_identities.append({"_id": doc_id, "source": source, "url": url})
        elif source == "codeberg" and "codeberg.org" not in url and url:
            conflicting_identities.append({"_id": doc_id, "source": source, "url": url})
        elif source == "bugzilla" and "bugzilla" not in url and url:
            conflicting_identities.append({"_id": doc_id, "source": source, "url": url})

        # Check closed, stale, or ineligible in active candidate pool
        # Active candidate pool definition: state == "open" and stale_status in ("active", "needs_refresh")
        is_in_active_pool = (state == "open" and stale_status in ("active", "needs_refresh", None))
        if is_in_active_pool:
            if doc.get("is_assigned") is True:
                # Note: unassigned is checked during recommendation
                pass
            if state != "open":
                closed_or_ineligible_in_active_pool.append({"_id": doc_id, "custom_id": custom_id, "reason": f"state is {state}"})
            if is_demo:
                closed_or_ineligible_in_active_pool.append({"_id": doc_id, "custom_id": custom_id, "reason": "mock/demo record"})

    actual_dup_ids = {k: v for k, v in duplicate_ids.items() if len(v) > 1}
    actual_dup_natural = {k: v for k, v in duplicate_natural_keys.items() if len(v) > 1}

    print("\n--- Breakdown by Source ---")
    for s, count in by_source.items():
        print(f"  {s}: {count}")

    print("\n--- Breakdown by State ---")
    for st, count in by_state.items():
        print(f"  {st}: {count}")

    print("\n--- Breakdown by Stale Status ---")
    for ss, count in by_stale_status.items():
        print(f"  {ss}: {count}")

    print("\n--- Breakdown by Source and State ---")
    for ss, count in by_source_and_state.items():
        print(f"  {ss}: {count}")

    print(f"\nDuplicate Custom IDs: {len(actual_dup_ids)}")
    if actual_dup_ids:
        print("  Sample duplicates:", list(actual_dup_ids.items())[:5])

    print(f"Duplicate Natural Keys: {len(actual_dup_natural)}")
    if actual_dup_natural:
        print("  Sample duplicate natural keys:", list(actual_dup_natural.items())[:5])

    print(f"Missing Required Fields: {len(missing_required_fields)}")
    if missing_required_fields:
        print("  Sample missing fields:", missing_required_fields[:5])

    print(f"Invalid URLs: {len(invalid_urls)}")
    if invalid_urls:
        print("  Sample invalid URLs:", invalid_urls[:5])

    print(f"Mock/Demo/Placeholder records: {len(mock_or_demo_records)}")
    if mock_or_demo_records:
        print("  Sample mock records:", mock_or_demo_records[:5])

    print(f"Conflicting source identities: {len(conflicting_identities)}")
    if conflicting_identities:
        print("  Sample conflicting:", conflicting_identities[:5])

    print(f"Ineligible records in active pool: {len(closed_or_ineligible_in_active_pool)}")
    if closed_or_ineligible_in_active_pool:
        print("  Sample ineligible:", closed_or_ineligible_in_active_pool[:5])

if __name__ == "__main__":
    audit_database()
