#include "../include/vote_affinity.hpp"
#include <cctype>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <set>
#include <map>

namespace issue_pilot {

std::string VoteAffinityEngine::normalize_token(const std::string& token) {
    if (token.empty()) return "";
    size_t start = 0;
    while (start < token.size() && std::isspace(static_cast<unsigned char>(token[start]))) {
        start++;
    }
    size_t end = token.size();
    while (end > start && std::isspace(static_cast<unsigned char>(token[end - 1]))) {
        end--;
    }
    std::string result;
    result.reserve(end - start);
    for (size_t i = start; i < end; ++i) {
        result.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(token[i]))));
    }
    return result;
}

std::vector<std::string> VoteAffinityEngine::normalize_tokens(const std::vector<std::string>& tokens) {
    std::set<std::string> unique_tokens;
    for (const auto& t : tokens) {
        std::string norm = normalize_token(t);
        if (!norm.empty()) {
            unique_tokens.insert(norm);
        }
    }
    return std::vector<std::string>(unique_tokens.begin(), unique_tokens.end());
}

double VoteAffinityEngine::jaccard_similarity(const std::vector<std::string>& a, const std::vector<std::string>& b) {
    auto norm_a = normalize_tokens(a);
    auto norm_b = normalize_tokens(b);

    if (norm_a.empty() || norm_b.empty()) {
        return -1.0; // Unavailable
    }

    std::set<std::string> set_a(norm_a.begin(), norm_a.end());
    std::set<std::string> set_b(norm_b.begin(), norm_b.end());

    size_t intersection_count = 0;
    for (const auto& item : set_a) {
        if (set_b.find(item) != set_b.end()) {
            intersection_count++;
        }
    }

    std::set<std::string> union_set = set_a;
    union_set.insert(set_b.begin(), set_b.end());

    if (union_set.empty()) {
        return -1.0;
    }

    return static_cast<double>(intersection_count) / static_cast<double>(union_set.size());
}

double VoteAffinityEngine::categorical_similarity(const std::string& a, const std::string& b) {
    std::string norm_a = normalize_token(a);
    std::string norm_b = normalize_token(b);

    if (norm_a.empty() || norm_b.empty()) {
        return -1.0; // Unavailable
    }

    return (norm_a == norm_b) ? 1.0 : 0.0;
}

double VoteAffinityEngine::difficulty_similarity(double diff_a, double diff_b) {
    if (diff_a < 0.0 || diff_a > 100.0 || diff_b < 0.0 || diff_b > 100.0) {
        return -1.0; // Unavailable
    }
    double diff = std::abs(diff_a - diff_b);
    return 1.0 - (diff / 100.0);
}

double VoteAffinityEngine::compute_similarity(
    const FeatureProfile& candidate,
    const FeatureProfile& voted,
    std::vector<std::string>* out_matched_groups
) const {
    double total_available_weight = 0.0;
    double weighted_sum = 0.0;

    // 1. Technologies and frameworks (weight: 0.30)
    double tech_sim = jaccard_similarity(candidate.technologies, voted.technologies);
    if (tech_sim >= 0.0) {
        total_available_weight += weights_.technology_weight;
        weighted_sum += weights_.technology_weight * tech_sim;
        if (tech_sim > 0.0 && out_matched_groups) {
            out_matched_groups->push_back("technologies");
        }
    }

    // 2. Labels and issue category (weight: 0.30)
    double label_sim = jaccard_similarity(candidate.labels, voted.labels);
    if (label_sim >= 0.0) {
        total_available_weight += weights_.label_weight;
        weighted_sum += weights_.label_weight * label_sim;
        if (label_sim > 0.0 && out_matched_groups) {
            out_matched_groups->push_back("labels");
        }
    }

    // 3. Issue type (weight: 0.15)
    double type_sim = categorical_similarity(candidate.issue_type, voted.issue_type);
    if (type_sim >= 0.0) {
        total_available_weight += weights_.issue_type_weight;
        weighted_sum += weights_.issue_type_weight * type_sim;
        if (type_sim > 0.0 && out_matched_groups) {
            out_matched_groups->push_back("issue_type");
        }
    }

    // 4. Difficulty (weight: 0.10)
    if (candidate.difficulty_available && voted.difficulty_available) {
        double diff_sim = difficulty_similarity(candidate.difficulty, voted.difficulty);
        if (diff_sim >= 0.0) {
            total_available_weight += weights_.difficulty_weight;
            weighted_sum += weights_.difficulty_weight * diff_sim;
            if (diff_sim > 0.5 && out_matched_groups) {
                out_matched_groups->push_back("difficulty");
            }
        }
    }

    // 5. Other reliable structured metadata (weight: 0.10)
    double meta_sim = jaccard_similarity(candidate.structured_metadata, voted.structured_metadata);
    if (meta_sim >= 0.0) {
        total_available_weight += weights_.structured_metadata_weight;
        weighted_sum += weights_.structured_metadata_weight * meta_sim;
        if (meta_sim > 0.0 && out_matched_groups) {
            out_matched_groups->push_back("structured_metadata");
        }
    }

    // 6. Repository identity (weight: 0.05)
    std::string repo_c = normalize_token(candidate.repository_full_name);
    std::string repo_v = normalize_token(voted.repository_full_name);
    if (!repo_c.empty() && !repo_v.empty()) {
        double repo_sim = (repo_c == repo_v) ? 1.0 : 0.0;
        total_available_weight += weights_.repository_weight;
        weighted_sum += weights_.repository_weight * repo_sim;
        if (repo_sim > 0.0 && out_matched_groups) {
            out_matched_groups->push_back("repository");
        }
    }

    if (total_available_weight <= 0.0) {
        return 0.0;
    }

    return weighted_sum / total_available_weight;
}

