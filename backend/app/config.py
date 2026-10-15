import os
from pydantic import BaseModel
from dotenv import load_dotenv

# Load .env file and atlas-credentials.env from current dir or project root
root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
load_dotenv(os.path.join(root_dir, ".env"))
load_dotenv(".env")
if not os.getenv("MONGODB_URI"):
    load_dotenv(os.path.join(root_dir, "atlas-credentials.env"))
    load_dotenv("atlas-credentials.env")

class Settings(BaseModel):
    mongodb_uri: str = os.getenv("MONGODB_URI", "mongodb://localhost:27017")
    mongodb_database: str = os.getenv("MONGODB_DATABASE", "issue_pilot")
    github_token: str = os.getenv("GITHUB_TOKEN", "")
    github_api_base_url: str = os.getenv("GITHUB_API_BASE_URL", "https://api.github.com")
    ingestion_enabled: bool = os.getenv("INGESTION_ENABLED", "true").lower() in ("true", "1", "yes")
    candidate_pool_target: int = int(os.getenv("CANDIDATE_POOL_TARGET", "3000"))
    recent_window_days: int = int(os.getenv("RECENT_WINDOW_DAYS", "30"))
    older_window_days: int = int(os.getenv("OLDER_WINDOW_DAYS", "90"))
    final_recommendation_count: int = int(os.getenv("FINAL_RECOMMENDATION_COUNT", "5"))
    verification_candidate_limit: int = int(os.getenv("VERIFICATION_CANDIDATE_LIMIT", "30"))
    cpp_engine_path: str = os.getenv("CPP_ENGINE_PATH", "ranking_engine/build/issue_pilot_ranking_engine.exe")

settings = Settings()
