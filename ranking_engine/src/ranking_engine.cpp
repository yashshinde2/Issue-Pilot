#include "../include/ranking_engine.hpp"
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <cmath>

namespace issue_pilot {

double RankingEngine::calculate_skill_match(const ContributorProfile& profile, 
                                            IssueRecord& issue) const {
    issue.normalize_technologies();
    if (issue.technologies.empty()) {
        return 50.0; // Neutral baseline if no specific technology detected
    }

    double match_sum = 0.0;
    for (const auto& t : issue.technologies) {
        double contributor_skill = 0.0;
        auto it = profile.skills.find(t.technology);
        if (it != profile.skills.end()) {
            contributor_skill = it->second.score;
        }
        match_sum += (contributor_skill * t.normalized_relevance);
    }
    return std::max(0.0, std::min(100.0, match_sum));
}

double RankingEngine::calculate_difficulty_match(double capability, 
                                                const DifficultyEvidence& diff_evidence,
                                                double& out_issue_difficulty) const {
    out_issue_difficulty = diff_evidence.compute_issue_difficulty();
    double d = 100.0 - std::abs(capability - out_issue_difficulty);
    return std::max(0.0, std::min(100.0, d));
}

double RankingEngine::calculate_responsiveness(const ResponsivenessEvidence& evidence) const {
    double r = evidence.compute_responsiveness();
    return std::max(0.0, std::min(100.0, r));
}

double RankingEngine::calculate_repository_health(const RepositoryHealthEvidence& evidence) const {
    double h = evidence.compute_health();
    return std::max(0.0, std::min(100.0, h));
}

bool RankingEngine::calculate_feedback(const ContributorProfile& profile,
                                      double& out_feedback,
                                      double& out_acceptance,
                                      double& out_completion) const {
    if (!profile.has_feedback_history) {
        return false;
    }
    int total_eval = profile.accepted_count + profile.rejected_count;
    if (total_eval <= 0) {
        return false;
    }

    out_acceptance = (static_cast<double>(profile.accepted_count) / total_eval) * 100.0;
    if (profile.accepted_count > 0) {
        out_completion = (static_cast<double>(profile.completed_count) / profile.accepted_count) * 100.0;
    } else {
        out_completion = 0.0;
    }

    out_feedback = 0.30 * out_acceptance + 
                   0.40 * out_completion + 
                   0.30 * profile.similarity_to_history;

    out_feedback = std::max(0.0, std::min(100.0, out_feedback));
    return true;
}

double RankingEngine::calculate_freshness(const FreshnessEvidence& evidence) const {
    double n = evidence.compute_freshness();
    return std::max(0.0, std::min(100.0, n));
}

ComponentScores RankingEngine::compute_scores(const ContributorProfile& profile,
                                             IssueRecord& issue) const {
    ComponentScores s;

    // S
    s.skill_match = calculate_skill_match(profile, issue);
    s.skill_available = true;

    // D
    s.difficulty_match = calculate_difficulty_match(profile.capability, 
                                                    issue.difficulty_evidence, 
                                                    s.computed_issue_difficulty);
    s.difficulty_available = true;

    // R
    s.responsiveness_available = issue.responsiveness_evidence.available;
    if (s.responsiveness_available) {
        s.responsiveness = calculate_responsiveness(issue.responsiveness_evidence);
    } else {
        s.missing_components.push_back("Responsiveness");
    }

    // H
    s.health_available = issue.health_evidence.available;
    if (s.health_available) {
        s.repository_health = calculate_repository_health(issue.health_evidence);
    } else {
        s.missing_components.push_back("Repository Health");
    }

    // F
    if (profile.has_feedback_history) {
        s.feedback_available = calculate_feedback(profile, s.feedback, s.acceptance_score, s.completion_score);
        if (!s.feedback_available) {
            s.missing_components.push_back("Feedback");
        }
    } else {
        s.feedback_available = false;
        s.missing_components.push_back("Feedback (Cold Start)");
    }

    // N
    s.freshness_available = issue.freshness_evidence.available;
    if (s.freshness_available) {
        s.freshness = calculate_freshness(issue.freshness_evidence);
    } else {
        s.missing_components.push_back("Freshness");
    }

    // Missing data renormalization
    double total_available_weight = 0.0;
    if (s.skill_available) total_available_weight += weights_.skill_weight;
    if (s.difficulty_available) total_available_weight += weights_.difficulty_weight;
    if (s.responsiveness_available) total_available_weight += weights_.responsiveness_weight;
    if (s.health_available) total_available_weight += weights_.health_weight;
    if (s.feedback_available) total_available_weight += weights_.feedback_weight;
    if (s.freshness_available) total_available_weight += weights_.freshness_weight;

    if (total_available_weight <= 0.0001) {
        s.final_score = 0.0;
        s.explanation = "Insufficient scoring data available.";
        return s;
    }

    // Renormalize effective weights
    s.effective_skill_weight = s.skill_available ? (weights_.skill_weight / total_available_weight) : 0.0;
    s.effective_diff_weight = s.difficulty_available ? (weights_.difficulty_weight / total_available_weight) : 0.0;
    s.effective_resp_weight = s.responsiveness_available ? (weights_.responsiveness_weight / total_available_weight) : 0.0;
    s.effective_health_weight = s.health_available ? (weights_.health_weight / total_available_weight) : 0.0;
    s.effective_fb_weight = s.feedback_available ? (weights_.feedback_weight / total_available_weight) : 0.0;
    s.effective_fresh_weight = s.freshness_available ? (weights_.freshness_weight / total_available_weight) : 0.0;

    s.weighted_skill = s.skill_match * s.effective_skill_weight;
    s.weighted_difficulty = s.difficulty_match * s.effective_diff_weight;
    s.weighted_responsiveness = s.responsiveness * s.effective_resp_weight;
    s.weighted_health = s.repository_health * s.effective_health_weight;
    s.weighted_feedback = s.feedback * s.effective_fb_weight;
    s.weighted_freshness = s.freshness * s.effective_fresh_weight;

    s.final_score = s.weighted_skill +
                    s.weighted_difficulty +
                    s.weighted_responsiveness +
                    s.weighted_health +
                    s.weighted_feedback +
                    s.weighted_freshness;

    s.explanation = generate_explanation(s);
    return s;
}

std::string RankingEngine::generate_explanation(const ComponentScores& s) {
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(2);
    oss << "Skill Match: " << s.skill_match << " x " << (s.effective_skill_weight * 100.0) << "% = " << s.weighted_skill << " | ";
    oss << "Difficulty Match: " << s.difficulty_match << " x " << (s.effective_diff_weight * 100.0) << "% = " << s.weighted_difficulty << " | ";
    if (s.responsiveness_available) {
        oss << "Responsiveness: " << s.responsiveness << " x " << (s.effective_resp_weight * 100.0) << "% = " << s.weighted_responsiveness << " | ";
    } else {
        oss << "Responsiveness: [N/A] | ";
    }
    if (s.health_available) {
        oss << "Repository Health: " << s.repository_health << " x " << (s.effective_health_weight * 100.0) << "% = " << s.weighted_health << " | ";
    } else {
        oss << "Repository Health: [N/A] | ";
    }
    if (s.feedback_available) {
        oss << "Feedback: " << s.feedback << " x " << (s.effective_fb_weight * 100.0) << "% = " << s.weighted_feedback << " | ";
    } else {
        oss << "Feedback: [N/A (Renormalized)] | ";
    }
    if (s.freshness_available) {
        oss << "Freshness: " << s.freshness << " x " << (s.effective_fresh_weight * 100.0) << "% = " << s.weighted_freshness;
    } else {
        oss << "Freshness: [N/A]";
    }
    oss << " -> Final Score: " << s.final_score << " / 100";
    return oss.str();
}

std::vector<RankingResult> RankingEngine::rank_candidates(const ContributorProfile& profile,
                                                         std::vector<IssueRecord>& candidates) const {
    std::vector<RankingResult> results;
    for (auto& issue : candidates) {
        if (!issue.is_eligible()) {
            continue; // Stage 0 Eligibility gate
        }
        RankingResult r;
        r.issue = issue;
        r.scores = compute_scores(profile, r.issue);
        results.push_back(r);
    }

    // Deterministic Sorting Order:
    // 1. Higher Final Score
    // 2. Higher Skill Match (S)
    // 3. Higher Difficulty Match (D)
    // 4. Higher Freshness (N)
    // 5. Stable deterministic issue number / ID
    std::sort(results.begin(), results.end(), [](const RankingResult& a, const RankingResult& b) {
        if (std::abs(a.scores.final_score - b.scores.final_score) > 0.001) {
            return a.scores.final_score > b.scores.final_score;
        }
        if (std::abs(a.scores.skill_match - b.scores.skill_match) > 0.001) {
            return a.scores.skill_match > b.scores.skill_match;
        }
        if (std::abs(a.scores.difficulty_match - b.scores.difficulty_match) > 0.001) {
            return a.scores.difficulty_match > b.scores.difficulty_match;
        }
        if (std::abs(a.scores.freshness - b.scores.freshness) > 0.001) {
            return a.scores.freshness > b.scores.freshness;
        }
        if (a.issue.issue_number != b.issue.issue_number) {
            return a.issue.issue_number < b.issue.issue_number;
        }
        return a.issue.id < b.issue.id;
    });

    for (size_t i = 0; i < results.size(); ++i) {
        results[i].rank = static_cast<int>(i + 1);
    }

    return results;
}

} // namespace issue_pilot
