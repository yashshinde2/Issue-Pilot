#include "../include/ranking_engine.hpp"
#include "../include/json_adapter.hpp"
#include "../include/technology_registry.hpp"
#include "../include/contribution_aggregator.hpp"
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

        // Ångström canonical equivalence
        std::string decomposed_angstrom = "A\xCC\x8Angstr\xC3\xB6m"; // A + ring above + ngstr + o with diaeresis + m
        std::string composed_angstrom = "\xC3\x85ngstr\xC3\xB6m";
        std::string nfc_angstrom = issue_pilot::unicode::to_nfc(decomposed_angstrom);
        report_test("Unicode NFC Ångström Decomposition Parity", nfc_angstrom == composed_angstrom, 1.0, (nfc_angstrom == composed_angstrom ? 1.0 : 0.0));

        // Composition exclusions: Angstrom sign (U+212B) and Ohm sign (U+2126)
        std::string angstrom_sign = "\xE2\x84\xAB";
        std::string nfc_angstrom_sign = issue_pilot::unicode::to_nfc(angstrom_sign);
        std::string expected_angstrom_composed = "\xC3\x85"; // U+00C5
        report_test("Unicode NFC Composition Exclusion: Angstrom Sign (U+212B -> U+00C5)", nfc_angstrom_sign == expected_angstrom_composed, 1.0, (nfc_angstrom_sign == expected_angstrom_composed ? 1.0 : 0.0));

        std::string ohm_sign = "\xE2\x84\xA6";
        std::string nfc_ohm_sign = issue_pilot::unicode::to_nfc(ohm_sign);
        std::string expected_ohm_composed = "\xCE\xA9"; // U+03A9
        report_test("Unicode NFC Composition Exclusion: Ohm Sign (U+2126 -> U+03A9)", nfc_ohm_sign == expected_ohm_composed, 1.0, (nfc_ohm_sign == expected_ohm_composed ? 1.0 : 0.0));

        // Combining-class reordering: stacked diacritics
        // q + U+0301 (acute, ccc=230) + U+0323 (dot below, ccc=220) vs q + U+0323 + U+0301
        std::string stacked1 = "q\xCC\x81\xCC\xA3";
        std::string stacked2 = "q\xCC\xA3\xCC\x81";
        std::string nfc_stacked1 = issue_pilot::unicode::to_nfc(stacked1);
        std::string nfc_stacked2 = issue_pilot::unicode::to_nfc(stacked2);
        report_test("Unicode NFC Combining Class Canonical Reordering", nfc_stacked1 == nfc_stacked2 && nfc_stacked1 == stacked2, 1.0, (nfc_stacked1 == nfc_stacked2 && nfc_stacked1 == stacked2 ? 1.0 : 0.0));

        // Hangul algorithmic composition/decomposition
        // 가 (U+AC00) = \u1100 + \u1161
        std::string decomposed_ga = "\xE1\x84\x80\xE1\x85\xA1";
        std::string composed_ga = "\xEA\xB0\x80";
        report_test("Unicode NFC Hangul LV Composition (가)", issue_pilot::unicode::to_nfc(decomposed_ga) == composed_ga, 1.0, (issue_pilot::unicode::to_nfc(decomposed_ga) == composed_ga ? 1.0 : 0.0));

        // 간 (U+AC04) = \u1100 + \u1161 + \u11AB
        std::string decomposed_gan = "\xE1\x84\x80\xE1\x85\xA1\xE1\x86\xAB";
        std::string composed_gan = "\xEA\xB0\x84";
        report_test("Unicode NFC Hangul LVT Composition (간)", issue_pilot::unicode::to_nfc(decomposed_gan) == composed_gan, 1.0, (issue_pilot::unicode::to_nfc(decomposed_gan) == composed_gan ? 1.0 : 0.0));

        // 한 (U+D55C) = \u1112 + \u1161 + \u11AB
        std::string decomposed_han = "\xE1\x84\x92\xE1\x85\xA1\xE1\x86\xAB";
        std::string composed_han = "\xED\x95\x9C";
        report_test("Unicode NFC Hangul LVT Composition (한)", issue_pilot::unicode::to_nfc(decomposed_han) == composed_han, 1.0, (issue_pilot::unicode::to_nfc(decomposed_han) == composed_han ? 1.0 : 0.0));

        // Supplementary characters (SMP): Math bold A (U+1D400) and Emoji 😀 (U+1F600)
        std::string math_bold_a = "\xF0\x9D\x90\x80";
        report_test("Unicode NFC Supplementary Character (U+1D400)", issue_pilot::unicode::to_nfc(math_bold_a) == math_bold_a, 1.0, (issue_pilot::unicode::to_nfc(math_bold_a) == math_bold_a ? 1.0 : 0.0));

        std::string emoji_face = "\xF0\x9F\x98\x80";
        report_test("Unicode NFC Supplementary Emoji (U+1F600)", issue_pilot::unicode::to_nfc(emoji_face) == emoji_face, 1.0, (issue_pilot::unicode::to_nfc(emoji_face) == emoji_face ? 1.0 : 0.0));

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

        // Test 25: Repository Health V2 Mathematical Contract (Weights: 0.30 C90, 0.25 PR30, 0.25 AU90, 0.20 MA90)
        issue_pilot::RepositoryHealthEvidenceV2 h_full;
        h_full.c90_score = 100.0;
        h_full.pr30_score = 80.0;
        h_full.au90_score = 60.0;
        h_full.ma90_score = 40.0;
        // 0.30*100 + 0.25*80 + 0.25*60 + 0.20*40 = 30 + 20 + 15 + 8 = 73.0
        double h_full_val = h_full.compute_health();
        report_test("Repository Health V2 Full Weights Exact (73.0)", std::abs(h_full_val - 73.0) < 1e-6, 1.0, std::abs(h_full_val - 73.0) < 1e-6 ? 1.0 : 0.0);

        // Missing sub-signal renormalization: e.g. ma90 unavailable
        issue_pilot::RepositoryHealthEvidenceV2 h_missing_ma;
        h_missing_ma.c90_score = 100.0;
        h_missing_ma.pr30_score = 80.0;
        h_missing_ma.au90_score = 60.0;
        h_missing_ma.ma90_available = false;
        // Remaining weights: 0.30 + 0.25 + 0.25 = 0.80. Weighted sum: 65.0. 65.0 / 0.80 = 81.25.
        double h_renorm_val = h_missing_ma.compute_health();
        report_test("Repository Health V2 Missing Sub-signal Renormalization (81.25)", std::abs(h_renorm_val - 81.25) < 1e-6, 1.0, std::abs(h_renorm_val - 81.25) < 1e-6 ? 1.0 : 0.0);

        // Engine integration test: H evidence v2 feeds compute_scores correctly
        issue_pilot::RankingEngine engine_h_test;
        issue_pilot::IssueRecord cand_h_test;
        cand_h_test.id = "test_repo_health";
        cand_h_test.issue_url = "https://github.com/test/repo/issues/1";
        cand_h_test.health_evidence_v2.available = true;
        cand_h_test.health_evidence_v2.value = h_full;
        cand_h_test.health_evidence_v2.confidence = 0.90;
        cand_h_test.difficulty_evidence.available = true;
        cand_h_test.responsiveness_evidence.available = true;
        cand_h_test.freshness_evidence.available = true;

        issue_pilot::ContributorProfile profile_h_test;
        profile_h_test.user_id = "contrib_1";
        profile_h_test.completed_count = 5;

        auto comp_scores = engine_h_test.compute_scores(profile_h_test, cand_h_test);
        report_test("Engine compute_scores uses calculate_repository_health_v2 (73.0)", std::abs(comp_scores.repository_health - 73.0) < 1e-6, 1.0, std::abs(comp_scores.repository_health - 73.0) < 1e-6 ? 1.0 : 0.0);
    }

    // =========================================================================
    // PHASE 3: CONTRIBUTOR SKILL AND TECHNOLOGY EVIDENCE VERIFICATION
    // =========================================================================
    std::cout << "\n--- Phase 3: Contributor Skill & Technology Evidence Tests ---\n";

    // Test 26: Golden Skill Fixtures Execution
    {
        std::string skill_fix_path = "ranking_engine/tests/fixtures/golden_skill_v23_fixtures.json";
        std::ifstream f(skill_fix_path);
        if (!f.is_open()) {
            skill_fix_path = "tests/fixtures/golden_skill_v23_fixtures.json";
            f.open(skill_fix_path);
        }
        if (!f.is_open()) {
            skill_fix_path = "../tests/fixtures/golden_skill_v23_fixtures.json";
            f.open(skill_fix_path);
        }
        report_test("Golden Skill JSON Fixture Opened", f.is_open(), 1.0, f.is_open() ? 1.0 : 0.0);

        std::string content((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        bool err = false;
        size_t pos = 0;
        auto root = issue_pilot::SimpleJsonParser::parse(content, pos, err);
        report_test("Golden Skill JSON Valid AST", !err && root.has("fixtures"), 1.0, (!err && root.has("fixtures")) ? 1.0 : 0.0);

        // Case 1: Pure observed skill (sat curves, nominal weights, n_eff = 44)
        {
            issue_pilot::ContributorSkillEvidenceV2 ev1;
            ev1.technology = "Python";
            ev1.unique_contributed_repositories = 2;
            ev1.repo_available = true;
            ev1.standalone_commits = 40;
            ev1.standalone_commits_available = true;
            ev1.unique_authored_prs = 4;
            ev1.pr_volume_available = true;
            ev1.decided_prs = 8;
            ev1.merged_decided_prs = 6;
            ev1.merge_rate_available = true;
            ev1.months_since_last_activity = 6.0;
            ev1.recency_available = true;

            auto res1 = ev1.compute_skill();
            report_test("Golden Skill 1: Available", res1.available, 1.0, res1.available ? 1.0 : 0.0);
            report_test("Golden Skill 1: Score (~50.7388)", std::abs(res1.score - 50.738774) < 1e-4, 50.738774, res1.score);
            report_test("Golden Skill 1: Confidence (1.0)", std::abs(res1.confidence - 1.0) < 1e-6, 1.0, res1.confidence);
            report_test("Golden Skill 1: Effective Episodes (44)", res1.effective_episodes == 44, 44, res1.effective_episodes);
            report_test("Golden Skill 1: Source (observed)", res1.source == "observed", 1.0, res1.source == "observed" ? 1.0 : 0.0);
        }

        // Case 2: Missing merge rate (decided_prs = 3 < 5 -> weight renormalize over 0.70)
        {
            issue_pilot::ContributorSkillEvidenceV2 ev2;
            ev2.technology = "Java";
            ev2.unique_contributed_repositories = 4;
            ev2.repo_available = true;
            ev2.standalone_commits = 80;
            ev2.standalone_commits_available = true;
            ev2.unique_authored_prs = 8;
            ev2.pr_volume_available = true;
            ev2.decided_prs = 3;
            ev2.merged_decided_prs = 2;
            ev2.merge_rate_available = false;
            ev2.months_since_last_activity = 12.0;
            ev2.recency_available = true;

            auto res2 = ev2.compute_skill();
            report_test("Golden Skill 2: Available", res2.available, 1.0, res2.available ? 1.0 : 0.0);
            report_test("Golden Skill 2: Score (~57.5497)", std::abs(res2.score - 57.549746) < 1e-4, 57.549746, res2.score);
            report_test("Golden Skill 2: Coverage A_t (0.70)", std::abs(res2.nominal_coverage_at - 0.70) < 1e-6, 0.70, res2.nominal_coverage_at);
        }

        // Case 3: Insufficient coverage rejection (A_t = 0.15 < 0.40)
        {
            issue_pilot::ContributorSkillEvidenceV2 ev3;
            ev3.technology = "Rust";
            ev3.unique_contributed_repositories = 2;
            ev3.repo_available = true;
            auto res3 = ev3.compute_skill();
            report_test("Golden Skill 3: Unavailable when A_t < 0.40", !res3.available, 1.0, !res3.available ? 1.0 : 0.0);
        }

        // Case 4: Pure questionnaire (intermediate -> 60.0, conf = 0.40, source = self_reported)
        {
            issue_pilot::ContributorSkillEvidenceV2 ev4;
            ev4.technology = "Go";
            ev4.has_questionnaire = true;
            ev4.questionnaire_level = "intermediate";
            auto res4 = ev4.compute_skill();
            report_test("Golden Skill 4: Available via questionnaire", res4.available, 1.0, res4.available ? 1.0 : 0.0);
            report_test("Golden Skill 4: Score (60.0)", std::abs(res4.score - 60.0) < 1e-6, 60.0, res4.score);
            report_test("Golden Skill 4: Confidence (0.40)", std::abs(res4.confidence - 0.40) < 1e-6, 0.40, res4.confidence);
            report_test("Golden Skill 4: Source (self_reported)", res4.source == "self_reported", 1.0, res4.source == "self_reported" ? 1.0 : 0.0);
        }

        // Case 5: Blended skill (capped at 20% weight)
        {
            issue_pilot::ContributorSkillEvidenceV2 ev5;
            ev5.technology = "Python";
            ev5.unique_contributed_repositories = 2;
            ev5.repo_available = true;
            ev5.standalone_commits = 40;
            ev5.standalone_commits_available = true;
            ev5.unique_authored_prs = 4;
            ev5.pr_volume_available = true;
            ev5.decided_prs = 8;
            ev5.merged_decided_prs = 6;
            ev5.merge_rate_available = true;
            ev5.months_since_last_activity = 6.0;
            ev5.recency_available = true;
            ev5.has_questionnaire = true;
            ev5.questionnaire_level = "advanced";
            ev5.questionnaire_score = 75.0;

            auto res5 = ev5.compute_skill();
            report_test("Golden Skill 5: Score (~55.5910)", std::abs(res5.score - 55.591019) < 1e-4, 55.591019, res5.score);
            report_test("Golden Skill 5: Confidence (0.88)", std::abs(res5.confidence - 0.88) < 1e-4, 0.88, res5.confidence);
            report_test("Golden Skill 5: Source (blended)", res5.source == "blended", 1.0, res5.source == "blended" ? 1.0 : 0.0);
        }

        // Case 6: Multi-technology candidate with >= 0.50 coverage
        {
            issue_pilot::RankingEngine engine_multi;
            issue_pilot::ContributorProfile prof6;
            prof6.skills["Python"] = issue_pilot::ContributorSkill{"Python", 55.591019, 0.88};

            issue_pilot::IssueRecord issue6;
            issue6.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 3.0, 0.75});
            issue6.technologies.push_back(issue_pilot::TechnologyRelevance{"JavaScript", 1.0, 0.25});

            double s_out = 0.0, c_out = 0.0, cov_out = 0.0;
            bool ok6 = engine_multi.calculate_skill_match_v2(prof6, issue6, s_out, c_out, cov_out);
            report_test("Golden Skill 6: Coverage >= 0.50 Passes", ok6, 1.0, ok6 ? 1.0 : 0.0);
            report_test("Golden Skill 6: Skill S (~55.5910)", std::abs(s_out - 55.591019) < 1e-4, 55.591019, s_out);
            report_test("Golden Skill 6: Confidence C_S (0.66)", std::abs(c_out - 0.66) < 1e-4, 0.66, c_out);
            report_test("Golden Skill 6: Coverage (0.75)", std::abs(cov_out - 0.75) < 1e-4, 0.75, cov_out);
        }

        // Case 7: Multi-technology candidate with < 0.50 coverage rejected
        {
            issue_pilot::RankingEngine engine_multi;
            issue_pilot::ContributorProfile prof7;
            prof7.skills["JavaScript"] = issue_pilot::ContributorSkill{"JavaScript", 80.0, 0.90};

            issue_pilot::IssueRecord issue7;
            issue7.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 3.0, 0.75});
            issue7.technologies.push_back(issue_pilot::TechnologyRelevance{"JavaScript", 1.0, 0.25});

            double s_out = 0.0, c_out = 0.0, cov_out = 0.0;
            bool ok7 = engine_multi.calculate_skill_match_v2(prof7, issue7, s_out, c_out, cov_out);
            report_test("Golden Skill 7: Coverage < 0.50 Fails", !ok7, 1.0, !ok7 ? 1.0 : 0.0);
            report_test("Golden Skill 7: Coverage Measured (0.25)", std::abs(cov_out - 0.25) < 1e-4, 0.25, cov_out);
        }
    }

    // Test 27: Short Alias Context-Aware Detection (Section 5.1)
    {
        // "we must go to the database" -> NOT Go
        auto d1 = issue_pilot::TechnologyRegistry::detect("", "we must go to the database", "there is an issue when we go ahead", {}, {});
        bool found_go_false = false;
        for (const auto& t : d1.technologies) {
            if (t.technology == "Go") found_go_false = true;
        }
        report_test("Context-aware detection: English 'go' rejected", !found_go_false, 1.0, !found_go_false ? 1.0 : 0.0);

        // "refactor server in Go using goroutines" -> Go detected
        auto d2 = issue_pilot::TechnologyRegistry::detect("", "refactor server in Go", "rewritten with goroutines", {}, {});
        bool found_go_true = false;
        for (const auto& t : d2.technologies) {
            if (t.technology == "Go") found_go_true = true;
        }
        report_test("Context-aware detection: 'in Go' and 'goroutines' accepted", found_go_true, 1.0, found_go_true ? 1.0 : 0.0);

        // "fixed segmentation fault in main.c" -> C detected via .c extension
        auto d3 = issue_pilot::TechnologyRegistry::detect("", "fixed segmentation fault", "crash occurs in main.c line 42", {}, {});
        bool found_c_true = false;
        for (const auto& t : d3.technologies) {
            if (t.technology == "C") found_c_true = true;
        }
        report_test("Context-aware detection: 'main.c' maps to C", found_c_true, 1.0, found_c_true ? 1.0 : 0.0);
    }

    // Test 28: Contribution Episode Deduplication (Section 5.2)
    {
        std::vector<issue_pilot::ContributionPr> prs;
        prs.push_back({"pr-1", "octocat/hello-world", "Python", true, true, false, false, 10.0});
        prs.push_back({"pr-1", "octocat/hello-world", "Python", true, true, false, false, 10.0}); // duplicate PR page
        prs.push_back({"pr-bot", "octocat/hello-world", "Python", true, true, true, false, 5.0});  // bot PR

        std::vector<issue_pilot::ContributionCommit> commits;
        commits.push_back({"sha-1", "octocat/hello-world", "Python", "pr-1", false, false, 10.0}); // linked to pr-1
        commits.push_back({"sha-2", "octocat/hello-world", "Python", "pr-1", false, false, 10.0}); // linked to pr-1
        commits.push_back({"sha-3", "octocat/hello-world", "Python", "", false, false, 12.0});     // standalone
        commits.push_back({"sha-fork", "fork/hello-world", "Python", "", false, true, 8.0});        // fork commit
        commits.push_back({"sha-bot", "octocat/hello-world", "Python", "", true, false, 8.0});       // bot commit

        std::vector<issue_pilot::QuestionnaireReport> q_reports;
        auto agg = issue_pilot::ContributionEpisodeAggregator::aggregate(prs, commits, q_reports);

        report_test("Aggregator: Python present", agg.find("Python") != agg.end(), 1.0, agg.find("Python") != agg.end() ? 1.0 : 0.0);
        if (agg.find("Python") != agg.end()) {
            const auto& py = agg["Python"];
            report_test("Aggregator: Unique Repositories (1)", py.unique_contributed_repositories == 1, 1.0, py.unique_contributed_repositories);
            report_test("Aggregator: Standalone Commits (1)", py.standalone_commits == 1, 1.0, py.standalone_commits);
            report_test("Aggregator: Unique Authored PRs (1)", py.unique_authored_prs == 1, 1.0, py.unique_authored_prs);
            auto res_agg = py.compute_skill();
            report_test("Aggregator: Deduplicated Effective Episodes n_eff (2)", res_agg.effective_episodes == 2, 2.0, res_agg.effective_episodes);
        }
    }

    // Test 29: Contribution Deduplication 6 Edge Cases (PR-commit linking, multi-repo IDs, pagination, bots, forks)
    {
        std::vector<issue_pilot::ContributionPr> prs;
        // 1. Multiple commits in one PR -> tested together below
        // 4. Duplicate PRs across pagination pages count once
        prs.push_back({"42", "orgA/repoA", "Go", true, true, false, false, 0.0});
        prs.push_back({"42", "orgA/repoA", "Go", true, true, false, false, 0.0});
        // 5. Identical native PR IDs in different repositories remain distinct (Repo A #42 vs Repo B #42)
        prs.push_back({"42", "orgB/repoB", "Go", false, true, false, false, 0.0});
        // 6. Bots and forks are excluded
        prs.push_back({"99", "orgA/repoA", "Go", true, true, true, false, 0.0});
        prs.push_back({"10", "fork/repoC", "Go", true, true, false, true, 0.0});

        std::vector<issue_pilot::ContributionCommit> commits;
        // 1. Multiple commits in one PR (c1, c2, c3 in Repo A PR #42)
        // 2. PR-linked commits do not count as standalone commits
        // 3. A commit linked to multiple PR records or duplicate commit records is not duplicated
        commits.push_back({"c1", "orgA/repoA", "Go", "42", false, false, 0.0});
        commits.push_back({"c2", "orgA/repoA", "Go", "42", false, false, 0.0});
        commits.push_back({"c3", "orgA/repoA", "Go", "42", false, false, 0.0});
        commits.push_back({"c1", "orgA/repoA", "Go", "42", false, false, 0.0});
        // PR-linked commit for Repo B PR #42
        commits.push_back({"c4", "orgB/repoB", "Go", "42", false, false, 0.0});
        // Standalone human commit in Repo A
        commits.push_back({"c5", "orgA/repoA", "Go", "", false, false, 0.0});
        // 6. Bot commit & fork commit excluded
        commits.push_back({"c6", "orgA/repoA", "Go", "", true, false, 0.0});
        commits.push_back({"c7", "fork/repoC", "Go", "", false, true, 0.0});

        std::vector<issue_pilot::QuestionnaireReport> q_reports;
        auto agg = issue_pilot::ContributionEpisodeAggregator::aggregate(prs, commits, q_reports);

        report_test("Deduplication 6 Cases: Go present", agg.find("Go") != agg.end(), 1.0, agg.find("Go") != agg.end() ? 1.0 : 0.0);
        if (agg.find("Go") != agg.end()) {
            const auto& go = agg["Go"];
            report_test("Deduplication 6 Cases: Unique Repositories (2)", go.unique_contributed_repositories == 2, 2.0, go.unique_contributed_repositories);
            report_test("Deduplication 6 Cases: Unique Authored PRs (2)", go.unique_authored_prs == 2, 2.0, go.unique_authored_prs);
            report_test("Deduplication 6 Cases: Standalone Commits (1)", go.standalone_commits == 1, 1.0, go.standalone_commits);
            auto res = go.compute_skill();
            report_test("Deduplication 6 Cases: Effective Episodes n_eff (3)", res.effective_episodes == 3, 3.0, res.effective_episodes);
            report_test("Deduplication 6 Cases: Decided PRs (2)", go.decided_prs == 2, 2.0, go.decided_prs);
            report_test("Deduplication 6 Cases: Merged Decided PRs (1)", go.merged_decided_prs == 1, 1.0, go.merged_decided_prs);
        }
    }

    // Test 30: Recency Evaluations and Half-Life Comparison (0, 6, 12, 24, 36 months)
    {
        double months[] = {0.0, 6.0, 12.0, 24.0, 36.0};
        double exp_expected[] = {100.0, 60.653066, 36.787944, 13.533528, 4.978707};
        double hl_expected[] = {100.0, 70.710678, 50.0, 25.0, 12.5};

        bool all_exp_ok = true;
        bool all_hl_ok = true;
        for (int i = 0; i < 5; ++i) {
            double v_exp = issue_pilot::ContributorSkillEvidenceV2::recency_decay(months[i]);
            double v_hl = issue_pilot::ContributorSkillEvidenceV2::recency_half_life(months[i]);
            if (std::abs(v_exp - exp_expected[i]) > 1e-4) all_exp_ok = false;
            if (std::abs(v_hl - hl_expected[i]) > 1e-4) all_hl_ok = false;
        }
        report_test("Recency Decay Formula 100*exp(-m/12) (0..36m)", all_exp_ok, 1.0, all_exp_ok ? 1.0 : 0.0);
        report_test("Recency Half-Life Formula 100*0.5^(m/12) (0..36m)", all_hl_ok, 1.0, all_hl_ok ? 1.0 : 0.0);
        report_test("Recency Half-Life at 12m is exactly 50.0", std::abs(issue_pilot::ContributorSkillEvidenceV2::recency_half_life(12.0) - 50.0) < 1e-6, 50.0, issue_pilot::ContributorSkillEvidenceV2::recency_half_life(12.0));
    }

    // Test 31: Questionnaire Blending Weight Invariant Across c_obs in [0, 1.0]
    {
        double q_conf = 0.40;
        double c_obs_vals[] = {0.0, 0.10, 0.40, 1.00};
        bool all_qw_ok = true;
        for (int i = 0; i < 4; ++i) {
            double c = c_obs_vals[i];
            double ratio = q_conf / (c + q_conf);
            double qw = std::min(0.20, ratio);
            if (std::abs(qw - 0.20) > 1e-9) all_qw_ok = false;
        }
        report_test("Questionnaire Blending Weight q_w == 0.20 identically on [0, 1.0]", all_qw_ok, 1.0, all_qw_ok ? 1.0 : 0.0);
    }

    // Test 32: Technology Detection Ambiguous Alias Edge Cases (Go, C, R, JS, TS)
    {
        // Go
        auto d_go_eng = issue_pilot::TechnologyRegistry::detect("", "we must go to the database now", "let us go ahead with migration", {}, {});
        bool go_eng = false;
        for (const auto& t : d_go_eng.technologies) if (t.technology == "Go") go_eng = true;
        report_test("Technology Edge: 'go' prose rejected", !go_eng, 1.0, !go_eng ? 1.0 : 0.0);

        auto d_go_ctx = issue_pilot::TechnologyRegistry::detect("", "written in Go", "concurrency handled by goroutines", {}, {});
        bool go_ctx = false;
        for (const auto& t : d_go_ctx.technologies) if (t.technology == "Go") go_ctx = true;
        report_test("Technology Edge: 'written in Go' accepted", go_ctx, 1.0, go_ctx ? 1.0 : 0.0);

        auto d_go_fence = issue_pilot::TechnologyRegistry::detect("", "panic trace", "```go\nfunc main() {}\n```", {}, {});
        bool go_fence = false;
        for (const auto& t : d_go_fence.technologies) if (t.technology == "Go") go_fence = true;
        report_test("Technology Edge: Go code fence accepted", go_fence, 1.0, go_fence ? 1.0 : 0.0);

        auto d_go_ext = issue_pilot::TechnologyRegistry::detect("", "syntax error", "failed at pkg/server/main.go line 10", {}, {});
        bool go_ext = false;
        for (const auto& t : d_go_ext.technologies) if (t.technology == "Go") go_ext = true;
        report_test("Technology Edge: .go file extension accepted", go_ext, 1.0, go_ext ? 1.0 : 0.0);

        auto d_go_top = issue_pilot::TechnologyRegistry::detect("", "backend issue", "server hangs", {}, {"golang"});
        bool go_top = false;
        for (const auto& t : d_go_top.technologies) if (t.technology == "Go") go_top = true;
        report_test("Technology Edge: 'golang' topic accepted", go_top, 1.0, go_top ? 1.0 : 0.0);

        // C
        auto d_c_eng = issue_pilot::TechnologyRegistry::detect("", "look at option c in the list", "part c describes the behavior", {}, {});
        bool c_eng = false;
        for (const auto& t : d_c_eng.technologies) if (t.technology == "C") c_eng = true;
        report_test("Technology Edge: 'c' prose rejected", !c_eng, 1.0, !c_eng ? 1.0 : 0.0);

        auto d_c_ctx = issue_pilot::TechnologyRegistry::detect("", "core engine written in C", "ansi-c compatibility required", {}, {});
        bool c_ctx = false;
        for (const auto& t : d_c_ctx.technologies) if (t.technology == "C") c_ctx = true;
        report_test("Technology Edge: 'written in C' accepted", c_ctx, 1.0, c_ctx ? 1.0 : 0.0);

        auto d_c_ext = issue_pilot::TechnologyRegistry::detect("", "segfault", "fault in driver/main.c line 55", {}, {});
        bool c_ext = false;
        for (const auto& t : d_c_ext.technologies) if (t.technology == "C") c_ext = true;
        report_test("Technology Edge: .c file extension accepted", c_ext, 1.0, c_ext ? 1.0 : 0.0);

        // R
        auto d_r_eng = issue_pilot::TechnologyRegistry::detect("", "computed correlation r = 0.95", "radius r of the circle", {}, {});
        bool r_eng = false;
        for (const auto& t : d_r_eng.technologies) if (t.technology == "R") r_eng = true;
        report_test("Technology Edge: 'r' prose rejected", !r_eng, 1.0, !r_eng ? 1.0 : 0.0);

        auto d_r_ctx = issue_pilot::TechnologyRegistry::detect("", "statistical analysis in R", "uses cran package", {}, {});
        bool r_ctx = false;
        for (const auto& t : d_r_ctx.technologies) if (t.technology == "R") r_ctx = true;
        report_test("Technology Edge: 'analysis in R' accepted", r_ctx, 1.0, r_ctx ? 1.0 : 0.0);

        auto d_r_ext = issue_pilot::TechnologyRegistry::detect("", "runtime error", "error in scripts/analysis.R line 12", {}, {});
        bool r_ext = false;
        for (const auto& t : d_r_ext.technologies) if (t.technology == "R") r_ext = true;
        report_test("Technology Edge: .R file extension accepted", r_ext, 1.0, r_ext ? 1.0 : 0.0);

        // JS
        auto d_js_eng = issue_pilot::TechnologyRegistry::detect("", "the js variable is unset", "pass param js = 1", {}, {});
        bool js_eng = false;
        for (const auto& t : d_js_eng.technologies) if (t.technology == "JavaScript") js_eng = true;
        report_test("Technology Edge: 'js' generic prose rejected", !js_eng, 1.0, !js_eng ? 1.0 : 0.0);

        auto d_js_ctx = issue_pilot::TechnologyRegistry::detect("", "client side written in JS", "bundle size regression", {}, {});
        bool js_ctx = false;
        for (const auto& t : d_js_ctx.technologies) if (t.technology == "JavaScript") js_ctx = true;
        report_test("Technology Edge: 'written in JS' accepted", js_ctx, 1.0, js_ctx ? 1.0 : 0.0);

        auto d_js_ext = issue_pilot::TechnologyRegistry::detect("", "syntax error", "found in web/app.js:20", {}, {});
        bool js_ext = false;
        for (const auto& t : d_js_ext.technologies) if (t.technology == "JavaScript") js_ext = true;
        report_test("Technology Edge: .js file extension accepted", js_ext, 1.0, js_ext ? 1.0 : 0.0);

        auto d_js_manifest = issue_pilot::TechnologyRegistry::detect("", "build broken", "check package.json dependencies", {}, {});
        bool js_manifest = false;
        for (const auto& t : d_js_manifest.technologies) if (t.technology == "JavaScript") js_manifest = true;
        report_test("Technology Edge: package.json manifest accepted", js_manifest, 1.0, js_manifest ? 1.0 : 0.0);

        // TS
        auto d_ts_eng = issue_pilot::TechnologyRegistry::detect("", "timestamp ts = 1700000000", "invalid ts in event log", {}, {});
        bool ts_eng = false;
        for (const auto& t : d_ts_eng.technologies) if (t.technology == "TypeScript") ts_eng = true;
        report_test("Technology Edge: 'ts' timestamp prose rejected", !ts_eng, 1.0, !ts_eng ? 1.0 : 0.0);

        auto d_ts_ctx = issue_pilot::TechnologyRegistry::detect("", "frontend written in TS", "compiler error on TS types", {}, {});
        bool ts_ctx = false;
        for (const auto& t : d_ts_ctx.technologies) if (t.technology == "TypeScript") ts_ctx = true;
        report_test("Technology Edge: 'written in TS' accepted", ts_ctx, 1.0, ts_ctx ? 1.0 : 0.0);

        auto d_ts_ext = issue_pilot::TechnologyRegistry::detect("", "type error", "found in src/index.ts:15", {}, {});
        bool ts_ext = false;
        for (const auto& t : d_ts_ext.technologies) if (t.technology == "TypeScript") ts_ext = true;
        report_test("Technology Edge: .ts file extension accepted", ts_ext, 1.0, ts_ext ? 1.0 : 0.0);

        auto d_ts_manifest = issue_pilot::TechnologyRegistry::detect("", "tsconfig error", "check tsconfig.json paths", {}, {});
        bool ts_manifest = false;
        for (const auto& t : d_ts_manifest.technologies) if (t.technology == "TypeScript") ts_manifest = true;
        report_test("Technology Edge: tsconfig.json manifest accepted", ts_manifest, 1.0, ts_manifest ? 1.0 : 0.0);
    }

    // =========================================================================
    // PHASE 4: DIFFICULTY ESTIMATOR AND ENGINEERING CHALLENGE FIT MODEL TESTS
    // =========================================================================
    std::cout << "\n--- Phase 4: Difficulty Estimator & Engineering Challenge Fit Tests ---\n";

    // Test 33: Golden Difficulty Fixtures Execution
    {
        std::string diff_fix_path = "ranking_engine/tests/fixtures/golden_difficulty_v23_fixtures.json";
        std::ifstream f(diff_fix_path);
        if (!f.is_open()) {
            diff_fix_path = "tests/fixtures/golden_difficulty_v23_fixtures.json";
            f.open(diff_fix_path);
        }
        if (!f.is_open()) {
            diff_fix_path = "../tests/fixtures/golden_difficulty_v23_fixtures.json";
            f.open(diff_fix_path);
        }
        report_test("Golden Difficulty JSON Fixture Opened", f.is_open(), 1.0, f.is_open() ? 1.0 : 0.0);

        std::string content((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        bool err = false;
        size_t pos = 0;
        auto root = issue_pilot::SimpleJsonParser::parse(content, pos, err);
        report_test("Golden Difficulty JSON Valid AST", !err && root.has("fixtures"), 1.0, (!err && root.has("fixtures")) ? 1.0 : 0.0);

        // Case 1: Full anchors (SC=75, TC=50, DI=50, CC=25 -> ID=52.5, Aid=1.0, D=100.0, CD=0.88)
        {
            issue_pilot::DifficultyEvidenceV2 ev1;
            ev1.scope_change_size = 75.0;
            ev1.scope_available = true;
            ev1.scope_confidence = 1.0;
            ev1.technical_complexity = 50.0;
            ev1.technical_available = true;
            ev1.technical_confidence = 1.0;
            ev1.dependency_arch_impact = 50.0;
            ev1.dependency_available = true;
            ev1.dependency_confidence = 1.0;
            ev1.coordination_complexity = 25.0;
            ev1.coordination_available = true;
            ev1.coordination_confidence = 1.0;

            auto res1 = ev1.compute_difficulty({});
            report_test("Golden Difficulty 1: Available", res1.available, 1.0, res1.available ? 1.0 : 0.0);
            report_test("Golden Difficulty 1: Aid (1.0)", std::abs(res1.coverage_aid - 1.0) < 1e-6, 1.0, res1.coverage_aid);
            report_test("Golden Difficulty 1: ID (52.5)", std::abs(res1.issue_difficulty - 52.5) < 1e-4, 52.5, res1.issue_difficulty);
            report_test("Golden Difficulty 1: Confidence (1.0)", std::abs(res1.confidence - 1.0) < 1e-6, 1.0, res1.confidence);

            auto fit1 = issue_pilot::DifficultyEvidenceV2::compute_fit(res1.issue_difficulty, res1.confidence, 50.0, 0.88);
            report_test("Golden Difficulty 1: Fit Available", fit1.available, 1.0, fit1.available ? 1.0 : 0.0);
            report_test("Golden Difficulty 1: D (100.0, in 5-pt dead zone)", std::abs(fit1.difficulty_match_score - 100.0) < 1e-6, 100.0, fit1.difficulty_match_score);
            report_test("Golden Difficulty 1: Penalty (0.0)", std::abs(fit1.penalty - 0.0) < 1e-6, 0.0, fit1.penalty);
            report_test("Golden Difficulty 1: CD (0.88)", std::abs(fit1.confidence - 0.88) < 1e-4, 0.88, fit1.confidence);
        }

        // Case 2 & 3: Spec Section 16 worked examples
        {
            // ID=80, cap=60 -> g=20, penalty=22.5, D=77.5
            auto fit2 = issue_pilot::DifficultyEvidenceV2::compute_fit(80.0, 0.90, 60.0, 0.85);
            report_test("Spec Worked Example 1: g=20", std::abs(fit2.fit_gap - 20.0) < 1e-6, 20.0, fit2.fit_gap);
            report_test("Spec Worked Example 1: penalty=22.5", std::abs(fit2.penalty - 22.5) < 1e-4, 22.5, fit2.penalty);
            report_test("Spec Worked Example 1: D=77.5", std::abs(fit2.difficulty_match_score - 77.5) < 1e-4, 77.5, fit2.difficulty_match_score);
            report_test("Spec Worked Example 1: CD=0.85", std::abs(fit2.confidence - 0.85) < 1e-4, 0.85, fit2.confidence);

            // ID=40, cap=60 -> g=-20, penalty=15.0, D=85.0
            auto fit3 = issue_pilot::DifficultyEvidenceV2::compute_fit(40.0, 0.90, 60.0, 0.85);
            report_test("Spec Worked Example 2: g=-20", std::abs(fit3.fit_gap - (-20.0)) < 1e-6, -20.0, fit3.fit_gap);
            report_test("Spec Worked Example 2: penalty=15.0", std::abs(fit3.penalty - 15.0) < 1e-4, 15.0, fit3.penalty);
            report_test("Spec Worked Example 2: D=85.0", std::abs(fit3.difficulty_match_score - 85.0) < 1e-4, 85.0, fit3.difficulty_match_score);
            report_test("Spec Worked Example 2: CD=0.85", std::abs(fit3.confidence - 0.85) < 1e-4, 0.85, fit3.confidence);
        }

        // Case 4: Exactly 0.50 coverage boundary (TC=50, CC=50 -> Aid=0.50 -> Available!)
        {
            issue_pilot::DifficultyEvidenceV2 ev4;
            ev4.technical_complexity = 50.0;
            ev4.technical_available = true;
            ev4.technical_confidence = 0.80;
            ev4.coordination_complexity = 50.0;
            ev4.coordination_available = true;
            ev4.coordination_confidence = 0.80;

            auto res4 = ev4.compute_difficulty({});
            report_test("Golden Difficulty 4: Aid == 0.50 Boundary Available", res4.available, 1.0, res4.available ? 1.0 : 0.0);
            report_test("Golden Difficulty 4: Aid = 0.50", std::abs(res4.coverage_aid - 0.50) < 1e-6, 0.50, res4.coverage_aid);
            report_test("Golden Difficulty 4: ID = 50.0", std::abs(res4.issue_difficulty - 50.0) < 1e-4, 50.0, res4.issue_difficulty);
            report_test("Golden Difficulty 4: CID = 0.40", std::abs(res4.confidence - 0.40) < 1e-6, 0.40, res4.confidence);
        }

        // Case 5: 0.40 coverage below gate (DI=50, CC=50 -> Aid=0.40 -> Unavailable!)
        {
            issue_pilot::DifficultyEvidenceV2 ev5;
            ev5.dependency_arch_impact = 50.0;
            ev5.dependency_available = true;
            ev5.dependency_confidence = 0.80;
            ev5.coordination_complexity = 50.0;
            ev5.coordination_available = true;
            ev5.coordination_confidence = 0.80;

            auto res5 = ev5.compute_difficulty({});
            report_test("Golden Difficulty 5: Aid = 0.40 < 0.50 Gate Unavailable", !res5.available, 1.0, !res5.available ? 1.0 : 0.0);
            report_test("Golden Difficulty 5: Aid = 0.40", std::abs(res5.coverage_aid - 0.40) < 1e-6, 0.40, res5.coverage_aid);
        }

        // Case 6 & 7: Weak label corroboration (easy -3.0, hard +3.0)
        {
            issue_pilot::DifficultyEvidenceV2 ev6;
            ev6.scope_change_size = 50.0; ev6.scope_available = true;
            ev6.technical_complexity = 50.0; ev6.technical_available = true;
            ev6.dependency_arch_impact = 50.0; ev6.dependency_available = true;
            ev6.coordination_complexity = 50.0; ev6.coordination_available = true;

            auto res6 = ev6.compute_difficulty({"good-first-issue", "beginner", "starter"});
            report_test("Label Corroboration Easy: Capped at -3.0", std::abs(res6.label_adjustment - (-3.0)) < 1e-6, -3.0, res6.label_adjustment);
            report_test("Label Corroboration Easy: Final ID = 47.0", std::abs(res6.issue_difficulty - 47.0) < 1e-4, 47.0, res6.issue_difficulty);

            issue_pilot::DifficultyEvidenceV2 ev7;
            ev7.scope_change_size = 50.0; ev7.scope_available = true;
            ev7.technical_complexity = 50.0; ev7.technical_available = true;
            ev7.dependency_arch_impact = 50.0; ev7.dependency_available = true;
            ev7.coordination_complexity = 50.0; ev7.coordination_available = true;

            auto res7 = ev7.compute_difficulty({"epic", "complex", "high-complexity"});
            report_test("Label Corroboration Hard: Capped at +3.0", std::abs(res7.label_adjustment - 3.0) < 1e-6, 3.0, res7.label_adjustment);
            report_test("Label Corroboration Hard: Final ID = 53.0", std::abs(res7.issue_difficulty - 53.0) < 1e-4, 53.0, res7.issue_difficulty);
        }

        // Case 8: Dead zone evaluations (g in [-6, +6])
        {
            struct DeadZoneCheck { double g; double exp_pen; double exp_d; };
            std::vector<DeadZoneCheck> dz_checks = {
                {-6.0, 1.00, 99.00},
                {-5.1, 0.10, 99.90},
                {-5.0, 0.00, 100.00},
                { 0.0, 0.00, 100.00},
                { 5.0, 0.00, 100.00},
                { 5.1, 0.15, 99.85},
                { 6.0, 1.50, 98.50}
            };
            bool all_dz_ok = true;
            for (const auto& c : dz_checks) {
                auto fit = issue_pilot::DifficultyEvidenceV2::compute_fit(c.g, 1.0, 0.0, 1.0);
                if (std::abs(fit.penalty - c.exp_pen) > 1e-4 || std::abs(fit.difficulty_match_score - c.exp_d) > 1e-4) {
                    all_dz_ok = false;
                }
            }
            report_test("Dead Zone Boundaries and Slopes Verified (g in [-6, 6])", all_dz_ok, 1.0, all_dz_ok ? 1.0 : 0.0);
        }

        // Case 9: Penalty saturation and clamping to 0.0
        {
            auto fit_sat_pos = issue_pilot::DifficultyEvidenceV2::compute_fit(80.0, 1.0, 0.0, 1.0);
            report_test("Penalty Saturation Pos (g=80 -> D=0.0)", std::abs(fit_sat_pos.difficulty_match_score - 0.0) < 1e-6, 0.0, fit_sat_pos.difficulty_match_score);

            auto fit_sat_neg = issue_pilot::DifficultyEvidenceV2::compute_fit(-110.0, 1.0, 0.0, 1.0);
            report_test("Penalty Saturation Neg (g=-110 -> D=0.0)", std::abs(fit_sat_neg.difficulty_match_score - 0.0) < 1e-6, 0.0, fit_sat_neg.difficulty_match_score);
        }

        // Case 10: Questionnaire-only skill regression
        {
            auto fit10 = issue_pilot::DifficultyEvidenceV2::compute_fit(75.0, 0.90, 75.0, 0.40);
            report_test("Questionnaire Only Fit: D=100.0", std::abs(fit10.difficulty_match_score - 100.0) < 1e-6, 100.0, fit10.difficulty_match_score);
            report_test("Questionnaire Only Fit: CD=0.40", std::abs(fit10.confidence - 0.40) < 1e-6, 0.40, fit10.confidence);
        }
    }

    // Test 34: Missing Signals, Non-Anchor Inputs, and Availability Independence
    {
        // Missing 1 signal: SC missing -> remaining 0.70 >= 0.50
        issue_pilot::DifficultyEvidenceV2 ev_m1;
        ev_m1.technical_complexity = 50.0; ev_m1.technical_available = true;
        ev_m1.dependency_arch_impact = 50.0; ev_m1.dependency_available = true;
        ev_m1.coordination_complexity = 50.0; ev_m1.coordination_available = true;
        auto res_m1 = ev_m1.compute_difficulty({});
        report_test("Missing 1 Signal (SC): Aid=0.70 Available", res_m1.available && std::abs(res_m1.coverage_aid - 0.70) < 1e-6, 1.0, res_m1.available ? 1.0 : 0.0);

        // Missing 2 signals: SC + TC missing -> remaining 0.40 < 0.50
        issue_pilot::DifficultyEvidenceV2 ev_m2;
        ev_m2.dependency_arch_impact = 50.0; ev_m2.dependency_available = true;
        ev_m2.coordination_complexity = 50.0; ev_m2.coordination_available = true;
        auto res_m2 = ev_m2.compute_difficulty({});
        report_test("Missing 2 Signals (SC+TC): Aid=0.40 Unavailable", !res_m2.available, 1.0, !res_m2.available ? 1.0 : 0.0);

        // Missing 3 signals: only SC present -> remaining 0.30 < 0.50
        issue_pilot::DifficultyEvidenceV2 ev_m3;
        ev_m3.scope_change_size = 100.0; ev_m3.scope_available = true;
        auto res_m3 = ev_m3.compute_difficulty({});
        report_test("Missing 3 Signals (only SC): Aid=0.30 Unavailable", !res_m3.available, 1.0, !res_m3.available ? 1.0 : 0.0);

        // Non-anchor input (e.g. 33.0 or 60.0) treated as invalid/unavailable
        issue_pilot::DifficultyEvidenceV2 ev_non_anchor;
        ev_non_anchor.scope_change_size = 33.0; ev_non_anchor.scope_available = true;
        ev_non_anchor.technical_complexity = 60.0; ev_non_anchor.technical_available = true;
        ev_non_anchor.dependency_arch_impact = 50.0; ev_non_anchor.dependency_available = true;
        ev_non_anchor.coordination_complexity = 50.0; ev_non_anchor.coordination_available = true;
        auto res_non_anchor = ev_non_anchor.compute_difficulty({});
        report_test("Non-Anchor Inputs Discarded: Aid=0.40 Unavailable", !res_non_anchor.available && std::abs(res_non_anchor.coverage_aid - 0.40) < 1e-6, 1.0, !res_non_anchor.available ? 1.0 : 0.0);

        // Score clamping at boundaries 0 and 100 with label adjustments
        issue_pilot::DifficultyEvidenceV2 ev_top;
        ev_top.scope_change_size = 100.0; ev_top.scope_available = true;
        ev_top.technical_complexity = 100.0; ev_top.technical_available = true;
        ev_top.dependency_arch_impact = 100.0; ev_top.dependency_available = true;
        ev_top.coordination_complexity = 100.0; ev_top.coordination_available = true;
        auto res_top = ev_top.compute_difficulty({"epic", "hard", "complex"});
        report_test("Score Clamping Max at 100 (100 + 3 = 100)", std::abs(res_top.issue_difficulty - 100.0) < 1e-6, 100.0, res_top.issue_difficulty);

        issue_pilot::DifficultyEvidenceV2 ev_bot;
        ev_bot.scope_change_size = 0.0; ev_bot.scope_available = true;
        ev_bot.technical_complexity = 0.0; ev_bot.technical_available = true;
        ev_bot.dependency_arch_impact = 0.0; ev_bot.dependency_available = true;
        ev_bot.coordination_complexity = 0.0; ev_bot.coordination_available = true;
        auto res_bot = ev_bot.compute_difficulty({"good-first-issue", "easy", "beginner"});
        report_test("Score Clamping Min at 0 (0 - 3 = 0)", std::abs(res_bot.issue_difficulty - 0.0) < 1e-6, 0.0, res_bot.issue_difficulty);
    }

    // Test 35: Engine Integration with DifficultyEvidenceV2 and Skill Availability Gate
    {
        issue_pilot::RankingEngine engine_diff;
        issue_pilot::IssueRecord issue_diff;
        issue_diff.id = "diff_engine_test";
        issue_diff.issue_url = "https://github.com/test/repo/issues/100";
        issue_diff.labels = {"good-first-issue"};

        issue_pilot::DifficultyEvidenceV2 d_ev;
        d_ev.scope_change_size = 50.0; d_ev.scope_available = true;
        d_ev.technical_complexity = 50.0; d_ev.technical_available = true;
        d_ev.dependency_arch_impact = 50.0; d_ev.dependency_available = true;
        d_ev.coordination_complexity = 50.0; d_ev.coordination_available = true;
        issue_diff.difficulty_evidence_v2.available = true;
        issue_diff.difficulty_evidence_v2.value = d_ev;
        issue_diff.difficulty_evidence_v2.confidence = 0.95;

        // Subcase A: Contributor has skill available -> D computed
        issue_pilot::ContributorProfile prof_with_skill;
        prof_with_skill.user_id = "user_skill_ok";
        prof_with_skill.skills["Python"] = issue_pilot::ContributorSkill{"Python", 50.0, 0.85};
        issue_diff.technologies.push_back(issue_pilot::TechnologyRelevance{"Python", 1.0, 1.0});

        auto scores_a = engine_diff.compute_scores(prof_with_skill, issue_diff);
        report_test("Engine V2.3 Difficulty Match Available when Skill Available", scores_a.difficulty_available, 1.0, scores_a.difficulty_available ? 1.0 : 0.0);
        // ID raw = 50, label "good-first-issue" -> -1.5 -> ID = 48.5
        // cap = 50.0 -> g = 48.5 - 50.0 = -1.5 in dead zone -> D = 100.0
        report_test("Engine V2.3 Computed ID = 48.5", std::abs(scores_a.computed_issue_difficulty - 48.5) < 1e-4, 48.5, scores_a.computed_issue_difficulty);
        report_test("Engine V2.3 D Match = 100.0", std::abs(scores_a.difficulty_match - 100.0) < 1e-4, 100.0, scores_a.difficulty_match);

        // Subcase B: Skill is UNAVAILABLE -> D must be UNAVAILABLE!
        issue_pilot::ContributorProfile prof_no_skill;
        prof_no_skill.user_id = "user_no_skill";
        prof_no_skill.skill_evidence_v2["Rust"] = issue_pilot::ContributorSkillEvidenceV2{}; // not matching Python
        auto scores_b = engine_diff.compute_scores(prof_no_skill, issue_diff);
        report_test("Engine V2.3 Difficulty Match Unavailable when Skill Unavailable", !scores_b.difficulty_available, 1.0, !scores_b.difficulty_available ? 1.0 : 0.0);
    }

    std::cout << "\nALL UNIT TEST CHECKS (INCLUDING PHASE 1 CONTRACTS) PASSED SUCCESSFULLY!\n";
    std::cout << "Executed: " << total_assertions << " | Passed: " << passed_assertions << " | Failed: " << (total_assertions - passed_assertions) << "\n";
    std::cout << "========================================\n";
    return (total_assertions == passed_assertions) ? 0 : 1;
}