std::vector<AffinityResult> VoteAffinityEngine::evaluate(
    const std::vector<CandidateIssue>& candidates,
    const std::vector<IssueVoteRecord>& raw_votes
) const {
    std::vector<AffinityResult> results;
    results.reserve(candidates.size());

    // Step 1: Filter and deduplicate votes by stable identity (source, platform_issue_id)
    // Preserves the latest vote for each issue and ignores empty/unverified identities.
    std::map<std::pair<std::string, std::string>, IssueVoteRecord> deduplicated_votes;
    for (const auto& v : raw_votes) {
        if (v.source.empty() || v.platform_issue_id.empty()) {
            continue; // Exclude unverified or empty identities
        }
        if (v.vote != 1 && v.vote != -1) {
            continue; // Exclude invalid vote values (or removed votes)
        }
        auto key = std::make_pair(v.source, v.platform_issue_id);
        // Map will overwrite or keep latest entry deterministically
        deduplicated_votes[key] = v;
    }

    // Separate into likes and dislikes
    std::vector<IssueVoteRecord> liked_votes;
    std::vector<IssueVoteRecord> disliked_votes;

    for (const auto& entry : deduplicated_votes) {
        if (entry.second.vote == 1) {
            liked_votes.push_back(entry.second);
        } else if (entry.second.vote == -1) {
            disliked_votes.push_back(entry.second);
        }
    }

    // Step 2: Compute effective weights for votes to handle correlated/near-duplicate evidence
    // If a user likes 10 near-identical issues, damp subsequent duplicates.
    auto compute_damping = [&](const std::vector<IssueVoteRecord>& votes) -> std::vector<double> {
        std::vector<double> eff_weights(votes.size(), 1.0);
        for (size_t i = 0; i < votes.size(); ++i) {
            int correlated_count = 0;
            for (size_t j = 0; j < i; ++j) {
                double sim_between = compute_similarity(votes[i].profile, votes[j].profile, nullptr);
                if (sim_between >= weights_.correlation_threshold) {
                    correlated_count++;
                }
            }
            if (correlated_count > 0) {
                // Novelty damping factor: 1 / (1 + 0.5 * correlated_count)
                eff_weights[i] = 1.0 / (1.0 + 0.5 * static_cast<double>(correlated_count));
            }
        }
        return eff_weights;
    };

    std::vector<double> liked_weights = compute_damping(liked_votes);
    std::vector<double> disliked_weights = compute_damping(disliked_votes);

    // Step 3: Evaluate each candidate issue
    for (const auto& candidate : candidates) {
        AffinityResult res;
        res.id = candidate.id;
        res.source = candidate.source;
        res.platform_issue_id = candidate.platform_issue_id;

        double P = 0.0;
        double N = 0.0;
        std::set<std::string> all_matched_groups;
        std::vector<std::string> tech_matches;
        std::vector<std::string> label_matches;

        // Positive evidence from liked issues
        for (size_t i = 0; i < liked_votes.size(); ++i) {
            std::vector<std::string> matched;
            double sim = compute_similarity(candidate.profile, liked_votes[i].profile, &matched);
            if (sim > 0.0) {
                P += liked_weights[i] * sim;
                for (const auto& g : matched) {
                    all_matched_groups.insert(g);
                }
                // Record overlapping technologies for grounded explanation
                for (const auto& t : candidate.profile.technologies) {
                    std::string nt = normalize_token(t);
                    for (const auto& vt : liked_votes[i].profile.technologies) {
                        if (nt == normalize_token(vt)) {
                            tech_matches.push_back(vt);
                        }
                    }
                }
                // Record overlapping labels
                for (const auto& l : candidate.profile.labels) {
                    std::string nl = normalize_token(l);
                    for (const auto& vl : liked_votes[i].profile.labels) {
                        if (nl == normalize_token(vl)) {
                            label_matches.push_back(vl);
                        }
                    }
                }
            }
        }

        // Negative evidence from disliked issues
        for (size_t i = 0; i < disliked_votes.size(); ++i) {
            std::vector<std::string> matched;
            double sim = compute_similarity(candidate.profile, disliked_votes[i].profile, &matched);
            if (sim > 0.0) {
                N += disliked_weights[i] * sim;
                for (const auto& g : matched) {
                    all_matched_groups.insert(g);
                }
            }
        }

        res.positive_evidence = P;
        res.negative_evidence = N;

        double E = P + N;
        if (E <= 0.0) {
            res.affinity_score = 0.0;
            res.confidence = 0.0;
            res.conflict_indicator = false;
            res.conflict_ratio = 0.0;
            res.explanations.push_back("No preference evidence or comparable feature overlap available.");
        } else {
            // Smoothed affinity formula: 100 * (P - N) / (P + N + lambda)
            res.affinity_score = 100.0 * (P - N) / (E + weights_.lambda);

            // Bounds check [-100, 100]
            if (res.affinity_score > 100.0) res.affinity_score = 100.0;
            if (res.affinity_score < -100.0) res.affinity_score = -100.0;

            // Confidence: E / (E + k)
            res.confidence = E / (E + weights_.k);
            if (res.confidence > 1.0) res.confidence = 1.0;
            if (res.confidence < 0.0) res.confidence = 0.0;

            // Conflict calculation
            if (P > 0.0 && N > 0.0) {
                res.conflict_ratio = (2.0 * std::min(P, N)) / (P + N);
            } else {
                res.conflict_ratio = 0.0;
            }
            res.conflict_indicator = (res.conflict_ratio >= 0.35 && E >= 0.5);

            // Grounded explanations
            tech_matches = normalize_tokens(tech_matches);
            label_matches = normalize_tokens(label_matches);

            if (res.conflict_indicator) {
                res.explanations.push_back("Conflicting preference evidence: matches both liked and disliked issues.");
            }
            if (P > N) {
                if (!tech_matches.empty()) {
                    std::ostringstream ss;
                    ss << "Similar technology stack (";
                    for (size_t m = 0; m < tech_matches.size() && m < 3; ++m) {
                        if (m > 0) ss << ", ";
                        ss << tech_matches[m];
                    }
                    ss << ") to previously liked issues.";
                    res.explanations.push_back(ss.str());
                }
                if (!label_matches.empty()) {
                    std::ostringstream ss;
                    ss << "Similar category/labels (";
                    for (size_t m = 0; m < label_matches.size() && m < 3; ++m) {
                        if (m > 0) ss << ", ";
                        ss << label_matches[m];
                    }
                    ss << ") to previously liked issues.";
                    res.explanations.push_back(ss.str());
                }
                if (all_matched_groups.find("repository") != all_matched_groups.end()) {
                    res.explanations.push_back("Same repository as previously liked issues.");
                }
            } else if (N > P) {
                res.explanations.push_back("Similar characteristics to previously disliked issues.");
            }
        }

        res.matched_feature_groups.assign(all_matched_groups.begin(), all_matched_groups.end());
        results.push_back(res);
    }

    return results;
}

} // namespace issue_pilot
