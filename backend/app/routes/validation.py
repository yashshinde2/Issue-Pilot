import os
import subprocess
from datetime import datetime, timezone
from fastapi import APIRouter, HTTPException
from app.cpp_engine_client import cpp_client
from app.config import settings

router = APIRouter(prefix="/api/validation", tags=["Validation & Evaluation"])

@router.post("/run")
def run_validation_suite():
    """
    Executes:
    1. C++ Unit Tests (test_ranking.exe)
    2. C++ What-If Experiments (1 to 7)
    3. C++ Recommendation Quality Evaluation (NDCG@5, Precision@5)
    Returns detailed audit report.
    """
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    test_exe = os.path.join(base_dir, "ranking_engine", "build", "test_ranking.exe")

    unit_tests_output = ""
    unit_tests_pass = False

    if os.path.exists(test_exe):
        res = subprocess.run([test_exe], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
        unit_tests_output = res.stdout
        unit_tests_pass = (res.returncode == 0)
    else:
        unit_tests_output = f"Executable not found at {test_exe}"

    # Experiments
    try:
        experiments_data = cpp_client.execute_experiments()
    except Exception as e:
        experiments_data = {"error": str(e), "experiments": []}

    # Evaluation metrics
    try:
        eval_data = cpp_client.execute_eval()
    except Exception as e:
        eval_data = {"error": str(e)}

    return {
        "status": "success" if unit_tests_pass else "failure",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "unit_tests": {
            "passed": unit_tests_pass,
            "raw_output": unit_tests_output
        },
        "experiments": experiments_data.get("experiments", []),
        "evaluation_metrics": eval_data
    }
