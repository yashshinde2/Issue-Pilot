#pragma once

#include "types.hpp"
#include <vector>
#include <cmath>
#include <algorithm>

namespace issue_pilot {

struct EvaluationItem {
    std::string issue_id;
    int rank{0};
    double predicted_score{0.0};
    double ground_truth_relevance{0.0}; // e.g. 0 to 3 scale (0: irrelevant, 1: weak, 2: relevant, 3: highly relevant)
    bool is_relevant{false};             // binary relevant flag (e.g. relevance >= 2)
};

struct EvaluationResult {
    double precision_at_5{0.0};
    double recall_at_5{0.0};
    double dcg_at_5{0.0};
    double idcg_at_5{0.0};
    double ndcg_at_5{0.0};
    int total_evaluated{0};
    int total_relevant{0};
    int top5_count{0};
};

class Evaluator {
public:
    static EvaluationResult evaluate(const std::vector<EvaluationItem>& items, int k = 5);
};

} // namespace issue_pilot
