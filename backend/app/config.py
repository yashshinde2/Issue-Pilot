import os
import re
from pydantic import BaseModel
from dotenv import load_dotenv

# Load .env file and atlas-credentials.env from current dir or project root
root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
load_dotenv(os.path.join(root_dir, ".env"))
load_dotenv(".env")
if not os.getenv("MONGODB_URI"):
    load_dotenv(os.path.join(root_dir, "atlas-credentials.env"))
    load_dotenv("atlas-credentials.env")

def sanitize_uri(uri: str) -> str:
    """Mask credentials in MongoDB connection string for safe logging and error reporting."""
    if not uri:
        return ""
    return re.sub(r'://([^:]+):([^@]+)@', r'://\1:****@', uri)

class Settings(BaseModel):
    mongodb_uri: str = os.getenv("MONGODB_URI", "mongodb://localhost:27017")
    mongodb_database: str = os.getenv("MONGODB_DATABASE", "issue_pilot")
    mongodb_max_pool_size: int = int(os.getenv("MONGODB_MAX_POOL_SIZE", "50"))
    mongodb_min_pool_size: int = int(os.getenv("MONGODB_MIN_POOL_SIZE", "5"))
    mongodb_server_selection_timeout_ms: int = int(os.getenv("MONGODB_SERVER_SELECTION_TIMEOUT_MS", "5000"))
    mongodb_connect_timeout_ms: int = int(os.getenv("MONGODB_CONNECT_TIMEOUT_MS", "10000"))
    mongodb_socket_timeout_ms: int = int(os.getenv("MONGODB_SOCKET_TIMEOUT_MS", "10000"))
    mongodb_max_idle_time_ms: int = int(os.getenv("MONGODB_MAX_IDLE_TIME_MS", "45000"))
    github_token: str = os.getenv("GITHUB_TOKEN", "")
    github_api_base_url: str = os.getenv("GITHUB_API_BASE_URL", "https://api.github.com")
    ingestion_enabled: bool = os.getenv("INGESTION_ENABLED", "true").lower() in ("true", "1", "yes")
    candidate_pool_target: int = int(os.getenv("CANDIDATE_POOL_TARGET", "3000"))
    recent_window_days: int = int(os.getenv("RECENT_WINDOW_DAYS", "30"))
    older_window_days: int = int(os.getenv("OLDER_WINDOW_DAYS", "90"))
    final_recommendation_count: int = int(os.getenv("FINAL_RECOMMENDATION_COUNT", "5"))
    verification_candidate_limit: int = int(os.getenv("VERIFICATION_CANDIDATE_LIMIT", "30"))
    cpp_engine_path: str = os.getenv("CPP_ENGINE_PATH", "ranking_engine/build/issue_pilot_ranking_engine.exe")

    @property
    def sanitized_mongodb_uri(self) -> str:
        return sanitize_uri(self.mongodb_uri)

settings = Settings()

