#pragma once

#include "types.hpp"
#include "ranking_engine.hpp"
#include <string>
#include <vector>

namespace issue_pilot {

class JsonAdapter {
public:
    // Parse input payload containing profile, weights (optional), and candidate issues
    static bool parse_request(const std::string& json_str,
                              ContributorProfile& out_profile,
                              RankingWeights& out_weights,
                              std::vector<IssueRecord>& out_candidates,
                              std::string& out_error);

    // Serialize ranking results to JSON
    static std::string serialize_results(const std::vector<RankingResult>& results,
                                         const RankingWeights& weights,
                                         const std::string& model_version);

    // Serialize what-if experiments output to JSON
    static std::string serialize_experiments_json();
};

} // namespace issue_pilot
