import logging
from pymongo import MongoClient, ASCENDING, DESCENDING
from pymongo.errors import ConnectionFailure
from app.config import settings

logger = logging.getLogger(__name__)

class Database:
    def __init__(self):
        self.client = None
        self.db = None

    def connect(self):
        try:
            self.client = MongoClient(settings.mongodb_uri, serverSelectionTimeoutMS=5000)
            # Trigger connection check
            self.client.admin.command('ping')
            self.db = self.client[settings.mongodb_database]
            logger.info("Connected successfully to MongoDB Atlas database '%s'", settings.mongodb_database)
            self._init_indexes()
            return True
        except Exception as e:
            logger.warning("MongoDB Atlas connection could not be established: %s", str(e))
            self.db = None
            return False

    def _init_indexes(self):
        if self.db is None:
            return
        try:
            # Collection 1: issues
            # Unique compound index on source, repository_full_name, issue_number
            self.db.issues.create_index(
                [("source", ASCENDING), ("repository_full_name", ASCENDING), ("issue_number", ASCENDING)],
                unique=True,
                name="idx_source_repo_issue"
            )
            self.db.issues.create_index([("state", ASCENDING)], name="idx_state")
            self.db.issues.create_index([("updated_at", DESCENDING)], name="idx_updated_at")
            self.db.issues.create_index([("detected_technologies", ASCENDING)], name="idx_detected_technologies")
            self.db.issues.create_index([("stale_status", ASCENDING)], name="idx_stale_status")

            # Collection 2: users
            self.db.users.create_index([("user_id", ASCENDING)], unique=True, name="idx_user_id")

            # Collection 3: recommendations
            self.db.recommendations.create_index([("user_id", ASCENDING), ("generated_at", DESCENDING)], name="idx_user_generated")
            self.db.recommendations.create_index([("issue_id", ASCENDING)], name="idx_issue_id")

            # Collection 4: feedback
            self.db.feedback.create_index([("user_id", ASCENDING), ("event_timestamp", DESCENDING)], name="idx_feedback_user")
            self.db.feedback.create_index([("issue_id", ASCENDING)], name="idx_feedback_issue")
            self.db.feedback.create_index([("event_type", ASCENDING)], name="idx_feedback_event_type")

            # Collection 5: jobs
            self.db.jobs.create_index([("job_id", ASCENDING)], unique=True, name="idx_job_id")
            self.db.jobs.create_index([("start_time", DESCENDING)], name="idx_job_start")

            logger.info("MongoDB Atlas indexes initialized successfully.")
        except Exception as e:
            logger.error("Failed to initialize indexes: %s", str(e))

    def is_connected(self) -> bool:
        if not self.client:
            return False
        try:
            self.client.admin.command('ping')
            return True
        except Exception:
            return False

db = Database()
