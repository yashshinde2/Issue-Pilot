from datetime import datetime, timezone
from fastapi import APIRouter, HTTPException
from typing import List, Dict, Any
from app.models import FeedbackEvent
from app.database import db

router = APIRouter(prefix="/api/feedback", tags=["Feedback & Personalization"])

@router.post("")
def record_feedback(event: FeedbackEvent):
    """
    Record user interaction with recommendation:
    accepted, rejected, completed.
    Never infer that clicking means completion!
    """
    if event.event_type not in ("accepted", "rejected", "completed"):
        raise HTTPException(status_code=400, detail="Invalid event_type. Must be 'accepted', 'rejected', or 'completed'.")

    now_iso = datetime.now(timezone.utc).isoformat()
    record = {
        "user_id": event.user_id,
        "issue_id": event.issue_id,
        "event_type": event.event_type,
        "event_timestamp": event.event_timestamp or now_iso,
        "recommendation_snapshot": event.recommendation_snapshot
    }

    if db.db is not None:
        try:
            db.db.feedback.insert_one(record)

            # Update aggregated stats on user document
            inc_field = f"{event.event_type}_count"
            db.db.users.update_one(
                {"user_id": event.user_id},
                {
                    "$inc": {inc_field: 1},
                    "$set": {"last_active_at": now_iso, "has_feedback_history": True}
                },
                upsert=True
            )
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"Database error: {str(e)}")

    return {
        "status": "success",
        "message": f"Recorded feedback '{event.event_type}' for user '{event.user_id}' on issue '{event.issue_id}'",
        "timestamp": now_iso
    }

@router.get("/{user_id}")
def get_user_feedback(user_id: str):
    if db.db is None:
        return {"feedback_events": [], "counts": {"accepted": 0, "rejected": 0, "completed": 0}}

    try:
        cursor = db.db.feedback.find({"user_id": user_id}).sort("event_timestamp", -1).limit(50)
        events = []
        counts = {"accepted": 0, "rejected": 0, "completed": 0}
        for doc in cursor:
            doc["_id"] = str(doc["_id"])
            events.append(doc)
            et = doc.get("event_type")
            if et in counts:
                counts[et] += 1

        return {
            "user_id": user_id,
            "counts": counts,
            "events": events
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
