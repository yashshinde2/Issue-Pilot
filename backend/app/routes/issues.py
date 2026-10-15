import logging
from typing import List, Optional
from fastapi import APIRouter, HTTPException, Query, status

from app.models import IssueRecord
from app.crud import (
    issue_repo,
    DuplicateRecordError,
    RecordNotFoundError,
    DatabaseConnectionError,
    InvalidDataError
)
from app.database import db

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/issues", tags=["Issues Candidate Pool"])

@router.post("", status_code=status.HTTP_200_OK)
def upsert_issue_record(issue: IssueRecord):
    """
    Idempotently upsert an issue candidate into MongoDB Atlas.
    Updates existing document based on (source, repository_full_name, issue_number).
    Preserves existing fields not supplied in partial update.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    try:
        saved = issue_repo.upsert_issue(issue, preserve_unsupplied=True)
        return saved
    except InvalidDataError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))
    except DatabaseConnectionError as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
    except Exception as e:
        logger.error("Error upserting issue: %s", str(e))
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

@router.post("/batch-upsert", status_code=status.HTTP_200_OK)
def batch_upsert_issues(issues: List[IssueRecord]):
    """
    Batch upsert multiple issue candidates idempotently.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    results = []
    for issue in issues:
        try:
            saved = issue_repo.upsert_issue(issue, preserve_unsupplied=True)
            results.append(saved)
        except Exception as e:
            logger.warning("Failed to upsert issue %s: %s", issue.id, str(e))

    return {
        "status": "success",
        "total_submitted": len(issues),
        "total_upserted": len(results)
    }

@router.get("")
def query_issues(
    state: Optional[str] = Query("open", description="Issue state: 'open', 'closed', or 'all'"),
    is_assigned: Optional[bool] = Query(False, description="Filter by assignment status"),
    technology: Optional[str] = Query(None, description="Filter by detected technology"),
    min_difficulty: Optional[float] = Query(None, ge=0, le=100, description="Minimum technical complexity"),
    max_difficulty: Optional[float] = Query(None, ge=0, le=100, description="Maximum technical complexity"),
    search: Optional[str] = Query(None, description="Search term in title or repo"),
    limit: int = Query(50, ge=1, le=500),
    skip: int = Query(0, ge=0)
):
    """
    Retrieve candidate issues from MongoDB Atlas with eligibility and tech filters.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    try:
        issues = issue_repo.query_issues(
            state=state,
            is_assigned=is_assigned,
            technology=technology,
            min_difficulty=min_difficulty,
            max_difficulty=max_difficulty,
            search=search,
            limit=limit,
            skip=skip
        )
        total_count = issue_repo.count_issues()
        return {
            "count": len(issues),
            "total_in_pool": total_count,
            "filters": {
                "state": state,
                "is_assigned": is_assigned,
                "technology": technology,
                "min_difficulty": min_difficulty,
                "max_difficulty": max_difficulty,
                "search": search
            },
            "issues": issues
        }
    except DatabaseConnectionError as e:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(e))
    except Exception as e:
        logger.error("Error querying issues: %s", str(e))
        raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=str(e))

@router.get("/{issue_id}")
def get_issue_by_id(issue_id: str):
    """
    Get a single issue candidate by id.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    issue = issue_repo.get_issue_by_id(issue_id)
    if not issue:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Issue '{issue_id}' not found.")
    return issue

@router.delete("/{issue_id}")
def delete_issue_by_id(issue_id: str):
    """
    Delete an issue candidate by id.
    """
    if not db.is_connected():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="MongoDB Atlas connection is unavailable."
        )

    deleted = issue_repo.delete_issue(issue_id)
    if not deleted:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"Issue '{issue_id}' not found.")
    return {"status": "success", "message": f"Issue '{issue_id}' deleted."}
