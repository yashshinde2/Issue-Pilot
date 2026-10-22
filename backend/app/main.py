import logging
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.config import settings
from app.database import db
from app.cpp_engine_client import cpp_client
from app.routes.simulator import router as simulator_router
from app.routes.recommendations import router as recommendations_router
from app.routes.ingestion import router as ingestion_router
from app.routes.feedback import router as feedback_router
from app.routes.validation import router as validation_router
from app.routes.users import router as users_router
from app.routes.issues import router as issues_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("issue_pilot")

from contextlib import asynccontextmanager
from app.ingestion.scheduler import scheduler_service

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Initializing Issue Pilot backend services...")
    atlas_connected = db.connect()
    if atlas_connected:
        logger.info("MongoDB Atlas connected and verified on database '%s'.", settings.mongodb_database)
    else:
        logger.warning("MongoDB Atlas could not connect. Running in offline/sample mode.")
    try:
        ver = cpp_client.get_version()
        logger.info("C++ Ranking Engine loaded successfully (Algorithm Version: %s)", ver)
    except Exception as e:
        logger.error("Could not load C++ Ranking Engine: %s", str(e))

    if settings.scheduler_enabled:
        logger.info("Auto-starting background ingestion scheduler...")
        scheduler_service.start()

    yield

    logger.info("Shutting down Issue Pilot backend services...")
    scheduler_service.stop()
    db.disconnect()

app = FastAPI(
    title="Issue Pilot API",
    description="Backend orchestration for Issue Pilot: GitHub Ingestion + MongoDB Atlas + C++ Ranking Engine",
    version="1.1.0",
    lifespan=lifespan
)

# Enable CORS for frontend integration
cors_origins_list = [o.strip() for o in settings.cors_origins.split(",") if o.strip()] or ["*"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_origins_list,
    allow_credentials=False if cors_origins_list == ["*"] else True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/api/health", tags=["Health"])
def health_check():
    atlas_ok = db.is_connected()
    cpp_ver = "unknown"
    cpp_ok = False
    try:
        cpp_ver = cpp_client.get_version()
        cpp_ok = True
    except Exception:
        pass

    return {
        "status": "healthy" if (atlas_ok and cpp_ok) else "degraded",
        "mongodb_atlas_connected": atlas_ok,
        "database_name": settings.mongodb_database,
        "cpp_ranking_engine_available": cpp_ok,
        "algorithm_version": cpp_ver,
        "github_token_configured": bool(settings.github_token),
        "ingestion_enabled": settings.ingestion_enabled,
        "scheduler_active": scheduler_service.is_running
    }

# Mount sub-routers
app.include_router(simulator_router)
app.include_router(recommendations_router)
app.include_router(users_router)
app.include_router(issues_router)
app.include_router(ingestion_router)
app.include_router(feedback_router)
app.include_router(validation_router)

# Mount static demo UI dashboard
import os
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse

static_dir = os.path.join(os.path.dirname(__file__), "static")
if os.path.exists(static_dir):
    app.mount("/static", StaticFiles(directory=static_dir), name="static")


@app.get("/", include_in_schema=False)
@app.get("/demo", include_in_schema=False)
def serve_demo_dashboard():
    index_file = os.path.join(static_dir, "index.html")
    if os.path.exists(index_file):
        return FileResponse(index_file)
    return {"message": "Issue Pilot API is active. Visit /docs for OpenAPI documentation."}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
