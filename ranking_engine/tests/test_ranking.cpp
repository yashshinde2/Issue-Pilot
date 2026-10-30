#include "../include/ranking_engine.hpp"
#include "../include/json_adapter.hpp"
#include <iostream>
#include <fstream>
#include <sstream>
#include <cassert>
#include <cmath>
#include <vector>

static int total_assertions = 0;
static int passed_assertions = 0;

void report_test(const std::string& name, bool pass, double expected, double actual) {
    total_assertions++;
    if (pass) passed_assertions++;
    std::cout << "[TEST] " << (pass ? "PASS" : "FAIL") << " | " << name 
              << " | Expected: " << expected << " | Actual: " << actual << "\n";
    if (!pass) {
        std::cerr << "Assertion failed for " << name << "!\n";
        std::exit(1);
    }
}

int main() {
    std::cout << "========================================\n";
    std::cout << "ISSUE PILOT — C++ RANKING ENGINE UNIT TESTS\n";
    std::cout << "========================================\n\n";

    issue_pilot::RankingEngine engine;

    // Test 1: Skill Match
    // Python 90 (0.30), FastAPI 80 (0.50), MongoDB 70 (0.20) -> S = 27 + 40 + 14 = 81.0
    {
        issue_pilot::ContributorProfile profile;
        profile.skills["Python"] = issue_pilot::ContributorSkill{"Python", 90.0, 1.0};
        profile.skills["FastAPI"] = issue_pilot::ContributorSkill{"FastAPI", 80.0, 1.0};
        profile.skills["MongoDB"] = issue_pilot::ContributorSkill{"MongoDB", 70.0, 1.0};

        issue_pilot::IssueRecord issue;
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"FastAPI", 5.0, 0.0});
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 3.0, 0.0});
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"MongoDB", 2.0, 0.0});

        double s = engine.calculate_skill_match(profile, issue);
        report_test("Skill Match Calculation", std::abs(s - 81.0) < 0.001, 81.0, s);
    }

    // Test 2: Issue Difficulty
    // SC=70, TC=75, HE=65, DI=80, CC=50 -> 0.25*70 + 0.25*75 + 0.25*65 + 0.15*80 + 0.10*50 = 69.5
    {
        issue_pilot::DifficultyEvidence diff;
        diff.scope_change_size = 70.0;
        diff.technical_complexity = 75.0;
        diff.historical_effort = 65.0;
        diff.dependency_arch_impact = 80.0;
        diff.coordination_complexity = 50.0;

        double id = diff.compute_issue_difficulty();
        report_test("Issue Difficulty Formula", std::abs(id - 69.5) < 0.001, 69.5, id);
    }

    // Test 3: Difficulty Match
    // Capability = 76, ID = 72 -> D = 100 - |76 - 72| = 96.0
    {
        issue_pilot::DifficultyEvidence diff;
        diff.scope_change_size = 72.0;
        diff.technical_complexity = 72.0;
        diff.historical_effort = 72.0;
        diff.dependency_arch_impact = 72.0;
        diff.coordination_complexity = 72.0;

        double id_out = 0.0;
        double d = engine.calculate_difficulty_match(76.0, diff, id_out);
        report_test("Difficulty Match Proximity", std::abs(d - 96.0) < 0.001, 96.0, d);
    }

    // Test 4: Responsiveness
    // RT=90 (0.30), RR=90 (0.30), PR=85 (0.25), MA=90 (0.15) -> 27 + 27 + 21.25 + 13.5 = 88.75
    {
        issue_pilot::ResponsivenessEvidence resp;
        resp.response_time_score = 90.0;
        resp.response_rate_score = 90.0;
        resp.pr_review_score = 85.0;
        resp.recent_maintainer_act = 90.0;

        double r = engine.calculate_responsiveness(resp);
        report_test("Maintainer Responsiveness Formula", std::abs(r - 88.75) < 0.001, 88.75, r);
    }

    // Test 5: Repository Health
    // RC=90 (0.25), PR=80 (0.20), IA=85 (0.20), AC=75 (0.20), RR=80 (0.15) -> 22.5 + 16 + 17 + 15 + 12 = 82.5
    {
        issue_pilot::RepositoryHealthEvidence h;
        h.recent_commits = 90.0;
        h.pr_activity = 80.0;
        h.issue_activity = 85.0;
        h.active_contributors = 75.0;
        h.recent_releases = 80.0;

        double health = engine.calculate_repository_health(h);
        report_test("Repository Health Formula", std::abs(health - 82.5) < 0.001, 82.5, health);
    }

    // Test 6: Feedback
    // 12 accepted, 8 rejected -> A = 12/20*100 = 60
    // 12 accepted, 9 completed -> C = 9/12*100 = 75
    // Similarity = 90
    // F = 0.30(60) + 0.40(75) + 0.30(90) = 18 + 30 + 27 = 75.0
    {
        issue_pilot::ContributorProfile p;
        p.has_feedback_history = true;
        p.accepted_count = 12;
        p.rejected_count = 8;
        p.completed_count = 9;
        p.similarity_to_history = 90.0;

        double fb = 0.0, acc = 0.0, comp = 0.0;
        bool ok = engine.calculate_feedback(p, fb, acc, comp);
        report_test("Feedback Availability", ok ? 1.0 : 0.0, 1.0, ok ? 1.0 : 0.0);
        report_test("Feedback Acceptance Score", std::abs(acc - 60.0) < 0.001, 60.0, acc);
        report_test("Feedback Completion Score", std::abs(comp - 75.0) < 0.001, 75.0, comp);
        report_test("Feedback Total Score", std::abs(fb - 75.0) < 0.001, 75.0, fb);
    }

    // Test 7: Freshness
    // Age=80 (0.40), Activity=100 (0.60) -> 32 + 60 = 92.0
    {
        issue_pilot::FreshnessEvidence f;
        f.age_score = 80.0;
        f.activity_score = 100.0;

        double n = engine.calculate_freshness(f);
        report_test("Freshness Formula", std::abs(n - 92.0) < 0.001, 92.0, n);
    }

    // Test 8: Complete Worked Example from Algo Doc Section 16
    // S=81, D=76, R=89, H=83, F=75, N=89 -> Final Score = 80.95
    {
        issue_pilot::ContributorProfile prof;
        prof.skills["Python"] = issue_pilot::ContributorSkill{"Python", 90.0, 1.0};
        prof.skills["FastAPI"] = issue_pilot::ContributorSkill{"FastAPI", 80.0, 1.0};
        prof.skills["MongoDB"] = issue_pilot::ContributorSkill{"MongoDB", 70.0, 1.0};
        prof.capability = 100.0; // D = 100 - |100 - 76| = 76
        prof.has_feedback_history = true;
        prof.accepted_count = 12;
        prof.rejected_count = 8;
        prof.completed_count = 9;
        prof.similarity_to_history = 90.0;

        issue_pilot::IssueRecord issue;
        issue.id = "worked-example-1";
        issue.issue_url = "https://github.com/example/repo/issues/1";
        issue.state = "open";
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"FastAPI", 5.0, 0.0});
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 3.0, 0.0});
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"MongoDB", 2.0, 0.0});

        issue.difficulty_evidence.scope_change_size = 76.0;
        issue.difficulty_evidence.technical_complexity = 76.0;
        issue.difficulty_evidence.historical_effort = 76.0;
        issue.difficulty_evidence.dependency_arch_impact = 76.0;
        issue.difficulty_evidence.coordination_complexity = 76.0; // ID = 76

        issue.responsiveness_evidence.response_time_score = 89.0;
        issue.responsiveness_evidence.response_rate_score = 89.0;
        issue.responsiveness_evidence.pr_review_score = 89.0;
        issue.responsiveness_evidence.recent_maintainer_act = 89.0; // R = 89.0

        issue.health_evidence.recent_commits = 83.0;
        issue.health_evidence.pr_activity = 83.0;
        issue.health_evidence.issue_activity = 83.0;
        issue.health_evidence.active_contributors = 83.0;
        issue.health_evidence.recent_releases = 83.0; // H = 83.0

        issue.freshness_evidence.age_score = 89.0;
        issue.freshness_evidence.activity_score = 89.0; // N = 89.0

        auto scores = engine.compute_scores(prof, issue);
        report_test("Doc Section 16 Worked Example Final Score", std::abs(scores.final_score - 80.95) < 0.01, 80.95, scores.final_score);
    }

    // Test 9: Missing Data Renormalization
    {
        issue_pilot::ContributorProfile p_cold;
        p_cold.has_feedback_history = false; // Missing Feedback
        p_cold.skills["Python"] = issue_pilot::ContributorSkill{"Python", 80.0, 1.0};
        p_cold.capability = 70.0;

        issue_pilot::IssueRecord issue;
        issue.id = "missing-issue";
        issue.issue_url = "https://github.com/example/repo/issues/2";
        issue.state = "open";
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 1.0, 0.0});

        auto scores = engine.compute_scores(p_cold, issue);
        double expected_effective_skill = 0.35 / 0.90;
        report_test("Missing Feedback Renormalization", !scores.feedback_available, 0.0, scores.feedback_available ? 1.0 : 0.0);
        report_test("Effective Skill Weight Renormalization", std::abs(scores.effective_skill_weight - expected_effective_skill) < 0.001, expected_effective_skill, scores.effective_skill_weight);
    }

    // Test 10: Eligibility Gate
    {
        issue_pilot::IssueRecord closed_issue;
        closed_issue.state = "closed";
        report_test("Closed Issue Ineligibility", !closed_issue.is_eligible(), 0.0, closed_issue.is_eligible() ? 1.0 : 0.0);

        issue_pilot::IssueRecord assigned_issue;
        assigned_issue.state = "open";
        assigned_issue.is_assigned = true;
        report_test("Assigned Issue Ineligibility", !assigned_issue.is_eligible(), 0.0, assigned_issue.is_eligible() ? 1.0 : 0.0);
    }

    // Test 11: Case-Insensitive Skill Match Robustness
    {
        issue_pilot::ContributorProfile profile;
        profile.skills["Python"] = issue_pilot::ContributorSkill{"Python", 90.0, 1.0};
        profile.skills["FastAPI"] = issue_pilot::ContributorSkill{"FastAPI", 80.0, 1.0};

        issue_pilot::IssueRecord issue;
        // Issue technologies provided in lowercase
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"python", 1.0, 0.0});
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"fastapi", 1.0, 0.0});

        double s = engine.calculate_skill_match(profile, issue);
        report_test("Case-Insensitive Skill Matching", std::abs(s - 85.0) < 0.001, 85.0, s);
    }

    // Test 12: Six Component Weights Total 100%
    {
        issue_pilot::RankingWeights w;
        double sum = w.skill_weight + w.difficulty_weight + w.responsiveness_weight +
                     w.health_weight + w.feedback_weight + w.freshness_weight;
        report_test("Six Component Weights Total 100%", std::abs(sum - 1.0) < 0.0001, 1.0, sum);
    }

    // Test 13: Clamped Component Bounds [0, 100]
    {
        issue_pilot::DifficultyEvidence diff;
        diff.scope_change_size = 500.0; // Extreme
        diff.technical_complexity = 500.0;
        diff.historical_effort = 500.0;
        diff.dependency_arch_impact = 500.0;
        diff.coordination_complexity = 500.0;

        double out_diff = 0.0;
        double d = engine.calculate_difficulty_match(-50.0, diff, out_diff);
        bool bounded = (d >= 0.0 && d <= 100.0);
        report_test("Component Clamping Upper/Lower Bounds", bounded, 1.0, bounded ? 1.0 : 0.0);
    }

    // Test 14: Insufficient Scoring Data Identification
    {
        issue_pilot::ContributorProfile prof;
        issue_pilot::IssueRecord empty_issue;
        empty_issue.id = "empty-issue-all-missing";
        empty_issue.state = "open";
        empty_issue.issue_url = "https://github.com/example/repo/issues/999";
        empty_issue.skill_available = false;
        empty_issue.difficulty_evidence.available = false;
        empty_issue.responsiveness_evidence.available = false;
        empty_issue.health_evidence.available = false;
        empty_issue.freshness_evidence.available = false;
        prof.has_feedback_history = false;

        auto scores = engine.compute_scores(prof, empty_issue);
        bool insufficient = (scores.final_score == 0.0 && scores.explanation == "Insufficient scoring data available.");
        report_test("Insufficient Scoring Data Handling", insufficient, 1.0, insufficient ? 1.0 : 0.0);
    }

    // Test 15: Multi-Component Renormalization Sums to 100%
    {
        issue_pilot::ContributorProfile prof;
        prof.skills["Python"] = issue_pilot::ContributorSkill{"Python", 90.0, 1.0};
        prof.capability = 75.0;
        prof.has_feedback_history = false; // Feedback missing (0.10)

        issue_pilot::IssueRecord issue;
        issue.id = "multi-missing";
        issue.state = "open";
        issue.issue_url = "https://github.com/example/repo/issues/100";
        issue.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 1.0, 1.0});
        issue.responsiveness_evidence.available = false; // Responsiveness missing (0.15)
        issue.health_evidence.available = false;         // Health missing (0.10)
        // Available: Skill (0.35) + Difficulty (0.25) + Freshness (0.05) = 0.65

        auto s = engine.compute_scores(prof, issue);
        double eff_sum = s.effective_skill_weight + s.effective_diff_weight + s.effective_fresh_weight;
        report_test("Multi-Component Weight Renormalization Sum", std::abs(eff_sum - 1.0) < 0.001, 1.0, eff_sum);
        double expected_skill_eff = 0.35 / 0.65;
        report_test("Multi-Component Effective Skill Weight", std::abs(s.effective_skill_weight - expected_skill_eff) < 0.001, expected_skill_eff, s.effective_skill_weight);
    }

    // Test 16: Deterministic Tie-Breaking Order
    {
        issue_pilot::ContributorProfile prof;
        prof.capability = 70.0;

        // Two candidates with identical final scores, identical S, identical D, identical N, different issue numbers
        issue_pilot::IssueRecord i_a;
        i_a.id = "tie-issue-20";
        i_a.issue_number = 20;
        i_a.state = "open";
        i_a.issue_url = "https://github.com/example/repo/issues/20";
        i_a.responsiveness_evidence.available = false;
        i_a.health_evidence.available = false;
        i_a.freshness_evidence.available = false;
        i_a.difficulty_evidence.scope_change_size = 70.0;
        i_a.difficulty_evidence.technical_complexity = 70.0;
        i_a.difficulty_evidence.historical_effort = 70.0;
        i_a.difficulty_evidence.dependency_arch_impact = 70.0;
        i_a.difficulty_evidence.coordination_complexity = 70.0;

        issue_pilot::IssueRecord i_b = i_a;
        i_b.id = "tie-issue-10";
        i_b.issue_number = 10;
        i_b.issue_url = "https://github.com/example/repo/issues/10";

        std::vector<issue_pilot::IssueRecord> cands = {i_a, i_b};
        auto ranked = engine.rank_candidates(prof, cands);
        // Smaller issue number 10 should be ranked #1
        bool tie_ok = (ranked.size() == 2 && ranked[0].issue.issue_number == 10 && ranked[1].issue.issue_number == 20);
        report_test("Deterministic Tie-Breaking by Issue Number", tie_ok, 1.0, tie_ok ? 1.0 : 0.0);
    }

    // Test 17: Controlled Error Handling on Malformed JSON
    {
        issue_pilot::ContributorProfile p_dummy;
        issue_pilot::RankingWeights w_dummy;
        std::vector<issue_pilot::IssueRecord> cands_dummy;
        std::string err;
        bool parsed_invalid = issue_pilot::JsonAdapter::parse_request("{invalid_json_missing_quotes", p_dummy, w_dummy, cands_dummy, err);
        report_test("Controlled Error on Malformed JSON", !parsed_invalid, 1.0, parsed_invalid ? 0.0 : 1.0);
    }

    // Test 18: Deterministic Reproducibility
    {
        issue_pilot::ContributorProfile prof;
        prof.skills["Python"] = issue_pilot::ContributorSkill{"Python", 85.0, 1.0};
        prof.capability = 72.0;

        std::vector<issue_pilot::IssueRecord> cands;
        for (int i = 1; i <= 5; ++i) {
            issue_pilot::IssueRecord c;
            c.id = "repro-" + std::to_string(i);
            c.issue_number = i;
            c.state = "open";
            c.issue_url = "https://github.com/example/repo/issues/" + std::to_string(i);
            c.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 1.0, 1.0});
            c.difficulty_evidence.scope_change_size = 60.0 + i * 5;
            c.difficulty_evidence.technical_complexity = 60.0 + i * 5;
            c.difficulty_evidence.historical_effort = 60.0 + i * 5;
            c.difficulty_evidence.dependency_arch_impact = 60.0 + i * 5;
            c.difficulty_evidence.coordination_complexity = 60.0 + i * 5;
            cands.push_back(c);
        }

        auto first_pass = engine.rank_candidates(prof, cands);
        bool identical_all_runs = true;
        for (int run = 0; run < 10; ++run) {
            auto next_pass = engine.rank_candidates(prof, cands);
            for (size_t k = 0; k < first_pass.size(); ++k) {
                if (first_pass[k].issue.id != next_pass[k].issue.id ||
                    std::abs(first_pass[k].scores.final_score - next_pass[k].scores.final_score) > 0.00001) {
                    identical_all_runs = false;
                }
            }
        }
        report_test("Deterministic Ranking Reproducibility Across 10 Runs", identical_all_runs, 1.0, identical_all_runs ? 1.0 : 0.0);
    }

    // =========================================================================
    // PHASE 1: DATA CONTRACTS AND CANONICAL EVIDENCE MODEL TESTS
    // =========================================================================
    {
        // Test 19: Canonical Four-Part Issue Key Construction & Parsing
        std::string key = issue_pilot::build_issue_key("gitlab", "gitlab.example.org", "org/repo", "101");
        report_test("Issue Key Builder Output", key == "gitlab|gitlab.example.org|org/repo|101", 1.0, key == "gitlab|gitlab.example.org|org/repo|101" ? 1.0 : 0.0);

        std::string s, inst, repo, nat;
        bool parse_ok = issue_pilot::parse_issue_key(key, s, inst, repo, nat);
        bool parse_match = parse_ok && s == "gitlab" && inst == "gitlab.example.org" && repo == "org/repo" && nat == "101";
        report_test("Issue Key Parser Exact Match", parse_match, 1.0, parse_match ? 1.0 : 0.0);

        // Invalid key segments
        std::string s_bad, inst_bad, repo_bad, nat_bad;
        bool parse_3_parts = issue_pilot::parse_issue_key("github|api.github.com|owner/repo", s_bad, inst_bad, repo_bad, nat_bad);
        report_test("Reject 3-Part Issue Key", !parse_3_parts, 1.0, !parse_3_parts ? 1.0 : 0.0);

        bool parse_empty_segment = issue_pilot::parse_issue_key("github||owner/repo|1", s_bad, inst_bad, repo_bad, nat_bad);
        report_test("Reject Empty Segment in Issue Key", !parse_empty_segment, 1.0, !parse_empty_segment ? 1.0 : 0.0);

        // Test 20: Evidence<T> Known-Zero vs Unavailable Semantics & Confidence Rejection
        auto ev_zero = issue_pilot::Evidence<double>::known_zero(0.85, "github", "api.github.com", "direct_count", 0.0);
        bool zero_sem_ok = ev_zero.available && ev_zero.value == 0.0 && std::abs(ev_zero.confidence - 0.85) < 0.0001;
        report_test("Evidence Known-Zero Semantics", zero_sem_ok, 1.0, zero_sem_ok ? 1.0 : 0.0);

        auto ev_unavail = issue_pilot::Evidence<double>::unavailable("endpoint unsupported", "bugzilla", "bugzilla.mozilla.org");
        bool unavail_sem_ok = (!ev_unavail.available) && ev_unavail.confidence == 0.0 && ev_unavail.approximation_reason == "endpoint unsupported";
        report_test("Evidence Unavailable Semantics", unavail_sem_ok, 1.0, unavail_sem_ok ? 1.0 : 0.0);

        bool threw_on_invalid_conf = false;
        try {
            issue_pilot::validate_confidence(1.25);
        } catch (const std::invalid_argument&) {
            threw_on_invalid_conf = true;
        }
        report_test("Strict Rejection of Confidence > 1.0", threw_on_invalid_conf, 1.0, threw_on_invalid_conf ? 1.0 : 0.0);

        bool threw_on_negative_conf = false;
        try {
            issue_pilot::validate_confidence(-0.10);
        } catch (const std::invalid_argument&) {
            threw_on_negative_conf = true;
        }
        report_test("Strict Rejection of Confidence < 0.0", threw_on_negative_conf, 1.0, threw_on_negative_conf ? 1.0 : 0.0);

        // Case insensitivity, whitespace trimming, and delimiter injection in issue_key
        std::string trimmed_built = issue_pilot::build_issue_key("  GitHub  ", " api.github.com ", " owner/repo ", " 42 ");
        report_test("Issue Key Trimming & Case Normalization", trimmed_built == "github|api.github.com|owner/repo|42", 1.0, trimmed_built == "github|api.github.com|owner/repo|42" ? 1.0 : 0.0);

        bool threw_on_delimiter_injection = false;
        try {
            issue_pilot::build_issue_key("git|hub", "api.github.com", "repo", "1");
        } catch (const std::invalid_argument&) {
            threw_on_delimiter_injection = true;
        }
        report_test("Reject Delimiter Injection in Issue Key", threw_on_delimiter_injection, 1.0, threw_on_delimiter_injection ? 1.0 : 0.0);

        // Test 21: Schema and Algorithm Version Constants
        bool versions_ok = (std::string(issue_pilot::versions::ALGORITHM_VERSION) == "2.3.0") &&
                           (std::string(issue_pilot::versions::FEATURE_SCHEMA_VERSION) == "2.3.0");
        report_test("Algorithm & Feature Schema Versioning", versions_ok, 1.0, versions_ok ? 1.0 : 0.0);

        // Test 22: JSON Round-Trip with Issue Key
        std::string test_json = R"({
            "profile": {"user_id": "u1", "capability": 70.0, "skills": {"Python": 80.0}},
            "candidates": [{
                "id": "c1",
                "issue_key": "codeberg|codeberg.org|forge/repo|55",
                "source": "codeberg",
                "source_instance": "codeberg.org",
                "canonical_repository_key": "forge/repo",
                "native_id": "55",
                "issue_number": 55,
                "state": "open",
                "issue_url": "https://codeberg.org/forge/repo/issues/55",
                "technologies": [{"technology": "Python", "raw_relevance": 1.0}]
            }]
        })";
        issue_pilot::ContributorProfile parsed_prof;
        issue_pilot::RankingWeights parsed_w;
        std::vector<issue_pilot::IssueRecord> parsed_cands;
        std::string err;
        bool parsed = issue_pilot::JsonAdapter::parse_request(test_json, parsed_prof, parsed_w, parsed_cands, err);
        bool cand_parsed_ok = parsed && parsed_cands.size() == 1 &&
                              parsed_cands[0].get_issue_key() == "codeberg|codeberg.org|forge/repo|55" &&
                              parsed_cands[0].source == "codeberg" &&
                              parsed_cands[0].source_instance == "codeberg.org";
        report_test("JsonAdapter Parse Issue Key & Source", cand_parsed_ok, 1.0, cand_parsed_ok ? 1.0 : 0.0);

        // Test 23: Unicode NFC Parity (Composed vs Decomposed & Non-ASCII)
        std::string decomposed_cafe = "cafe\xCC\x81"; // 'cafe' + U+0301 combining acute accent
        std::string nfc_cafe = issue_pilot::unicode::to_nfc(decomposed_cafe);
        std::string expected_cafe = "caf\xC3\xA9"; // U+00E9
        report_test("Unicode NFC Decomposition to Composition (café)", nfc_cafe == expected_cafe, 1.0, (nfc_cafe == expected_cafe ? 1.0 : 0.0));

        std::string decomposed_naive = "nai\xCC\x88ve"; // 'nai' + U+0308 combining diaeresis + 've'
        std::string nfc_naive = issue_pilot::unicode::to_nfc(decomposed_naive);
        std::string expected_naive = "na\xC3\xAFve"; // U+00EF
        report_test("Unicode NFC Decomposition to Composition (naïve)", nfc_naive == expected_naive, 1.0, (nfc_naive == expected_naive ? 1.0 : 0.0));

        // Preserve case of instance and repository segments without silent fold
        std::string key_with_non_ascii = issue_pilot::build_issue_key(
            "GitLab",
            "GitLab.M\xC3\xBCnchen.org",
            "MyOrg/Caf\xC3\xA9-Project",
            "101"
        );
        std::string expected_key = "gitlab|GitLab.M\xC3\xBCnchen.org|MyOrg/Caf\xC3\xA9-Project|101";
        report_test("Issue Key Preserves Instance & Repo Case with Non-ASCII", key_with_non_ascii == expected_key, 1.0, (key_with_non_ascii == expected_key ? 1.0 : 0.0));

        // Test 24: Golden JSON Fixtures Loaded from Disk
        std::vector<std::string> fixture_paths = {
            "tests/fixtures/golden_evidence_v23_fixtures.json",
            "ranking_engine/tests/fixtures/golden_evidence_v23_fixtures.json",
            "../ranking_engine/tests/fixtures/golden_evidence_v23_fixtures.json",
            "../../ranking_engine/tests/fixtures/golden_evidence_v23_fixtures.json"
        };
        std::string fixture_content;
        for (const auto& p : fixture_paths) {
            std::ifstream f(p);
            if (f.is_open()) {
                std::stringstream ss;
                ss << f.rdbuf();
                fixture_content = ss.str();
                break;
            }
        }
        report_test("Golden JSON Fixture File Opened from Disk", !fixture_content.empty(), 1.0, !fixture_content.empty() ? 1.0 : 0.0);

        bool parse_err = false;
        size_t parse_pos = 0;
        auto json_root = issue_pilot::SimpleJsonParser::parse(fixture_content, parse_pos, parse_err);
        bool root_valid = (!parse_err) && json_root.has("golden_fixtures");
        report_test("Golden JSON Fixture Root Valid Object", root_valid, 1.0, root_valid ? 1.0 : 0.0);

        if (root_valid) {
            const auto& gf = json_root.get("golden_fixtures");

            // 1. Known Zero
            bool kz_ok = gf.has("known_zero") &&
                         gf.get("known_zero").get("available").as_bool(false) &&
                         gf.get("known_zero").get("value").as_double(-1.0) == 0.0 &&
                         std::abs(gf.get("known_zero").get("confidence").as_double() - 0.90) < 0.001 &&
                         gf.get("known_zero").get("pagination_complete").as_bool(false);
            report_test("C++ Golden Fixture 1: Known Zero Verified", kz_ok, 1.0, kz_ok ? 1.0 : 0.0);

            // 2. Unavailable Unsupported Signal
            bool unavail_unsupported_ok = gf.has("unavailable_unsupported_signal") &&
                                         (!gf.get("unavailable_unsupported_signal").get("available").as_bool(true)) &&
                                         gf.get("unavailable_unsupported_signal").get("value").type == issue_pilot::JsonValue::V_NULL &&
                                         gf.get("unavailable_unsupported_signal").get("confidence").as_double() == 0.0 &&
                                         gf.get("unavailable_unsupported_signal").get("schema_version").as_str() == "2.3.0";
            report_test("C++ Golden Fixture 2: Unsupported Unavailable Verified", unavail_unsupported_ok, 1.0, unavail_unsupported_ok ? 1.0 : 0.0);

            // 3. Unavailable Incomplete Pagination
            bool unavail_inc_ok = gf.has("unavailable_incomplete_pagination") &&
                                  (!gf.get("unavailable_incomplete_pagination").get("available").as_bool(true)) &&
                                  gf.get("unavailable_incomplete_pagination").get("value").type == issue_pilot::JsonValue::V_NULL &&
                                  (!gf.get("unavailable_incomplete_pagination").get("pagination_complete").as_bool(true));
            report_test("C++ Golden Fixture 3: Incomplete Pagination Unavailable Verified", unavail_inc_ok, 1.0, unavail_inc_ok ? 1.0 : 0.0);

            // 4. Measured Positive
            bool mp_ok = gf.has("measured_positive") &&
                         gf.get("measured_positive").get("available").as_bool(false) &&
                         gf.get("measured_positive").get("value").as_double() == 85.0 &&
                         std::abs(gf.get("measured_positive").get("confidence").as_double() - 0.81) < 0.001 &&
                         gf.get("measured_positive").get("schema_version").as_str() == "2.3.0";
            report_test("C++ Golden Fixture 4: Measured Positive Verified", mp_ok, 1.0, mp_ok ? 1.0 : 0.0);

            // 5. Structured Difficulty
            bool sd_ok = gf.has("structured_difficulty") &&
                         gf.get("structured_difficulty").get("available").as_bool(false) &&
                         gf.get("structured_difficulty").get("value").get("scope_change_size").as_double() == 25.0 &&
                         gf.get("structured_difficulty").get("value").get("technical_complexity").as_double() == 50.0 &&
                         gf.get("structured_difficulty").get("value").get("dependency_arch_impact").as_double() == 25.0 &&
                         gf.get("structured_difficulty").get("value").get("coordination_complexity").as_double() == 25.0 &&
                         !gf.get("structured_difficulty").get("value").has("historical_effort");
            report_test("C++ Golden Fixture 5: Structured Difficulty (No HE) Verified", sd_ok, 1.0, sd_ok ? 1.0 : 0.0);
        }
    }

    std::cout << "\nALL UNIT TEST CHECKS (INCLUDING PHASE 1 CONTRACTS) PASSED SUCCESSFULLY!\n";
    std::cout << "Executed: " << total_assertions << " | Passed: " << passed_assertions << " | Failed: " << (total_assertions - passed_assertions) << "\n";
    std::cout << "========================================\n";
    return (total_assertions == passed_assertions) ? 0 : 1;
}
