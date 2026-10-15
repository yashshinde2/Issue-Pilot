#include "../include/evaluation.hpp"
#include <cmath>
#include <algorithm>

namespace issue_pilot {

EvaluationResult Evaluator::evaluate(const std::vector<EvaluationItem>& items, int k) {
    EvaluationResult res;
    res.total_evaluated = static_cast<int>(items.size());
    if (items.empty() || k <= 0) {
        return res;
    }

    // Sort items by predicted_score descending (model ranking)
    std::vector<EvaluationItem> model_sorted = items;
    std::sort(model_sorted.begin(), model_sorted.end(), [](const EvaluationItem& a, const EvaluationItem& b) {
        return a.predicted_score > b.predicted_score;
    });

    int limit = std::min(k, static_cast<int>(model_sorted.size()));
    res.top5_count = limit;

    int relevant_in_top_k = 0;
    double dcg = 0.0;

    for (int i = 0; i < limit; ++i) {
        if (model_sorted[i].is_relevant || model_sorted[i].ground_truth_relevance >= 2.0) {
            relevant_in_top_k++;
        }
        double rel = model_sorted[i].ground_truth_relevance;
        // Standard DCG formula: (2^rel - 1) / log2(rank + 1)
        double numerator = std::pow(2.0, rel) - 1.0;
        double denominator = std::log2(static_cast<double>(i + 2));
        dcg += (numerator / denominator);
    }

    res.precision_at_5 = (k > 0) ? (static_cast<double>(relevant_in_top_k) / static_cast<double>(k)) : 0.0;
    res.dcg_at_5 = dcg;

    // Count all relevant items overall
    int total_rel = 0;
    for (const auto& item : items) {
        if (item.is_relevant || item.ground_truth_relevance >= 2.0) {
            total_rel++;
        }
    }
    res.total_relevant = total_rel;
    res.recall_at_5 = (total_rel > 0) ? (static_cast<double>(relevant_in_top_k) / static_cast<double>(total_rel)) : 1.0;

    // Compute IDCG by sorting by ground_truth_relevance descending
    std::vector<EvaluationItem> ideal_sorted = items;
    std::sort(ideal_sorted.begin(), ideal_sorted.end(), [](const EvaluationItem& a, const EvaluationItem& b) {
        return a.ground_truth_relevance > b.ground_truth_relevance;
    });

    double idcg = 0.0;
    for (int i = 0; i < limit; ++i) {
        double rel = ideal_sorted[i].ground_truth_relevance;
        double numerator = std::pow(2.0, rel) - 1.0;
        double denominator = std::log2(static_cast<double>(i + 2));
        idcg += (numerator / denominator);
    }
    res.idcg_at_5 = idcg;

    if (idcg > 0.0) {
        res.ndcg_at_5 = dcg / idcg;
    } else {
        res.ndcg_at_5 = (dcg > 0.0) ? 1.0 : 0.0;
    }

    return res;
}

} // namespace issue_pilot
