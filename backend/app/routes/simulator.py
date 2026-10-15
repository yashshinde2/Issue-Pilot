from fastapi import APIRouter, HTTPException
from typing import Dict, Any, List
from app.models import SimulatorRequest, ContributorProfile, RankingWeights, IssueRecord
from app.cpp_engine_client import cpp_client

router = APIRouter(prefix="/api/simulator", tags=["Ranking Simulator"])

SAMPLE_PROFILES = [
    {
        "user_id": "contributor-fullstack",
        "capability": 76.0,
        "accepted_count": 12,
        "rejected_count": 8,
        "completed_count": 9,
        "similarity_to_history": 90.0,
        "has_feedback_history": True,
        "skills": {
            "Python": {"technology": "Python", "score": 90.0, "confidence": 0.95},
            "FastAPI": {"technology": "FastAPI", "score": 80.0, "confidence": 0.90},
            "MongoDB": {"technology": "MongoDB", "score": 70.0, "confidence": 0.85},
            "React": {"technology": "React", "score": 60.0, "confidence": 0.80},
            "TypeScript": {"technology": "TypeScript", "score": 65.0, "confidence": 0.80}
        }
    },
    {
        "user_id": "contributor-beginner-coldstart",
        "capability": 45.0,
        "accepted_count": 0,
        "rejected_count": 0,
        "completed_count": 0,
        "similarity_to_history": 0.0,
        "has_feedback_history": False,
        "skills": {
            "Python": {"technology": "Python", "score": 60.0, "confidence": 0.70},
            "Documentation": {"technology": "Documentation", "score": 75.0, "confidence": 0.80}
        }
    },
    {
        "user_id": "contributor-systems-cpp",
        "capability": 88.0,
        "accepted_count": 15,
        "rejected_count": 3,
        "completed_count": 14,
        "similarity_to_history": 85.0,
        "has_feedback_history": True,
        "skills": {
            "C++": {"technology": "C++", "score": 95.0, "confidence": 0.95},
            "CMake": {"technology": "CMake", "score": 85.0, "confidence": 0.90},
            "Rust": {"technology": "Rust", "score": 70.0, "confidence": 0.80}
        }
    }
]

