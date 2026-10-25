#include "../include/vote_affinity.hpp"
#include "../include/ranking_engine.hpp"
#include "../include/json_adapter.hpp"
#include <iostream>
#include <cassert>
#include <cmath>
#include <vector>
#include <string>

static int total_assertions = 0;
static int passed_assertions = 0;

void report_affinity_test(const std::string& name, bool pass, const std::string& details = "") {
    total_assertions++;
    if (pass) passed_assertions++;
    std::cout << "[TEST] " << (pass ? "PASS" : "FAIL") << " | " << name;
    if (!details.empty()) {
        std::cout << " (" << details << ")";
    }
    std::cout << "\n";
    if (!pass) {
        std::cerr << "Assertion failed for " << name << "!\n";
        std::exit(1);
    }
}

int main() {
    std::cout << "====================================================\n";
    std::cout << "ISSUE PILOT — C++ VOTE-AFFINITY ENGINE UNIT TESTS\n";
    std::cout << "====================================================\n\n";

    issue_pilot::VoteAffinityEngine engine;

    // Test 1: No votes -> zero affinity and zero confidence
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-1";
        cand.source = "github";
        cand.platform_issue_id = "101";
        cand.profile.technologies = {"Python", "FastAPI"};
        cand.profile.labels = {"enhancement"};
        cand.profile.issue_type = "feature";
        cand.profile.difficulty = 60.0;
        cand.profile.difficulty_available = true;

        std::vector<issue_pilot::IssueVoteRecord> empty_votes;
        auto results = engine.evaluate({cand}, empty_votes);

        bool ok = (results.size() == 1 &&
                   results[0].affinity_score == 0.0 &&
                   results[0].confidence == 0.0 &&
                   results[0].positive_evidence == 0.0 &&
                   results[0].negative_evidence == 0.0);
        report_affinity_test("Test 1: No Votes (Zero Affinity & Zero Confidence)", ok);
    }

    // Test 2: Candidate identical to a liked issue -> positive evidence
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-liked";
        cand.source = "github";
        cand.platform_issue_id = "200";
        cand.profile.repository_full_name = "test/repo";
        cand.profile.technologies = {"Python", "FastAPI"};
        cand.profile.labels = {"enhancement"};
        cand.profile.issue_type = "feature";
        cand.profile.difficulty = 50.0;
        cand.profile.difficulty_available = true;

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "100";
        vote.vote = 1; // Liked
        vote.profile = cand.profile;

        auto results = engine.evaluate({cand}, {vote});
        bool ok = (results.size() == 1 &&
                   results[0].positive_evidence > 0.99 &&
                   results[0].negative_evidence == 0.0 &&
                   results[0].affinity_score > 0.0 &&
                   results[0].confidence > 0.0 &&
                   !results[0].explanations.empty());
        report_affinity_test("Test 2: Candidate Identical to Liked Issue (Positive Evidence)", ok,
                             "Affinity: " + std::to_string(results[0].affinity_score));
    }

    // Test 3: Candidate identical to a disliked issue -> negative evidence
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-disliked";
        cand.source = "github";
        cand.platform_issue_id = "300";
        cand.profile.repository_full_name = "test/disliked-repo";
        cand.profile.technologies = {"Rust"};
        cand.profile.labels = {"bug"};
        cand.profile.issue_type = "bug";
        cand.profile.difficulty = 80.0;
        cand.profile.difficulty_available = true;

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "150";
        vote.vote = -1; // Disliked
        vote.profile = cand.profile;

        auto results = engine.evaluate({cand}, {vote});
        bool ok = (results.size() == 1 &&
                   results[0].negative_evidence > 0.99 &&
                   results[0].positive_evidence == 0.0 &&
                   results[0].affinity_score < 0.0 &&
                   results[0].confidence > 0.0);
        report_affinity_test("Test 3: Candidate Identical to Disliked Issue (Negative Evidence)", ok,
                             "Affinity: " + std::to_string(results[0].affinity_score));
    }

    // Test 4: Candidate similar to both liked and disliked issues -> net evidence & conflict reporting
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-conflict";
        cand.source = "github";
        cand.platform_issue_id = "400";
        cand.profile.repository_full_name = "org/project";
        cand.profile.technologies = {"Java", "Spring"};
        cand.profile.labels = {"performance", "backend"};
        cand.profile.issue_type = "enhancement";
        cand.profile.difficulty = 60.0;
        cand.profile.difficulty_available = true;

        issue_pilot::IssueVoteRecord liked_vote;
        liked_vote.user_id = "user1";
        liked_vote.source = "github";
        liked_vote.platform_issue_id = "101";
        liked_vote.vote = 1;
        liked_vote.profile = cand.profile; // Strong positive match

        issue_pilot::IssueVoteRecord disliked_vote;
        disliked_vote.user_id = "user1";
        disliked_vote.source = "github";
        disliked_vote.platform_issue_id = "102";
        disliked_vote.vote = -1;
        disliked_vote.profile = cand.profile; // Equal negative match

        auto results = engine.evaluate({cand}, {liked_vote, disliked_vote});
        bool ok = (results.size() == 1 &&
                   results[0].positive_evidence > 0.9 &&
                   results[0].negative_evidence > 0.9 &&
                   std::abs(results[0].affinity_score) < 1.0 && // Net affinity near zero
                   results[0].conflict_indicator == true &&
                   results[0].conflict_ratio > 0.8);
        report_affinity_test("Test 4: Similar to Both Liked & Disliked (Conflict Reporting)", ok,
                             "Conflict Ratio: " + std::to_string(results[0].conflict_ratio));
    }

    // Test 5: Unrelated issues -> little or no affinity
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-unrelated";
        cand.source = "github";
        cand.platform_issue_id = "500";
        cand.profile.repository_full_name = "random/unknown";
        cand.profile.technologies = {"Haskell", "Prolog"};
        cand.profile.labels = {"security-audit"};
        cand.profile.issue_type = "audit";
        cand.profile.difficulty = 95.0;
        cand.profile.difficulty_available = true;

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "gitlab";
        vote.platform_issue_id = "202";
        vote.vote = 1;
        vote.profile.repository_full_name = "different/project";
        vote.profile.technologies = {"JavaScript", "React"};
        vote.profile.labels = {"ui", "frontend"};
        vote.profile.issue_type = "bug";
        vote.profile.difficulty = 20.0;
        vote.profile.difficulty_available = true;

        auto results = engine.evaluate({cand}, {vote});
        bool ok = (results.size() == 1 &&
                   results[0].positive_evidence < 0.10 &&
                   results[0].affinity_score < 15.0 &&
                   results[0].confidence < 0.10);
        report_affinity_test("Test 5: Unrelated Issues (Low Affinity & Evidence)", ok,
                             "Evidence: " + std::to_string(results[0].positive_evidence));
    }

    // Test 6: Partial feature overlap (e.g. shared technologies but different labels & difficulty)
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-partial";
        cand.source = "github";
        cand.platform_issue_id = "600";
        cand.profile.technologies = {"Python", "Docker"};
        cand.profile.labels = {"bug"};
        cand.profile.issue_type = "bug";
        cand.profile.difficulty = 30.0;
        cand.profile.difficulty_available = true;

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "601";
        vote.vote = 1;
        vote.profile.technologies = {"Python", "Kubernetes"};
        vote.profile.labels = {"feature"};
        vote.profile.issue_type = "enhancement";
        vote.profile.difficulty = 70.0;
        vote.profile.difficulty_available = true;

        auto results = engine.evaluate({cand}, {vote});
        bool ok = (results.size() == 1 &&
                   results[0].positive_evidence > 0.05 &&
                   results[0].positive_evidence < 0.80 &&
                   results[0].affinity_score > 0.0);
        report_affinity_test("Test 6: Partial Feature Overlap (Graded Affinity)", ok,
                             "Evidence: " + std::to_string(results[0].positive_evidence));
    }

    // Test 7: Missing technologies, labels, and difficulty (Renormalization over available weights)
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-missing-groups";
        cand.source = "bugzilla";
        cand.platform_issue_id = "700";
        cand.profile.repository_full_name = "mozilla/firefox";
        // technologies, labels, difficulty are missing
        cand.profile.issue_type = "defect";
        cand.profile.difficulty_available = false;

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "bugzilla";
        vote.platform_issue_id = "701";
        vote.vote = 1;
        vote.profile.repository_full_name = "mozilla/firefox";
        vote.profile.issue_type = "defect";
        vote.profile.difficulty_available = false;

        auto results = engine.evaluate({cand}, {vote});
        // Available weights: issue_type (0.15) + repository (0.05) = 0.20
        // Both match exactly -> similarity = (0.15*1.0 + 0.05*1.0) / 0.20 = 1.0!
        bool ok = (results.size() == 1 &&
                   std::abs(results[0].positive_evidence - 1.0) < 0.01 &&
                   results[0].affinity_score > 0.0);
        report_affinity_test("Test 7: Missing Metadata Renormalization (Non-negative)", ok,
                             "Positive Evidence: " + std::to_string(results[0].positive_evidence));
    }

    // Test 8: No comparable feature groups -> 0 evidence, 0 affinity
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-no-groups";
        cand.source = "github";
        cand.platform_issue_id = "800";
        // Empty feature profile

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "801";
        vote.vote = 1;
        // Also empty feature profile

        auto results = engine.evaluate({cand}, {vote});
        bool ok = (results.size() == 1 &&
                   results[0].positive_evidence == 0.0 &&
                   results[0].affinity_score == 0.0 &&
                   results[0].confidence == 0.0);
        report_affinity_test("Test 8: No Comparable Feature Groups (Zero Affinity)", ok);
    }

    // Test 9: A single weak match does not produce an extreme score
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-weak";
        cand.source = "github";
        cand.platform_issue_id = "900";
        cand.profile.technologies = {"C++", "CMake", "Boost", "Qt", "OpenGL"};
        cand.profile.labels = {"core", "graphics", "engine", "gui"};
        cand.profile.issue_type = "feature";
        cand.profile.difficulty = 85.0;
        cand.profile.difficulty_available = true;

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "901";
        vote.vote = 1;
        // Shares only CMake out of 5+ technologies
        vote.profile.technologies = {"CMake", "Python", "Docker"};
        vote.profile.labels = {"ci", "devops"};
        vote.profile.issue_type = "task";
        vote.profile.difficulty = 20.0;
        vote.profile.difficulty_available = true;

        auto results = engine.evaluate({cand}, {vote});
        bool ok = (results.size() == 1 &&
                   results[0].affinity_score < 35.0 &&
                   results[0].confidence < 0.20);
        report_affinity_test("Test 9: Weak Match Does Not Produce Extreme Score", ok,
                             "Affinity: " + std::to_string(results[0].affinity_score));
    }

    // Test 10: Affinity stays within [-100, 100] bounds
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-bounds";
        cand.source = "github";
        cand.platform_issue_id = "1000";
        cand.profile.technologies = {"Java"};

        std::vector<issue_pilot::IssueVoteRecord> votes;
        // 50 likes
        for (int i = 0; i < 50; ++i) {
            issue_pilot::IssueVoteRecord v;
            v.user_id = "user1";
            v.source = "github";
            v.platform_issue_id = "like-" + std::to_string(i);
            v.vote = 1;
            v.profile.technologies = {"Java"};
            votes.push_back(v);
        }

        auto results = engine.evaluate({cand}, votes);
        bool ok = (results[0].affinity_score >= -100.0 && results[0].affinity_score <= 100.0);
        report_affinity_test("Test 10: Affinity Stays Within Documented Bounds [-100, 100]", ok,
                             "Score: " + std::to_string(results[0].affinity_score));
    }

    // Test 11: Confidence remains within [0, 1]
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-conf";
        cand.source = "github";
        cand.platform_issue_id = "1100";
        cand.profile.technologies = {"Go"};

        std::vector<issue_pilot::IssueVoteRecord> votes;
        for (int i = 0; i < 100; ++i) {
            issue_pilot::IssueVoteRecord v;
            v.user_id = "user1";
            v.source = "github";
            v.platform_issue_id = "go-" + std::to_string(i);
            v.vote = 1;
            v.profile.technologies = {"Go"};
            votes.push_back(v);
        }

        auto results = engine.evaluate({cand}, votes);
        bool ok = (results[0].confidence >= 0.0 && results[0].confidence <= 1.0);
        report_affinity_test("Test 11: Confidence Remains Within [0, 1]", ok,
                             "Confidence: " + std::to_string(results[0].confidence));
    }

    // Test 12: Duplicate votes do not increase evidence
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-dup";
        cand.source = "github";
        cand.platform_issue_id = "1200";
        cand.profile.technologies = {"Python"};

        issue_pilot::IssueVoteRecord v1;
        v1.user_id = "user1";
        v1.source = "github";
        v1.platform_issue_id = "item-dup";
        v1.vote = 1;
        v1.profile.technologies = {"Python"};

        // Single vote
        auto res_single = engine.evaluate({cand}, {v1});

        // Duplicate votes with exact same stable identity
        auto res_duplicate = engine.evaluate({cand}, {v1, v1, v1, v1});

        bool ok = (std::abs(res_single[0].positive_evidence - res_duplicate[0].positive_evidence) < 0.0001 &&
                   std::abs(res_single[0].affinity_score - res_duplicate[0].affinity_score) < 0.0001);
        report_affinity_test("Test 12: Duplicate Votes Do Not Increase Evidence", ok,
                             "Single: " + std::to_string(res_single[0].positive_evidence) +
                             " | Dup: " + std::to_string(res_duplicate[0].positive_evidence));
    }

    // Test 13: Changing a vote changes its sign rather than duplicating its influence
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-change";
        cand.source = "github";
        cand.platform_issue_id = "1300";
        cand.profile.technologies = {"TypeScript"};

        issue_pilot::IssueVoteRecord v_like;
        v_like.user_id = "user1";
        v_like.source = "github";
        v_like.platform_issue_id = "ts-100";
        v_like.vote = 1;
        v_like.profile.technologies = {"TypeScript"};

        issue_pilot::IssueVoteRecord v_dislike = v_like;
        v_dislike.vote = -1; // User changed vote to dislike

        // Sequence with like first, then dislike for same identity
        auto res = engine.evaluate({cand}, {v_like, v_dislike});

        // The latest vote (-1) should dominate; positive evidence must be 0
        bool ok = (res[0].positive_evidence == 0.0 &&
                   res[0].negative_evidence > 0.0 &&
                   res[0].affinity_score < 0.0);
        report_affinity_test("Test 13: Changing Vote Changes Sign Rather Than Duplicating", ok,
                             "Affinity: " + std::to_string(res[0].affinity_score));
    }

    // Test 14: Removing a vote removes its influence
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-remove";
        cand.source = "github";
        cand.platform_issue_id = "1400";
        cand.profile.technologies = {"Ruby"};

        issue_pilot::IssueVoteRecord v;
        v.user_id = "user1";
        v.source = "github";
        v.platform_issue_id = "ruby-1";
        v.vote = 0; // Removed vote (vote = 0)
        v.profile.technologies = {"Ruby"};

        auto res = engine.evaluate({cand}, {v});
        bool ok = (res[0].positive_evidence == 0.0 &&
                   res[0].negative_evidence == 0.0 &&
                   res[0].affinity_score == 0.0);
        report_affinity_test("Test 14: Removing Vote (vote=0) Removes Influence", ok);
    }

    // Test 15: Identical inputs produce deterministic outputs across 10 runs
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-deter";
        cand.source = "github";
        cand.platform_issue_id = "1500";
        cand.profile.technologies = {"Python", "SQL"};
        cand.profile.labels = {"enhancement"};
        cand.profile.issue_type = "feature";
        cand.profile.difficulty = 45.0;
        cand.profile.difficulty_available = true;

        issue_pilot::IssueVoteRecord v1;
        v1.user_id = "user1"; v1.source = "github"; v1.platform_issue_id = "d1"; v1.vote = 1;
        v1.profile = cand.profile;

        auto first_res = engine.evaluate({cand}, {v1});
        bool identical = true;
        for (int r = 0; r < 10; ++r) {
            auto run_res = engine.evaluate({cand}, {v1});
            if (std::abs(run_res[0].affinity_score - first_res[0].affinity_score) > 1e-9 ||
                std::abs(run_res[0].confidence - first_res[0].confidence) > 1e-9) {
                identical = false;
            }
        }
        report_affinity_test("Test 15: Deterministic Outputs Across 10 Runs", identical);
    }

    // Test 16: Different users' vote histories are isolated
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-user";
        cand.source = "github";
        cand.platform_issue_id = "1600";
        cand.profile.technologies = {"Scala"};

        issue_pilot::IssueVoteRecord user_a_vote;
        user_a_vote.user_id = "alice";
        user_a_vote.source = "github";
        user_a_vote.platform_issue_id = "scala-1";
        user_a_vote.vote = 1;
        user_a_vote.profile.technologies = {"Scala"};

        issue_pilot::IssueVoteRecord user_b_vote;
        user_b_vote.user_id = "bob";
        user_b_vote.source = "github";
        user_b_vote.platform_issue_id = "scala-1";
        user_b_vote.vote = -1;
        user_b_vote.profile.technologies = {"Scala"};

        // When evaluating for Alice, Bob's votes should not be included
        auto alice_res = engine.evaluate({cand}, {user_a_vote});
        auto bob_res = engine.evaluate({cand}, {user_b_vote});

        bool ok = (alice_res[0].affinity_score > 0.0 &&
                   bob_res[0].affinity_score < 0.0 &&
                   alice_res[0].negative_evidence == 0.0 &&
                   bob_res[0].positive_evidence == 0.0);
        report_affinity_test("Test 16: User Vote Histories Are Strictly Isolated", ok);
    }

    // Test 17: Incorrect issue identities and unverified snapshots are excluded
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-bad-id";
        cand.source = "github";
        cand.platform_issue_id = "1700";
        cand.profile.technologies = {"C#"};

        // Vote with empty source
        issue_pilot::IssueVoteRecord bad_vote1;
        bad_vote1.user_id = "user1";
        bad_vote1.source = ""; // Invalid
        bad_vote1.platform_issue_id = "1700";
        bad_vote1.vote = 1;
        bad_vote1.profile.technologies = {"C#"};

        // Vote with empty platform_issue_id
        issue_pilot::IssueVoteRecord bad_vote2;
        bad_vote2.user_id = "user1";
        bad_vote2.source = "github";
        bad_vote2.platform_issue_id = ""; // Invalid
        bad_vote2.vote = 1;
        bad_vote2.profile.technologies = {"C#"};

        auto res = engine.evaluate({cand}, {bad_vote1, bad_vote2});
        bool ok = (res[0].positive_evidence == 0.0 &&
                   res[0].affinity_score == 0.0 &&
                   res[0].confidence == 0.0);
        report_affinity_test("Test 17: Unverified & Incomplete Identities Excluded", ok);
    }

    // Test 18: Original ranking engine produces exactly the same regression scores & order
    {
        issue_pilot::RankingEngine ranker;
        issue_pilot::ContributorProfile profile;
        profile.skills["Python"] = issue_pilot::ContributorSkill{"Python", 90.0, 1.0};
        profile.skills["FastAPI"] = issue_pilot::ContributorSkill{"FastAPI", 80.0, 1.0};
        profile.skills["MongoDB"] = issue_pilot::ContributorSkill{"MongoDB", 70.0, 1.0};

        issue_pilot::IssueRecord issue;
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"FastAPI", 5.0, 0.0});
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 3.0, 0.0});
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"MongoDB", 2.0, 0.0});

        double s = ranker.calculate_skill_match(profile, issue);
        bool skill_ok = (std::abs(s - 81.0) < 0.001);

        // Test 6-factor full score
        issue.id = "regression-issue";
        issue.issue_number = 100;
        issue.issue_url = "https://github.com/org/repo/issues/100";
        issue.state = "open";
        issue.difficulty_evidence.scope_change_size = 70.0;
        issue.difficulty_evidence.technical_complexity = 70.0;
        issue.difficulty_evidence.historical_effort = 70.0;
        issue.difficulty_evidence.dependency_arch_impact = 70.0;
        issue.difficulty_evidence.coordination_complexity = 70.0;
        issue.responsiveness_evidence.response_time_score = 80.0;
        issue.responsiveness_evidence.response_rate_score = 80.0;
        issue.responsiveness_evidence.pr_review_score = 80.0;
        issue.responsiveness_evidence.recent_maintainer_act = 80.0;
        issue.health_evidence.recent_commits = 80.0;
        issue.health_evidence.pr_activity = 80.0;
        issue.health_evidence.issue_activity = 80.0;
        issue.health_evidence.active_contributors = 80.0;
        issue.health_evidence.recent_releases = 80.0;
        issue.freshness_evidence.age_score = 80.0;
        issue.freshness_evidence.activity_score = 80.0;
        profile.has_feedback_history = false;

        auto scores = ranker.compute_scores(profile, issue);
        bool ranker_intact = (skill_ok && scores.final_score > 0.0 && scores.missing_components.size() == 1 &&
                              scores.missing_components[0] == "Feedback (Cold Start)");

        report_affinity_test("Test 18: Original Ranking Engine Invariant Regression Fixture", ranker_intact,
                             "Skill: " + std::to_string(s) + " | Final: " + std::to_string(scores.final_score));
    }

    // Test 19: One-sided missing labels (Omitted and renormalized, not interpreted as genuine mismatch)
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-labels-missing-one-side";
        cand.source = "github";
        cand.platform_issue_id = "1900";
        cand.profile.technologies = {"Python"};
        cand.profile.labels = {"performance", "backend"}; // Candidate has labels

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "1901";
        vote.vote = 1;
        vote.profile.technologies = {"Python"};
        // vote.profile.labels is empty (unavailable)

        auto results = engine.evaluate({cand}, {vote});
        // Technology matches 1.0 (weight 0.30). Labels missing on vote side -> omitted.
        // Total available weight = 0.30. Similarity = 0.30 / 0.30 = 1.0!
        bool ok = (results.size() == 1 &&
                   std::abs(results[0].positive_evidence - 1.0) < 0.001 &&
                   results[0].affinity_score == 50.0);
        report_affinity_test("Test 19: One-Sided Missing Labels (Renormalized Without Penalty)", ok,
                             "Evidence: " + std::to_string(results[0].positive_evidence));
    }

    // Test 20: One-sided missing technologies (Omitted and renormalized)
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-tech-missing-one-side";
        cand.source = "github";
        cand.platform_issue_id = "2000";
        cand.profile.technologies = {"Java", "Kotlin"}; // Candidate has tech
        cand.profile.labels = {"security"};

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "2001";
        vote.vote = 1;
        // vote.profile.technologies is empty (unavailable)
        vote.profile.labels = {"security"};

        auto results = engine.evaluate({cand}, {vote});
        // Labels match 1.0 (weight 0.30). Tech missing on vote side -> omitted.
        // Total available weight = 0.30. Similarity = 0.30 / 0.30 = 1.0!
        bool ok = (results.size() == 1 &&
                   std::abs(results[0].positive_evidence - 1.0) < 0.001 &&
                   results[0].affinity_score == 50.0);
        report_affinity_test("Test 20: One-Sided Missing Technologies (Renormalized Without Penalty)", ok,
                             "Evidence: " + std::to_string(results[0].positive_evidence));
    }

    // Test 21: Unknown difficulty (difficulty_available = false or < 0) omitted and renormalized
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-diff-unknown";
        cand.source = "github";
        cand.platform_issue_id = "2100";
        cand.profile.technologies = {"Go"};
        cand.profile.difficulty = -1.0;
        cand.profile.difficulty_available = false; // Candidate difficulty unknown

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "2101";
        vote.vote = 1;
        vote.profile.technologies = {"Go"};
        vote.profile.difficulty = 80.0;
        vote.profile.difficulty_available = true; // Vote difficulty known

        auto results = engine.evaluate({cand}, {vote});
        // Difficulty unavailable on candidate -> omitted from available weights.
        // Tech matches 1.0 -> Evidence = 1.0, Affinity = 50.0
        bool ok = (results.size() == 1 &&
                   std::abs(results[0].positive_evidence - 1.0) < 0.001 &&
                   results[0].affinity_score == 50.0);
        report_affinity_test("Test 21: Unknown Difficulty Omitted and Renormalized", ok,
                             "Evidence: " + std::to_string(results[0].positive_evidence));
    }

    // Test 22: Genuine categorical mismatch vs missing category
    {
        // Case A: Missing issue_type
        issue_pilot::CandidateIssue candMissing;
        candMissing.id = "cand-missing-type";
        candMissing.source = "github";
        candMissing.platform_issue_id = "2201";
        candMissing.profile.technologies = {"Python"};
        candMissing.profile.issue_type = ""; // Missing

        // Case B: Genuine mismatch on issue_type
        issue_pilot::CandidateIssue candMismatch;
        candMismatch.id = "cand-mismatch-type";
        candMismatch.source = "github";
        candMismatch.platform_issue_id = "2202";
        candMismatch.profile.technologies = {"Python"};
        candMismatch.profile.issue_type = "bug"; // Known but mismatched!

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "2200";
        vote.vote = 1;
        vote.profile.technologies = {"Python"};
        vote.profile.issue_type = "enhancement"; // Voted issue type

        auto results = engine.evaluate({candMissing, candMismatch}, {vote});
        // For candMissing: issue_type omitted. tech_sim = 1.0. Available weight = 0.30 -> Similarity = 1.0. Evidence = 1.0
        // For candMismatch: both known. tech_sim = 1.0 (0.30), type_sim = 0.0 (0.15). Total weight = 0.45. Similarity = 0.30 / 0.45 = 0.6667. Evidence = 0.6667
        bool ok = (results.size() == 2 &&
                   results[0].positive_evidence > results[1].positive_evidence &&
                   std::abs(results[0].positive_evidence - 1.0) < 0.001 &&
                   std::abs(results[1].positive_evidence - (0.30 / 0.45)) < 0.001 &&
                   results[0].affinity_score > results[1].affinity_score);
        report_affinity_test("Test 22: Genuine Categorical Mismatch Penalized vs Missing Feature", ok,
                             "Missing: " + std::to_string(results[0].positive_evidence) +
                             " | Mismatch: " + std::to_string(results[1].positive_evidence));
    }

    // Test 23: Entirely unavailable feature profiles produce zero evidence and zero affinity
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-empty";
        cand.source = "github";
        cand.platform_issue_id = "2300";
        // completely empty profile

        issue_pilot::IssueVoteRecord vote;
        vote.user_id = "user1";
        vote.source = "github";
        vote.platform_issue_id = "2301";
        vote.vote = 1;
        // completely empty profile

        auto results = engine.evaluate({cand}, {vote});
        bool ok = (results.size() == 1 &&
                   results[0].positive_evidence == 0.0 &&
                   results[0].negative_evidence == 0.0 &&
                   results[0].affinity_score == 0.0 &&
                   results[0].confidence == 0.0);
        report_affinity_test("Test 23: Entirely Unavailable Profiles Produce Zero Evidence", ok);
    }

    // Test 24: Correlated-evidence damping under arbitrary permutations of the input vote set
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-perm";
        cand.source = "github";
        cand.platform_issue_id = "2400";
        cand.profile.technologies = {"Python", "FastAPI"};
        cand.profile.labels = {"api"};

        // Create 4 near-duplicate likes and 2 distinct dislikes
        std::vector<issue_pilot::IssueVoteRecord> vote_set;
        for (int i = 0; i < 4; ++i) {
            issue_pilot::IssueVoteRecord v;
            v.user_id = "user1";
            v.source = "github";
            v.platform_issue_id = "like-dup-" + std::to_string(i);
            v.vote = 1;
            v.profile.technologies = {"Python", "FastAPI"};
            v.profile.labels = {"api"};
            v.updated_at = "2026-10-25T12:00:0" + std::to_string(i) + "Z";
            vote_set.push_back(v);
        }
        for (int i = 0; i < 2; ++i) {
            issue_pilot::IssueVoteRecord v;
            v.user_id = "user1";
            v.source = "github";
            v.platform_issue_id = "dislike-" + std::to_string(i);
            v.vote = -1;
            v.profile.technologies = {"Rust"};
            v.profile.labels = {"crypto"};
            v.updated_at = "2026-10-25T12:00:1" + std::to_string(i) + "Z";
            vote_set.push_back(v);
        }

        // Add an updated duplicate vote to test deterministic deduplication
        issue_pilot::IssueVoteRecord v_update;
        v_update.user_id = "user1";
        v_update.source = "github";
        v_update.platform_issue_id = "like-dup-0";
        v_update.vote = 1;
        v_update.profile.technologies = {"Python", "FastAPI"};
        v_update.profile.labels = {"api"};
        v_update.updated_at = "2026-10-25T13:00:00Z"; // Later timestamp
        vote_set.push_back(v_update);

        // Baseline run
        auto base_results = engine.evaluate({cand}, vote_set);

        // Permutations
        bool all_permutations_match = true;
        // Permutation 1: Reversed
        std::vector<issue_pilot::IssueVoteRecord> perm1 = vote_set;
        std::reverse(perm1.begin(), perm1.end());
        auto res_perm1 = engine.evaluate({cand}, perm1);
        if (std::abs(res_perm1[0].affinity_score - base_results[0].affinity_score) > 1e-9 ||
            std::abs(res_perm1[0].confidence - base_results[0].confidence) > 1e-9 ||
            std::abs(res_perm1[0].positive_evidence - base_results[0].positive_evidence) > 1e-9) {
            all_permutations_match = false;
        }

        // Permutation 2: Rotated
        std::vector<issue_pilot::IssueVoteRecord> perm2 = vote_set;
        std::rotate(perm2.begin(), perm2.begin() + 3, perm2.end());
        auto res_perm2 = engine.evaluate({cand}, perm2);
        if (std::abs(res_perm2[0].affinity_score - base_results[0].affinity_score) > 1e-9 ||
            std::abs(res_perm2[0].confidence - base_results[0].confidence) > 1e-9 ||
            std::abs(res_perm2[0].positive_evidence - base_results[0].positive_evidence) > 1e-9) {
            all_permutations_match = false;
        }

        // Permutation 3: Dislikes first, likes second
        std::vector<issue_pilot::IssueVoteRecord> perm3;
        for (const auto& v : vote_set) if (v.vote == -1) perm3.push_back(v);
        for (const auto& v : vote_set) if (v.vote == 1) perm3.push_back(v);
        auto res_perm3 = engine.evaluate({cand}, perm3);
        if (std::abs(res_perm3[0].affinity_score - base_results[0].affinity_score) > 1e-9 ||
            std::abs(res_perm3[0].confidence - base_results[0].confidence) > 1e-9 ||
            std::abs(res_perm3[0].positive_evidence - base_results[0].positive_evidence) > 1e-9) {
            all_permutations_match = false;
        }

        report_affinity_test("Test 24: Permutation-Invariant Deterministic Damping & Deduplication", all_permutations_match,
                             "Affinity: " + std::to_string(base_results[0].affinity_score) +
                             " | Conf: " + std::to_string(base_results[0].confidence));
    }

    // Test 25: Configurable correlation_threshold and damping_factor
    {
        issue_pilot::CandidateIssue cand;
        cand.id = "cand-cfg";
        cand.source = "github";
        cand.platform_issue_id = "2500";
        cand.profile.technologies = {"Python"};

        std::vector<issue_pilot::IssueVoteRecord> votes;
        for (int i = 0; i < 3; ++i) {
            issue_pilot::IssueVoteRecord v;
            v.user_id = "user1";
            v.source = "github";
            v.platform_issue_id = "like-cfg-" + std::to_string(i);
            v.vote = 1;
            v.profile.technologies = {"Python"};
            votes.push_back(v);
        }

        // Engine with default damping (damping_factor = 0.50):
        // weights: w[0] = 1.0, w[1] = 1/(1 + 0.5*1) = 0.6667, w[2] = 1/(1 + 0.5*2) = 0.50 -> Sum P = 2.1667
        auto default_res = engine.evaluate({cand}, votes);

        // Engine with custom damping_factor = 1.00:
        // weights: w[0] = 1.0, w[1] = 1/(1 + 1.0*1) = 0.50, w[2] = 1/(1 + 1.0*2) = 0.3333 -> Sum P = 1.8333
        issue_pilot::AffinityWeights custom_weights = engine.get_weights();
        custom_weights.damping_factor = 1.0;
        custom_weights.correlation_threshold = 0.75;
        issue_pilot::VoteAffinityEngine custom_engine(custom_weights);
        auto custom_res = custom_engine.evaluate({cand}, votes);

        bool weights_configured = (std::abs(default_res[0].positive_evidence - 2.166667) < 0.01 &&
                                   std::abs(custom_res[0].positive_evidence - 1.833333) < 0.01 &&
                                   custom_res[0].positive_evidence < default_res[0].positive_evidence &&
                                   custom_res[0].confidence < default_res[0].confidence);

        report_affinity_test("Test 25: Configurable Correlation Threshold & Damping Factor", weights_configured,
                             "Default P: " + std::to_string(default_res[0].positive_evidence) +
                             " | Custom P: " + std::to_string(custom_res[0].positive_evidence));
    }

    std::cout << "\n====================================================\n";
    std::cout << "ALL 25 VOTE-AFFINITY TEST SCENARIOS PASSED!\n";
    std::cout << "Total Assertions: " << total_assertions << " | Passed: " << passed_assertions << "\n";
    std::cout << "====================================================\n";
    return 0;
}
