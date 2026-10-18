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

app = FastAPI(
    title="Issue Pilot API",
    description="Backend orchestration for Issue Pilot: GitHub Ingestion + MongoDB Atlas + C++ Ranking Engine",
    version="1.1.0"
)

# Enable CORS for frontend integration
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

from app.ingestion.scheduler import scheduler_service

@app.on_event("startup")
def startup_event():
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

@app.on_event("shutdown")
def shutdown_event():
    logger.info("Shutting down Issue Pilot backend services...")
    scheduler_service.stop()
    db.disconnect()

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

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)
