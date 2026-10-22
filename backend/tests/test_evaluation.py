"""
Automated unit and integration tests for Recommendation-Quality Evaluation.
Validates:
1. Precision@5, NDCG@5, and Recall@5 metric calculations.
2. Ground-truth evaluation dataset loading and integrity.
3. Macro-averaged comparison between Issue Pilot and Baseline (Tech-Match Only).
4. Prevention of data leakage (future feedback / contribution isolation).
5. API endpoint GET /api/validation/evaluation-report.
"""
import pytest
from fastapi.testclient import TestClient
from app.main import app
from app.evaluation import (
    compute_dcg,
    compute_ndcg,
    compute_precision_at_k,
    compute_recall_at_k,
    run_evaluation,
    format_evaluation_report_markdown
)

client = TestClient(app)


def test_ndcg_and_precision_math():
    """Verify DCG, IDCG, NDCG, and Precision math with known theoretical values."""
    # Perfect ranking: [3, 2, 1, 0, 0]
    perfect_rel = [3.0, 2.0, 1.0, 0.0, 0.0]
    ndcg_perfect = compute_ndcg(perfect_rel, perfect_rel, k=5)
    assert abs(ndcg_perfect - 1.0) < 0.0001

    # Imperfect ranking: [0, 1, 2, 3, 0] vs ideal [3, 2, 1, 0, 0]
    imperfect_rel = [0.0, 1.0, 2.0, 3.0, 0.0]
    ndcg_imperfect = compute_ndcg(imperfect_rel, perfect_rel, k=5)
    assert 0.0 < ndcg_imperfect < 1.0

    # Precision@5 with threshold 2.0
    # In [3, 2, 1, 0, 0], two items are >= 2.0 -> P@5 = 2/5 = 0.40
    p5 = compute_precision_at_k(perfect_rel, threshold=2.0, k=5)
    assert abs(p5 - 0.40) < 0.0001

    # Recall@5 where total pool has 2 relevant items
    r5 = compute_recall_at_k(perfect_rel, perfect_rel, threshold=2.0, k=5)
    assert abs(r5 - 1.0) < 0.0001


def test_evaluation_run_on_labeled_dataset():
    """Run full evaluation on ground-truth benchmark and assert valid metrics."""
    report = run_evaluation()
    assert report["total_queries"] >= 2
    assert report["total_candidate_pairs"] >= 10
    assert "label_definitions" in report

    # Verify data leakage audit
    assert report["data_leakage_audit"]["future_feedback_used"] is False
    assert report["data_leakage_audit"]["future_contributions_used"] is False

    macro = report["macro_metrics"]
    model = macro["issue_pilot_model"]
    baseline = macro["baseline_tech_match"]

    assert 0.0 <= model["mean_precision_at_5"] <= 1.0
    assert 0.0 <= model["mean_ndcg_at_5"] <= 1.0
    assert 0.0 <= model["mean_recall_at_5"] <= 1.0

    # Assert Issue Pilot outperforms or matches simple baseline
    assert model["mean_ndcg_at_5"] >= baseline["mean_ndcg_at_5"]


def test_evaluation_markdown_formatting():
    """Verify markdown output generates clean tables and summaries."""
    report = run_evaluation()
    md = format_evaluation_report_markdown(report)
    assert "# Issue Pilot" in md
    assert "NDCG@5" in md
    assert "Data Leakage Audit" in md
    assert "Known Limitations" in md


def test_evaluation_report_api_endpoint():
    """Verify GET /api/validation/evaluation-report returns valid JSON report."""
    res = client.get("/api/validation/evaluation-report")
    assert res.status_code == 200
    data = res.json()
    assert data["total_queries"] >= 2
    assert "macro_metrics" in data
    assert "query_details" in data