SAMPLE_ISSUES = [
    {
        "id": "sample-1",
        "native_id": "1001",
        "repository_full_name": "tiangolo/fastapi",
        "issue_number": 4821,
        "title": "Fix MongoDB async connection pooling and reconnect timeouts in FastAPI backend",
        "body": "When high concurrency requests hit the async database handler, MongoDB connection pool limits can cause socket disconnects without automatic reconnect.",
        "issue_url": "https://github.com/tiangolo/fastapi/issues/4821",
        "labels": ["bug", "async", "database"],
        "state": "open",
        "is_assigned": False,
        "technologies": [
            {"technology": "FastAPI", "raw_relevance": 5.0},
            {"technology": "Python", "raw_relevance": 3.0},
            {"technology": "MongoDB", "raw_relevance": 2.0}
        ],
        "difficulty_evidence": {
            "scope_change_size": 70.0,
            "technical_complexity": 75.0,
            "historical_effort": 65.0,
            "dependency_arch_impact": 80.0,
            "coordination_complexity": 50.0
        },
        "responsiveness_evidence": {
            "available": True,
            "response_time_score": 90.0,
            "response_rate_score": 90.0,
            "pr_review_score": 85.0,
            "recent_maintainer_act": 90.0
        },
        "health_evidence": {
            "available": True,
            "recent_commits": 90.0,
            "pr_activity": 80.0,
            "issue_activity": 85.0,
            "active_contributors": 75.0,
            "recent_releases": 80.0
        },
        "freshness_evidence": {
            "available": True,
            "age_score": 80.0,
            "activity_score": 100.0
        }
    },
    {
        "id": "sample-2",
        "native_id": "1002",
        "repository_full_name": "facebook/react",
        "issue_number": 29810,
        "title": "TypeScript type definition error for useEffect cleanup return signature",
        "body": "Type definition for useEffect returns undefined instead of Destructor function under strict null check flags.",
        "issue_url": "https://github.com/facebook/react/issues/29810",
        "labels": ["typescript", "typing", "good-first-issue"],
        "state": "open",
        "is_assigned": False,
        "technologies": [
            {"technology": "TypeScript", "raw_relevance": 5.0},
            {"technology": "React", "raw_relevance": 4.0}
        ],
        "difficulty_evidence": {
            "scope_change_size": 35.0,
            "technical_complexity": 45.0,
            "historical_effort": 40.0,
            "dependency_arch_impact": 50.0,
            "coordination_complexity": 30.0
        },
        "responsiveness_evidence": {
            "available": True,
            "response_time_score": 85.0,
            "response_rate_score": 88.0,
            "pr_review_score": 90.0,
            "recent_maintainer_act": 88.0
        },
        "health_evidence": {
            "available": True,
            "recent_commits": 95.0,
            "pr_activity": 90.0,
            "issue_activity": 92.0,
            "active_contributors": 95.0,
            "recent_releases": 85.0
        },
        "freshness_evidence": {
            "available": True,
            "age_score": 90.0,
            "activity_score": 90.0
        }
    },
    {
        "id": "sample-3",
        "native_id": "1003",
        "repository_full_name": "pallets/flask",
        "issue_number": 5120,
        "title": "Refactor blueprint routing dispatcher to support regex URL parameters",
        "body": "Allow custom converters or regex patterns inside url_prefix to support dynamic subdomain multi-tenant routing.",
        "issue_url": "https://github.com/pallets/flask/issues/5120",
        "labels": ["enhancement", "routing", "core"],
        "state": "open",
        "is_assigned": False,
        "technologies": [
            {"technology": "Python", "raw_relevance": 5.0}
        ],
        "difficulty_evidence": {
            "scope_change_size": 75.0,
            "technical_complexity": 80.0,
            "historical_effort": 70.0,
            "dependency_arch_impact": 85.0,
            "coordination_complexity": 60.0
        },
        "responsiveness_evidence": {
            "available": True,
            "response_time_score": 75.0,
            "response_rate_score": 80.0,
            "pr_review_score": 75.0,
            "recent_maintainer_act": 80.0
        },
        "health_evidence": {
            "available": True,
            "recent_commits": 80.0,
            "pr_activity": 75.0,
            "issue_activity": 78.0,
            "active_contributors": 70.0,
            "recent_releases": 75.0
        },
        "freshness_evidence": {
            "available": True,
            "age_score": 65.0,
            "activity_score": 60.0
        }
    },
    {
        "id": "sample-4",
        "native_id": "1004",
        "repository_full_name": "llvm/llvm-project",
        "issue_number": 84129,
        "title": "Add CMake module support for MinGW GCC 6.3 C++14 abi compatibility",
        "body": "Fix linking issues with std::string copy-on-write ABI under older mingw runtime configurations.",
        "issue_url": "https://github.com/llvm/llvm-project/issues/84129",
        "labels": ["cmake", "build", "mingw"],
        "state": "open",
        "is_assigned": False,
        "technologies": [
            {"technology": "C++", "raw_relevance": 5.0},
            {"technology": "CMake", "raw_relevance": 4.0}
        ],
        "difficulty_evidence": {
            "scope_change_size": 80.0,
            "technical_complexity": 85.0,
            "historical_effort": 80.0,
            "dependency_arch_impact": 90.0,
            "coordination_complexity": 75.0
        },
        "responsiveness_evidence": {
            "available": True,
            "response_time_score": 80.0,
            "response_rate_score": 85.0,
            "pr_review_score": 80.0,
            "recent_maintainer_act": 85.0
        },
        "health_evidence": {
            "available": True,
            "recent_commits": 98.0,
            "pr_activity": 95.0,
            "issue_activity": 90.0,
            "active_contributors": 95.0,
            "recent_releases": 90.0
        },
        "freshness_evidence": {
            "available": True,
            "age_score": 90.0,
            "activity_score": 90.0
        }
    },
    {
        "id": "sample-5",
        "native_id": "1005",
        "repository_full_name": "psf/requests",
        "issue_number": 6390,
        "title": "Clarify proxy authentication header documentation and SSL adapter examples",
        "body": "The tutorial documentation lacks examples showing how to pass custom SSLContext to HTTPAdapter when authenticating through corporate proxy.",
        "issue_url": "https://github.com/psf/requests/issues/6390",
        "labels": ["docs", "good first issue"],
        "state": "open",
        "is_assigned": False,
        "technologies": [
            {"technology": "Python", "raw_relevance": 4.0},
            {"technology": "Documentation", "raw_relevance": 5.0}
        ],
        "difficulty_evidence": {
            "scope_change_size": 25.0,
            "technical_complexity": 30.0,
            "historical_effort": 35.0,
            "dependency_arch_impact": 20.0,
            "coordination_complexity": 25.0
        },
        "responsiveness_evidence": {
            "available": True,
            "response_time_score": 85.0,
            "response_rate_score": 88.0,
            "pr_review_score": 85.0,
            "recent_maintainer_act": 80.0
        },
        "health_evidence": {
            "available": True,
            "recent_commits": 75.0,
            "pr_activity": 70.0,
            "issue_activity": 80.0,
            "active_contributors": 70.0,
            "recent_releases": 75.0
        },
        "freshness_evidence": {
            "available": True,
            "age_score": 50.0,
            "activity_score": 40.0
        }
    }
]

@router.get("/sample-data")
def get_sample_data():
    return {
        "profiles": SAMPLE_PROFILES,
        "issues": SAMPLE_ISSUES,
        "default_weights": RankingWeights().dict()
    }

@router.post("/rank")
def rank_simulator(req: SimulatorRequest):
    try:
        # Use provided candidates or fall back to default samples
        candidates = req.candidates if req.candidates else [IssueRecord(**i) for i in SAMPLE_ISSUES]
        payload = {
            "profile": req.profile.dict(),
            "weights": req.weights.dict() if req.weights else RankingWeights().dict(),
            "candidates": [c.dict() for c in candidates]
        }
        # Execute authoritative C++ ranking engine
        result = cpp_client.execute_ranking(payload)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Ranking engine error: {str(e)}")

@router.get("/experiments")
def get_what_if_experiments():
    try:
        # Execute actual C++ engine What-If experiments
        experiments_data = cpp_client.execute_experiments()
        return experiments_data
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Experiments evaluation failed: {str(e)}")
