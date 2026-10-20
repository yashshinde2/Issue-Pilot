import logging
from datetime import datetime, timezone, timedelta
from typing import List, Dict, Any, Optional, Union
from pymongo.errors import DuplicateKeyError, PyMongoError, ConnectionFailure, ServerSelectionTimeoutError
from bson import ObjectId

from app.database import db
from app.models import ContributorProfile, IssueRecord, FeedbackEvent, JobRecord, RecommendationRecord

logger = logging.getLogger(__name__)

class DatabaseConnectionError(Exception):
    """Raised when MongoDB connection is unavailable."""
    pass

class RecordNotFoundError(Exception):
    """Raised when a requested database record is not found."""
    pass

class DuplicateRecordError(Exception):
    """Raised when a unique constraint / index is violated."""
    pass

class InvalidDataError(Exception):
    """Raised when provided document data is malformed or invalid."""
    pass

def _ensure_connected(db_instance=None):
    target = db_instance if db_instance is not None else db.db
    if target is None:
        raise DatabaseConnectionError("MongoDB database connection is not established or unavailable.")
    return target

def _clean_doc(doc: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if doc is None:
        return None
    if "_id" in doc:
        doc["_id"] = str(doc["_id"])
    return doc


class IssueRepository:
    def __init__(self, db_instance=None):
        self._db = db_instance

    def _get_db(self):
        return _ensure_connected(self._db)

    def upsert_issue(self, issue_data: Union[dict, IssueRecord], preserve_unsupplied: bool = True) -> Dict[str, Any]:
        """
        Idempotent upsert for an issue record.
        Enforces natural identity (source, repository_full_name, issue_number).
        Preserves existing fields not supplied in partial updates.
        """
        database = self._get_db()

        if isinstance(issue_data, IssueRecord):
            data = issue_data.model_dump() if hasattr(issue_data, "model_dump") else issue_data.dict()
        elif isinstance(issue_data, dict):
            data = dict(issue_data)
        else:
            raise InvalidDataError("issue_data must be a dict or IssueRecord")

        # Validate natural key identity
        source = data.get("source", "github")
        repo_full_name = data.get("repository_full_name")
        issue_number = data.get("issue_number")

        if not repo_full_name or issue_number is None:
            raise InvalidDataError("repository_full_name and issue_number are required for issue identity.")

        try:
            issue_number = int(issue_number)
        except (ValueError, TypeError):
            raise InvalidDataError(f"issue_number must be an integer, got: {issue_number}")

        # Ensure ID is generated if not provided
        if not data.get("id"):
            data["id"] = f"{source}_{repo_full_name}_{issue_number}".replace("/", "_")

        filter_query = {
            "source": source,
            "repository_full_name": repo_full_name,
            "issue_number": issue_number
        }

        now_iso = datetime.now(timezone.utc).isoformat()
        
        # Strip mongo _id if passed
        data.pop("_id", None)

        if preserve_unsupplied:
            first_seen_val = data.get("first_seen_at") or now_iso
            created_at_val = data.get("created_at") or now_iso

            # Partial update preserves fields that are not supplied in data
            update_fields = {k: v for k, v in data.items() if v is not None}
            if "last_seen_at" not in update_fields:
                update_fields["last_seen_at"] = now_iso
            if "updated_at" not in update_fields:
                update_fields["updated_at"] = now_iso

            # Fields in $setOnInsert must never appear in $set to prevent MongoDB path conflict
            update_fields.pop("first_seen_at", None)
            update_fields.pop("created_at", None)

            update_op = {
                "$set": update_fields,
                "$setOnInsert": {
                    "first_seen_at": first_seen_val,
                    "created_at": created_at_val
                }
            }
        else:
            update_op = {"$set": data}

        try:
            res = database.issues.update_one(filter_query, update_op, upsert=True)
            saved = database.issues.find_one(filter_query)
            return _clean_doc(saved)
        except DuplicateKeyError as e:
            raise DuplicateRecordError(f"Unique constraint violation on issue identity: {str(e)}")
        except (ConnectionFailure, ServerSelectionTimeoutError) as e:
            raise DatabaseConnectionError(f"MongoDB connection failed during issue upsert: {str(e)}")
        except PyMongoError as e:
            raise InvalidDataError(f"MongoDB error during issue upsert: {str(e)}")

    def get_issue_by_id(self, issue_id: str) -> Optional[Dict[str, Any]]:
        database = self._get_db()
        doc = database.issues.find_one({"id": issue_id})
        if not doc and ObjectId.is_valid(issue_id):
            doc = database.issues.find_one({"_id": ObjectId(issue_id)})
        return _clean_doc(doc)

    def get_issue_by_natural_key(self, source: str, repo: str, issue_number: int) -> Optional[Dict[str, Any]]:
        database = self._get_db()
        doc = database.issues.find_one({
            "source": source,
            "repository_full_name": repo,
            "issue_number": issue_number
        })
        return _clean_doc(doc)

    def query_issues(
        self,
        state: Optional[str] = "open",
        is_assigned: Optional[bool] = False,
        technology: Optional[str] = None,
        min_difficulty: Optional[float] = None,
        max_difficulty: Optional[float] = None,
        search: Optional[str] = None,
        limit: int = 50,
        skip: int = 0
    ) -> List[Dict[str, Any]]:
        """
        Query issues with filters for open status, technology, difficulty, and eligibility.
        """
        database = self._get_db()
        query: Dict[str, Any] = {}

        if state and state.lower() != "all":
            query["state"] = state.lower()
            if state.lower() == "open":
                query["stale_status"] = {"$nin": ["closed", "unavailable"]}

        if is_assigned is not None:
            query["is_assigned"] = is_assigned

        # Eligibility rule: issue_url must not be empty
        query["issue_url"] = {"$exists": True, "$ne": ""}

        if technology:
            # Match case-insensitively against detected_technologies or technologies.technology
            regex_pat = f"^{technology.strip()}$"
            query["$or"] = [
                {"detected_technologies": {"$regex": regex_pat, "$options": "i"}},
                {"technologies.technology": {"$regex": regex_pat, "$options": "i"}}
            ]

        # Difficulty range filter on technical complexity or scope
        diff_conditions = {}
        if min_difficulty is not None:
            diff_conditions["$gte"] = float(min_difficulty)
        if max_difficulty is not None:
            diff_conditions["$lte"] = float(max_difficulty)
        if diff_conditions:
            query["difficulty_evidence.technical_complexity"] = diff_conditions

        if search:
            if "idx_text" in database.issues.index_information():
                query["$text"] = {"$search": search}
            else:
                query["$or"] = [
                    {"title": {"$regex": search, "$options": "i"}},
                    {"repository_full_name": {"$regex": search, "$options": "i"}}
                ]

        cursor = database.issues.find(query).sort("updated_at", -1).skip(skip).limit(limit)
        return [_clean_doc(d) for d in cursor]

    def count_issues(self, query: Optional[dict] = None) -> int:
        database = self._get_db()
        return database.issues.count_documents(query or {})

    def count_by_repository(self, repo_full_name: str) -> int:
        """Count active/open issues for a repository to enforce diversity limits."""
        database = self._get_db()
        return database.issues.count_documents({
            "repository_full_name": repo_full_name,
            "state": "open",
            "stale_status": {"$nin": ["closed", "unavailable"]}
        })

    def get_candidates_for_refresh(self, limit: int = 50, max_age_days: int = 3) -> List[Dict[str, Any]]:
        """
        Retrieve a bounded batch of stored open candidates for refresh.
        Prioritizes candidates marked 'needs_refresh' or with the oldest 'last_seen_at'.
        """
        database = self._get_db()
        cutoff_date = (datetime.now(timezone.utc) - timedelta(days=max_age_days)).isoformat()
        query = {
            "state": "open",
            "stale_status": {"$in": ["active", "needs_refresh"]},
            "$or": [
                {"stale_status": "needs_refresh"},
                {"last_seen_at": {"$lt": cutoff_date}},
                {"last_seen_at": None},
                {"last_seen_at": {"$exists": False}}
            ]
        }
        cursor = database.issues.find(query).sort("last_seen_at", 1).limit(limit)
        return [_clean_doc(d) for d in cursor]

    def get_stale_candidates(self, older_than_days: int = 90) -> List[Dict[str, Any]]:
        """Find active candidates that have not been seen within older_window_days."""
        database = self._get_db()
        cutoff_date = (datetime.now(timezone.utc) - timedelta(days=older_than_days)).isoformat()
        query = {
            "stale_status": {"$in": ["active", "needs_refresh"]},
            "last_seen_at": {"$lt": cutoff_date}
        }
        cursor = database.issues.find(query)
        return [_clean_doc(d) for d in cursor]

    def mark_issues_stale(self, issue_ids: List[str]) -> int:
        """Mark a list of issue records as stale without deleting them."""
        if not issue_ids:
            return 0
        database = self._get_db()
        now_iso = datetime.now(timezone.utc).isoformat()
        res = database.issues.update_many(
            {"id": {"$in": issue_ids}},
            {"$set": {"stale_status": "stale", "refresh_status": "stale", "updated_at": now_iso}}
        )
        return res.modified_count

    def mark_issue_closed(self, issue_id: str, closed_at: Optional[str] = None) -> bool:
        """Mark an issue closed while preserving it in MongoDB for historical analysis."""
        database = self._get_db()
        now_iso = datetime.now(timezone.utc).isoformat()
        res = database.issues.update_one(
            {"id": issue_id},
            {
                "$set": {
                    "state": "closed",
                    "stale_status": "closed",
                    "refresh_status": "closed",
                    "closed_at": closed_at or now_iso,
                    "updated_at": now_iso,
                    "last_verified_at": now_iso
                }
            }
        )
        return res.modified_count > 0

    def mark_issue_unavailable(self, issue_id: str) -> bool:
        """Mark an issue unavailable (e.g. 404 / deleted on GitHub)."""
        database = self._get_db()
        now_iso = datetime.now(timezone.utc).isoformat()
        res = database.issues.update_one(
            {"id": issue_id},
            {
                "$set": {
                    "stale_status": "unavailable",
                    "refresh_status": "unavailable",
                    "updated_at": now_iso,
                    "last_verified_at": now_iso
                }
            }
        )
        return res.modified_count > 0

    def get_pool_metrics(self) -> Dict[str, Any]:
        """Aggregate candidate pool metrics, repository diversity, and lifecycle states."""
        database = self._get_db()
        total_count = database.issues.count_documents({})
        open_count = database.issues.count_documents({"state": "open"})
        closed_count = database.issues.count_documents({"state": "closed"})

        status_counts = {}
        for st in ["active", "needs_refresh", "stale", "closed", "unavailable"]:
            status_counts[st] = database.issues.count_documents({"stale_status": st})

        unique_repos = len(database.issues.distinct("repository_full_name"))

        # Technology breakdown
        tech_pipeline = [
            {"$unwind": "$detected_technologies"},
            {"$group": {"_id": "$detected_technologies", "count": {"$sum": 1}}},
            {"$sort": {"count": -1}},
            {"$limit": 10}
        ]
        top_techs = [
            {"technology": item["_id"], "count": item["count"]}
            for item in database.issues.aggregate(tech_pipeline)
        ]

        # Top repositories by representation
        repo_pipeline = [
            {"$group": {"_id": "$repository_full_name", "count": {"$sum": 1}}},
            {"$sort": {"count": -1}},
            {"$limit": 10}
        ]
        top_repos = [
            {"repository": item["_id"], "count": item["count"]}
            for item in database.issues.aggregate(repo_pipeline)
        ]

        return {
            "total_candidates": total_count,
            "open_candidates": open_count,
            "closed_candidates": closed_count,
            "status_breakdown": status_counts,
            "unique_repositories": unique_repos,
            "top_technologies": top_techs,
            "top_repositories": top_repos
        }

    def delete_issue(self, issue_id: str) -> bool:
        database = self._get_db()
        res = database.issues.delete_one({"id": issue_id})
        if res.deleted_count == 0 and ObjectId.is_valid(issue_id):
            res = database.issues.delete_one({"_id": ObjectId(issue_id)})
        return res.deleted_count > 0


class UserRepository:
    def __init__(self, db_instance=None):
        self._db = db_instance

    def _get_db(self):
        return _ensure_connected(self._db)

    def create_user(self, profile: Union[dict, ContributorProfile]) -> Dict[str, Any]:
        database = self._get_db()

        if isinstance(profile, ContributorProfile):
            data = profile.model_dump() if hasattr(profile, "model_dump") else profile.dict()
        elif isinstance(profile, dict):
            data = dict(profile)
        else:
            raise InvalidDataError("profile must be a dict or ContributorProfile")

        user_id = data.get("user_id")
        if not user_id:
            raise InvalidDataError("user_id is required")

        now_iso = datetime.now(timezone.utc).isoformat()
        data["created_at"] = data.get("created_at") or now_iso
        data["updated_at"] = now_iso
        data.pop("_id", None)

        try:
            database.users.insert_one(data)
            return _clean_doc(database.users.find_one({"user_id": user_id}))
        except DuplicateKeyError:
            raise DuplicateRecordError(f"User with user_id '{user_id}' already exists.")
        except (ConnectionFailure, ServerSelectionTimeoutError) as e:
            raise DatabaseConnectionError(f"MongoDB connection failed: {str(e)}")
        except PyMongoError as e:
            raise InvalidDataError(f"MongoDB error: {str(e)}")

    def get_user(self, user_id: str) -> Optional[Dict[str, Any]]:
        database = self._get_db()
        doc = database.users.find_one({"user_id": user_id})
        return _clean_doc(doc)

    def update_user(self, user_id: str, updates: dict) -> Optional[Dict[str, Any]]:
        """
        Update user profile while preserving unsupplied fields.
        """
        database = self._get_db()
        clean_updates = {k: v for k, v in updates.items() if k != "_id"}
        now_iso = datetime.now(timezone.utc).isoformat()
        clean_updates["updated_at"] = now_iso

        res = database.users.update_one(
            {"user_id": user_id},
            {"$set": clean_updates}
        )
        if res.matched_count == 0:
            return None
        return _clean_doc(database.users.find_one({"user_id": user_id}))

    def upsert_user(self, profile: Union[dict, ContributorProfile]) -> Dict[str, Any]:
        """
        Idempotent upsert of user profile, preserving existing unsupplied fields.
        """
        database = self._get_db()
        if isinstance(profile, ContributorProfile):
            data = profile.model_dump() if hasattr(profile, "model_dump") else profile.dict()
        else:
            data = dict(profile)

        user_id = data.get("user_id")
        if not user_id:
            raise InvalidDataError("user_id is required")

        now_iso = datetime.now(timezone.utc).isoformat()
        data.pop("_id", None)
        update_fields = {k: v for k, v in data.items() if v is not None}
        update_fields["updated_at"] = now_iso

        database.users.update_one(
            {"user_id": user_id},
            {
                "$set": update_fields,
                "$setOnInsert": {"created_at": now_iso}
            },
            upsert=True
        )
        return _clean_doc(database.users.find_one({"user_id": user_id}))

    def delete_user(self, user_id: str) -> bool:
        database = self._get_db()
        res = database.users.delete_one({"user_id": user_id})
        return res.deleted_count > 0

    def list_users(self, limit: int = 50, skip: int = 0) -> List[Dict[str, Any]]:
        database = self._get_db()
        cursor = database.users.find({}).sort("updated_at", -1).skip(skip).limit(limit)
        return [_clean_doc(d) for d in cursor]


class RecommendationRepository:
    def __init__(self, db_instance=None):
        self._db = db_instance

    def _get_db(self):
        return _ensure_connected(self._db)

    def save_recommendations(
        self,
        user_id: str,
        recommendations: List[Dict[str, Any]],
        algorithm_version: str = "1.0.0-phase1",
        run_id: Optional[str] = None,
        audited_candidates: Optional[List[Dict[str, Any]]] = None
    ) -> int:
        database = self._get_db()
        items_to_persist = audited_candidates if audited_candidates is not None else recommendations
        if not items_to_persist:
            return 0

        now_iso = datetime.now(timezone.utc).isoformat()
        saved_count = 0

        for rec in items_to_persist:
            issue_id = rec.get("id") or rec.get("issue_id") or ""
            doc = {
                "user_id": user_id,
                "run_id": run_id or rec.get("run_id"),
                "issue_id": issue_id,
                "native_id": str(rec.get("issue_number", "") or rec.get("native_id", "")),
                "repository_full_name": rec.get("repository_full_name"),
                "original_rank": rec.get("original_rank") or rec.get("rank"),
                "original_score": rec.get("original_score") if rec.get("original_score") is not None else (
                    rec.get("scores", {}).get("final_score") if isinstance(rec.get("scores"), dict) else rec.get("final_score")
                ),
                "delivered_rank": rec.get("delivered_rank") if rec.get("delivered_rank") is not None else (
                    rec.get("rank") if rec.get("decision", "included") == "included" else None
                ),
                "rank": rec.get("rank") if rec.get("rank") is not None else rec.get("delivered_rank"),
                "final_score": rec.get("scores", {}).get("final_score") if isinstance(rec.get("scores"), dict) else rec.get("final_score"),
                "scores": rec.get("scores"),
                "effective_weights": rec.get("effective_weights"),
                "weighted_contributions": rec.get("weighted_contributions"),
                "missing_components": rec.get("missing_components", []),
                "explanation": rec.get("explanation"),
                "verification_status": rec.get("verification_status", "live_verified" if rec.get("verified") else "unverified"),
                "verified_at": rec.get("verified_at") or rec.get("last_verified_at") or now_iso,
                "verification_notes": rec.get("verification_notes"),
                "decision": rec.get("decision", "included"),
                "exclusion_reason": rec.get("exclusion_reason"),
                "response_metadata": rec.get("response_metadata"),
                "algorithm_version": algorithm_version,
                "generated_at": rec.get("generated_at") or now_iso
            }

            try:
                if run_id:
                    # Idempotent write keyed on (run_id, issue_id)
                    database.recommendations.update_one(
                        {"run_id": run_id, "issue_id": issue_id},
                        {"$set": doc},
                        upsert=True
                    )
                    saved_count += 1
                else:
                    database.recommendations.insert_one(doc)
                    saved_count += 1
            except PyMongoError as e:
                logger.error("Failed to persist recommendation record for issue %s: %s", issue_id, str(e))
                raise

        return saved_count

    def get_user_recommendations(self, user_id: str, limit: int = 10) -> List[Dict[str, Any]]:
        database = self._get_db()
        cursor = database.recommendations.find({"user_id": user_id}).sort("generated_at", -1).limit(limit)
        return [_clean_doc(d) for d in cursor]

    def get_run_recommendations(self, run_id: str) -> List[Dict[str, Any]]:
        database = self._get_db()
        cursor = database.recommendations.find({"run_id": run_id}).sort([("original_rank", 1), ("delivered_rank", 1)])
        return [_clean_doc(d) for d in cursor]


class FeedbackRepository:
    def __init__(self, db_instance=None):
        self._db = db_instance

    def _get_db(self):
        return _ensure_connected(self._db)

    def record_feedback(self, event: Union[dict, FeedbackEvent]) -> Dict[str, Any]:
        database = self._get_db()
        if isinstance(event, FeedbackEvent):
            data = event.model_dump() if hasattr(event, "model_dump") else event.dict()
        else:
            data = dict(event)

        user_id = data.get("user_id")
        issue_id = data.get("issue_id")
        event_type = data.get("event_type")

        if not user_id or not issue_id or not event_type:
            raise InvalidDataError("user_id, issue_id, and event_type are required.")

        if event_type not in ("accepted", "rejected", "completed"):
            raise InvalidDataError(f"Invalid event_type '{event_type}'. Must be accepted, rejected, or completed.")

        now_iso = data.get("event_timestamp") or datetime.now(timezone.utc).isoformat()
        record = {
            "user_id": user_id,
            "issue_id": issue_id,
            "event_type": event_type,
            "event_timestamp": now_iso,
            "recommendation_snapshot": data.get("recommendation_snapshot")
        }

        try:
            database.feedback.insert_one(record)

            # Update aggregated counter on user document atomically
            inc_field = f"{event_type}_count"
            database.users.update_one(
                {"user_id": user_id},
                {
                    "$inc": {inc_field: 1},
                    "$set": {"last_active_at": now_iso, "has_feedback_history": True}
                },
                upsert=True
            )
            return _clean_doc(record)
        except PyMongoError as e:
            logger.error("Failed to record feedback: %s", str(e))
            raise

    def get_user_feedback(self, user_id: str, limit: int = 50) -> Dict[str, Any]:
        database = self._get_db()
        cursor = database.feedback.find({"user_id": user_id}).sort("event_timestamp", -1).limit(limit)
        events = []
        counts = {"accepted": 0, "rejected": 0, "completed": 0}
        for doc in cursor:
            events.append(_clean_doc(doc))
            et = doc.get("event_type")
            if et in counts:
                counts[et] += 1
        return {
            "user_id": user_id,
            "counts": counts,
            "events": events
        }


class JobRepository:
    def __init__(self, db_instance=None):
        self._db = db_instance

    def _get_db(self):
        return _ensure_connected(self._db)

    def create_job(self, job_data: Union[dict, JobRecord]) -> Dict[str, Any]:
        database = self._get_db()
        if isinstance(job_data, JobRecord):
            data = job_data.dict() if hasattr(job_data, "dict") else job_data.model_dump()
        else:
            data = dict(job_data)

        if not data.get("job_id"):
            raise InvalidDataError("job_id is required")

        database.jobs.insert_one(data)
        return _clean_doc(database.jobs.find_one({"job_id": data["job_id"]}))

    def update_job(self, job_id: str, updates: dict) -> Optional[Dict[str, Any]]:
        database = self._get_db()
        clean = {k: v for k, v in updates.items() if k != "_id"}
        database.jobs.update_one({"job_id": job_id}, {"$set": clean})
        return _clean_doc(database.jobs.find_one({"job_id": job_id}))

    def get_job(self, job_id: str) -> Optional[Dict[str, Any]]:
        database = self._get_db()
        return _clean_doc(database.jobs.find_one({"job_id": job_id}))

    def get_latest_job(self, job_type: Optional[str] = None) -> Optional[Dict[str, Any]]:
        database = self._get_db()
        filter_q = {"job_type": job_type} if job_type else {}
        doc = database.jobs.find_one(filter_q, sort=[("start_time", -1)])
        return _clean_doc(doc)

    def acquire_job_lock(self, job_type: str, timeout_seconds: int = 1800, metadata: Optional[dict] = None) -> Optional[str]:
        """
        Concurrency lock: Prevents overlapping job executions from corrupting data.
        Returns job_id if lock acquired, or None if another job of same type is currently running.
        Times out stale locks older than timeout_seconds.
        """
        database = self._get_db()
        now_dt = datetime.now(timezone.utc)
        timeout_cutoff = (now_dt - timedelta(seconds=timeout_seconds)).isoformat()

        # Check for active in-progress job
        running_job = database.jobs.find_one({
            "job_type": job_type,
            "status": "in_progress"
        })

        if running_job:
            start_iso = running_job.get("start_time", "")
            if start_iso > timeout_cutoff:
                # Active non-timed-out job is running
                logger.warning("Job '%s' cannot start; lock held by job %s", job_type, running_job.get("job_id"))
                return None
            else:
                # Timed out, supersede it
                logger.warning("Superseding timed out job %s for '%s'", running_job.get("job_id"), job_type)
                database.jobs.update_one(
                    {"job_id": running_job.get("job_id")},
                    {"$set": {"status": "timed_out", "finish_time": now_dt.isoformat()}}
                )

        import uuid
        job_id = f"{job_type}_{uuid.uuid4().hex[:10]}"
        now_iso = now_dt.isoformat()
        job_doc = {
            "job_id": job_id,
            "job_type": job_type,
            "start_time": now_iso,
            "finish_time": None,
            "status": "in_progress",
            "checkpoints": {},
            "queries_executed": [],
            "issues_discovered": 0,
            "issues_inserted": 0,
            "issues_updated": 0,
            "issues_skipped": 0,
            "issues_marked_stale": 0,
            "issues_marked_closed": 0,
            "errors": [],
            "rate_limit_info": None,
            "execution_metadata": metadata or {}
        }
        database.jobs.insert_one(job_doc)
        return job_id

    def release_job_lock(self, job_id: str, status: str = "completed", updates: Optional[dict] = None) -> Optional[Dict[str, Any]]:
        """Release job lock by setting status and finish time with execution metrics."""
        database = self._get_db()
        now_iso = datetime.now(timezone.utc).isoformat()
        clean_updates = {k: v for k, v in (updates or {}).items() if k != "_id"}
        clean_updates["status"] = status
        clean_updates["finish_time"] = now_iso
        database.jobs.update_one({"job_id": job_id}, {"$set": clean_updates})
        return _clean_doc(database.jobs.find_one({"job_id": job_id}))

    def update_checkpoint(self, job_id: str, checkpoint: Dict[str, Any]) -> bool:
        """Persist checkpoint data to allow resumption of interrupted jobs."""
        database = self._get_db()
        now_iso = datetime.now(timezone.utc).isoformat()
        res = database.jobs.update_one(
            {"job_id": job_id},
            {
                "$set": {
                    "checkpoints": checkpoint,
                    "execution_metadata.last_checkpoint_at": now_iso
                }
            }
        )
        return res.modified_count > 0

    def get_latest_checkpoint(self, job_type: str) -> Optional[Dict[str, Any]]:
        """Retrieve checkpoint from the most recent job to support resume operations."""
        database = self._get_db()
        doc = database.jobs.find_one(
            {"job_type": job_type, "checkpoints": {"$exists": True, "$ne": {}}},
            sort=[("start_time", -1)]
        )
        if doc and "checkpoints" in doc:
            return doc["checkpoints"]
        return None

    def list_jobs(self, limit: int = 20, skip: int = 0, job_type: Optional[str] = None) -> List[Dict[str, Any]]:
        """List ingestion jobs in reverse chronological order."""
        database = self._get_db()
        filter_q = {"job_type": job_type} if job_type else {}
        cursor = database.jobs.find(filter_q).sort("start_time", -1).skip(skip).limit(limit)
        return [_clean_doc(d) for d in cursor]


# Singletons configured with default active db instance
issue_repo = IssueRepository()
user_repo = UserRepository()
rec_repo = RecommendationRepository()
feedback_repo = FeedbackRepository()
job_repo = JobRepository()
