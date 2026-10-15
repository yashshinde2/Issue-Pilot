import logging
from typing import List, Optional
from fastapi import APIRouter, HTTPException, Query, status

from app.models import ContributorProfile
from app.crud import (
    user_repo,
    DuplicateRecordError,
    RecordNotFoundError,
    DatabaseConnectionError,
    InvalidDataError
)
from app.database import db

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/users", tags=["Users & Contributor Profiles"])

@router.post("", status_code=status.HTTP_201_CREATED)
def create_user_profile(profile: ContributorProfile):
    """
    Create a new persistent contributor profile in MongoDB Atlas.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    try:
        created = user_repo.create_user(profile)
        return created
    except DuplicateRecordError as e:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(e))
    except (InvalidDataError, ValueError) as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except DatabaseConnectionError as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
    except Exception as e:
        logger.error("Unexpected error creating user: %s", str(e))
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

@router.get("/{user_id}")
def get_user_profile(user_id: str):
    """
    Retrieve contributor profile by user_id from MongoDB Atlas.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    user = user_repo.get_user(user_id)
    if not user:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"User '{user_id}' not found.")
    return user

@router.put("/{user_id}")
def update_user_profile(user_id: str, updates: dict):
    """
    Update an existing contributor profile.
    Preserves existing fields not specified in updates.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    updated = user_repo.update_user(user_id, updates)
    if not updated:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"User '{user_id}' not found.")
    return updated

@router.post("/upsert")
def upsert_user_profile(profile: ContributorProfile):
    """
    Idempotent upsert of contributor profile in MongoDB Atlas.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    try:
        return user_repo.upsert_user(profile)
    except Exception as e:
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

@router.get("")
def list_user_profiles(limit: int = Query(50, ge=1, le=200), skip: int = Query(0, ge=0)):
    """
    List contributor profiles with pagination.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    users = user_repo.list_users(limit=limit, skip=skip)
    return {"users": users, "count": len(users)}

@router.delete("/{user_id}")
def delete_user_profile(user_id: str):
    """
    Delete contributor profile by user_id.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    deleted = user_repo.delete_user(user_id)
    if not deleted:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"User '{user_id}' not found.")
    return {"status": "success", "message": f"User '{user_id}' deleted."}
