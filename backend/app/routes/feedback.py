import logging
from datetime import datetime, timezone
from fastapi import APIRouter, HTTPException, status
from app.models import FeedbackEvent
from app.database import db
from app.crud import feedback_repo, DatabaseConnectionError, InvalidDataError

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/feedback", tags=["Feedback & Personalization"])

@router.post("")
def record_feedback(event: FeedbackEvent):
    """
    Record user interaction with recommendation:
    accepted, rejected, completed.
    Never infer that clicking means completion!
    """
    if event.event_type not in ("accepted", "rejected", "completed"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid event_type. Must be 'accepted', 'rejected', or 'completed'."
        )

    now_iso = event.event_timestamp or datetime.now(timezone.utc).isoformat()

    if not db.is_connected():
        db.connect()

    if db.is_connected():
        try:
            feedback_repo.record_feedback(event)
        except Exception as e:
            logger.warning("Could not persist feedback to database: %s", str(e))

    return {
        "status": "success",
        "message": f"Recorded feedback '{event.event_type}' for user '{event.user_id}' on issue '{event.issue_id}'",
        "timestamp": now_iso
    }

@router.get("/{user_id}")
def get_user_feedback(user_id: str, limit: int = 50):
    """
    Retrieve feedback history and action counts for a contributor.
    """
    if not db.is_connected():
        db.connect()

    if not db.is_connected():
        return {
            "user_id": user_id,
            "counts": {"accepted": 0, "rejected": 0, "completed": 0},
            "events": []
        }

    try:
        data = feedback_repo.get_user_feedback(user_id, limit=limit)
        return data
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))
