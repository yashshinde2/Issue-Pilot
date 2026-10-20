import logging
from typing import Optional
from pymongo import MongoClient, ASCENDING, DESCENDING
from pymongo.errors import ConnectionFailure, ServerSelectionTimeoutError, PyMongoError
from app.config import settings, sanitize_uri

logger = logging.getLogger(__name__)

class Database:
    def __init__(self, uri: Optional[str] = None, database_name: Optional[str] = None):
        self.client: Optional[MongoClient] = None
        self.db = None
        self._uri: str = uri or settings.mongodb_uri
        self._database_name: str = database_name or settings.mongodb_database
        self._explicitly_disconnected: bool = False

    def connect(self, uri: Optional[str] = None, database_name: Optional[str] = None) -> bool:
        """
        Connect to MongoDB Atlas using connection pooling and timeouts.
        Safe error handling masks any credentials in logs.
        """
        self._explicitly_disconnected = False
        if uri:
            self._uri = uri
        if database_name:
            self._database_name = database_name

        target_uri = self._uri
        target_db = self._database_name

        sanitized = sanitize_uri(target_uri)
        try:
            logger.info("Connecting to MongoDB Atlas at %s (db: %s)...", sanitized, target_db)
            self.client = MongoClient(
                target_uri,
                maxPoolSize=settings.mongodb_max_pool_size,
                minPoolSize=settings.mongodb_min_pool_size,
                serverSelectionTimeoutMS=settings.mongodb_server_selection_timeout_ms,
                connectTimeoutMS=settings.mongodb_connect_timeout_ms,
                socketTimeoutMS=settings.mongodb_socket_timeout_ms,
                maxIdleTimeMS=settings.mongodb_max_idle_time_ms,
                retryWrites=True,
                retryReads=True
            )
            # Verify connectivity via admin ping
            self.client.admin.command("ping")
            self.db = self.client[target_db]
            logger.info("Connected successfully to MongoDB Atlas database '%s'", target_db)
            self.init_indexes()
            return True
        except (ConnectionFailure, ServerSelectionTimeoutError) as e:
            logger.warning("MongoDB connection timeout/failure: %s", sanitize_uri(str(e)))
            self.client = None
            self.db = None
            return False
        except PyMongoError as e:
            logger.warning("MongoDB Atlas error: %s", sanitize_uri(str(e)))
            self.client = None
            self.db = None
            return False
        except Exception as e:
            logger.warning("Unexpected error connecting to MongoDB Atlas: %s", sanitize_uri(str(e)))
            self.client = None
            self.db = None
            return False

    def disconnect(self):
        """Close connection pool and release resources."""
        self._explicitly_disconnected = True
        if self.client:
            try:
                self.client.close()
            except Exception as e:
                logger.debug("Error closing MongoDB client: %s", str(e))
            finally:
                self.client = None
                self.db = None
                logger.info("MongoDB client disconnected.")

    def is_connected(self) -> bool:
        """Ping MongoDB to verify active connection."""
        if self.client is None or self.db is None:
            return False
        try:
            self.client.admin.command("ping")
            return True
        except Exception:
            return False

    def init_indexes(self, db_instance=None):
        """
        Create indexes for all 5 collections.
        Safe and idempotent to execute repeatedly.
        """
        target_db = db_instance if db_instance is not None else self.db
        if target_db is None:
            return False

        try:
            # -------------------------------------------------------------
            # Collection 1: issues
            # -------------------------------------------------------------
            target_db.issues.create_index(
                [
                    ("source", ASCENDING),
                    ("repository_full_name", ASCENDING),
                    ("issue_number", ASCENDING)
                ],
                unique=True,
                name="idx_source_repo_issue"
            )
            target_db.issues.create_index(
                [("id", ASCENDING)],
                unique=True,
                sparse=True,
                name="idx_issue_id"
            )
            target_db.issues.create_index(
                [("state", ASCENDING), ("is_assigned", ASCENDING)],
                name="idx_state_assigned"
            )
            target_db.issues.create_index(
                [("detected_technologies", ASCENDING)],
                name="idx_detected_technologies"
            )
            target_db.issues.create_index(
                [("updated_at", DESCENDING)],
                name="idx_updated_at"
            )
            target_db.issues.create_index(
                [("stale_status", ASCENDING)],
                name="idx_stale_status"
            )

            # -------------------------------------------------------------
            # Collection 2: users
            # -------------------------------------------------------------
            target_db.users.create_index(
                [("user_id", ASCENDING)],
                unique=True,
                name="idx_user_id"
            )
            target_db.users.create_index(
                [("updated_at", DESCENDING)],
                name="idx_user_updated_at"
            )

            # -------------------------------------------------------------
            # Collection 3: recommendations
            # -------------------------------------------------------------
            target_db.recommendations.create_index(
                [("user_id", ASCENDING), ("generated_at", DESCENDING)],
                name="idx_user_generated"
            )
            target_db.recommendations.create_index(
                [("issue_id", ASCENDING)],
                name="idx_rec_issue_id"
            )
            target_db.recommendations.create_index(
                [("generated_at", DESCENDING)],
                name="idx_generated_at"
            )
            target_db.recommendations.create_index(
                [("run_id", ASCENDING), ("issue_id", ASCENDING)],
                name="idx_rec_run_issue"
            )

            # -------------------------------------------------------------
            # Collection 4: feedback
            # -------------------------------------------------------------
            target_db.feedback.create_index(
                [("user_id", ASCENDING), ("event_timestamp", DESCENDING)],
                name="idx_feedback_user"
            )
            target_db.feedback.create_index(
                [("issue_id", ASCENDING)],
                name="idx_feedback_issue"
            )
            target_db.feedback.create_index(
                [("event_type", ASCENDING)],
                name="idx_feedback_event_type"
            )

            # -------------------------------------------------------------
            # Collection 5: jobs
            # -------------------------------------------------------------
            target_db.jobs.create_index(
                [("job_id", ASCENDING)],
                unique=True,
                name="idx_job_id"
            )
            target_db.jobs.create_index(
                [("start_time", DESCENDING)],
                name="idx_job_start"
            )
            target_db.jobs.create_index(
                [("status", ASCENDING)],
                name="idx_job_status"
            )

            logger.info("MongoDB Atlas indexes for all 5 collections verified and ready.")
            return True
        except PyMongoError as e:
            logger.error("Failed to initialize indexes: %s", sanitize_uri(str(e)))
            return False

db = Database()
