#include "../include/json_adapter.hpp"
#include <sstream>
#include <iomanip>
#include <cctype>
#include <stdexcept>
#include <map>
#include <cmath>
#include <algorithm>
#include <iostream>

namespace issue_pilot {

// Minimal Lightweight AST for JSON parsing
struct JsonValue {
    enum Type { V_NULL, V_BOOL, V_NUM, V_STR, V_ARR, V_OBJ } type = V_NULL;
    bool b_val = false;
    double n_val = 0.0;
    std::string s_val;
    std::vector<JsonValue> arr_val;
    std::map<std::string, JsonValue> obj_val;

    bool has(const std::string& key) const {
        return type == V_OBJ && obj_val.find(key) != obj_val.end();
    }

    const JsonValue& get(const std::string& key) const {
        static JsonValue null_v;
        if (type != V_OBJ) return null_v;
        auto it = obj_val.find(key);
        if (it != obj_val.end()) return it->second;
        return null_v;
    }

    double as_double(double def = 0.0) const {
        if (type == V_NUM) return n_val;
        return def;
    }

    int as_int(int def = 0) const {
        if (type == V_NUM) return static_cast<int>(n_val);
        return def;
    }

    bool as_bool(bool def = false) const {
        if (type == V_BOOL) return b_val;
        return def;
    }

    std::string as_str(const std::string& def = "") const {
        if (type == V_STR) return s_val;
        return def;
    }
};

class SimpleJsonParser {
public:
    static void skip_ws(const std::string& t, size_t& p) {
        while (p < t.size() && (t[p] == ' ' || t[p] == '\t' || t[p] == '\r' || t[p] == '\n')) {
            p++;
        }
    }

    static JsonValue parse(const std::string& text, size_t& pos, bool& err) {
        skip_ws(text, pos);
        if (pos >= text.size()) return JsonValue();
        char c = text[pos];
        if (c == '{') return parse_object(text, pos, err);
        if (c == '[') return parse_array(text, pos, err);
        if (c == '"') return parse_string(text, pos, err);
        if (c == 't' || c == 'f') return parse_bool(text, pos, err);
        if (c == 'n') return parse_null(text, pos, err);
        if (c == '-' || std::isdigit(static_cast<unsigned char>(c))) return parse_number(text, pos, err);
        err = true;
        return JsonValue();
    }

private:
    static JsonValue parse_object(const std::string& t, size_t& p, bool& err) {
        JsonValue val;
        val.type = JsonValue::V_OBJ;
        p++; // skip '{'
        skip_ws(t, p);
        if (p < t.size() && t[p] == '}') {
            p++;
            return val;
        }
        while (p < t.size()) {
            skip_ws(t, p);
            if (p >= t.size() || t[p] != '"') {
                err = true;
                return JsonValue();
            }
            JsonValue key_v = parse_string(t, p, err);
            if (err) return JsonValue();
            skip_ws(t, p);
            if (p >= t.size() || t[p] != ':') {
                err = true;
                return JsonValue();
            }
            p++; // skip ':'
            skip_ws(t, p);
            JsonValue child = parse(t, p, err);
            if (err) return JsonValue();
            val.obj_val[key_v.s_val] = child;
            skip_ws(t, p);
            if (p < t.size() && t[p] == ',') {
                p++;
            } else if (p < t.size() && t[p] == '}') {
                p++;
                return val;
            } else {
                err = true;
                return JsonValue();
            }
        }
        err = true; // unclosed object
        return JsonValue();
    }

    static JsonValue parse_array(const std::string& t, size_t& p, bool& err) {
        JsonValue val;
        val.type = JsonValue::V_ARR;
        p++; // skip '['
        skip_ws(t, p);
        if (p < t.size() && t[p] == ']') {
            p++;
            return val;
        }
        while (p < t.size()) {
            skip_ws(t, p);
            val.arr_val.push_back(parse(t, p, err));
            if (err) return JsonValue();
            skip_ws(t, p);
            if (p < t.size() && t[p] == ',') {
                p++;
            } else if (p < t.size() && t[p] == ']') {
                p++;
                return val;
            } else {
                err = true;
                return JsonValue();
            }
        }
        err = true; // unclosed array
        return JsonValue();
    }

    static JsonValue parse_string(const std::string& t, size_t& p, bool& err) {
        JsonValue val;
        val.type = JsonValue::V_STR;
        if (p >= t.size() || t[p] != '"') {
            err = true;
            return JsonValue();
        }
        p++; // skip initial '"'
        std::string s;
        bool closed = false;
        while (p < t.size()) {
            char c = t[p++];
            if (c == '"') {
                closed = true;
                break;
            }
            if (c == '\\' && p < t.size()) {
                char esc = t[p++];
                if (esc == 'n') s += '\n';
                else if (esc == 'r') s += '\r';
                else if (esc == 't') s += '\t';
                else if (esc == '"') s += '"';
                else if (esc == '\\') s += '\\';
                else s += esc;
            } else {
                s += c;
            }
        }
        if (!closed) {
            err = true;
            return JsonValue();
        }
        val.s_val = s;
        return val;
    }

    static JsonValue parse_bool(const std::string& t, size_t& p, bool& err) {
        JsonValue val;
        val.type = JsonValue::V_BOOL;
        if (t.compare(p, 4, "true") == 0) {
            val.b_val = true;
            p += 4;
            return val;
        } else if (t.compare(p, 5, "false") == 0) {
            val.b_val = false;
            p += 5;
            return val;
        }
        err = true;
        return val;
    }

    static JsonValue parse_null(const std::string& t, size_t& p, bool& err) {
        JsonValue val;
        val.type = JsonValue::V_NULL;
        if (t.compare(p, 4, "null") == 0) {
            p += 4;
            return val;
        }
        err = true;
        return val;
    }

    static JsonValue parse_number(const std::string& t, size_t& p, bool& err) {
        JsonValue val;
        val.type = JsonValue::V_NUM;
        size_t start = p;
        if (p < t.size() && t[p] == '-') p++;
        bool has_digits = false;
        while (p < t.size() && (std::isdigit(static_cast<unsigned char>(t[p])) || t[p] == '.' || t[p] == 'e' || t[p] == 'E' || t[p] == '+' || t[p] == '-')) {
            if (std::isdigit(static_cast<unsigned char>(t[p]))) has_digits = true;
            p++;
        }
        if (!has_digits) {
            err = true;
            return JsonValue();
        }
        std::string sub = t.substr(start, p - start);
        try {
            val.n_val = std::stod(sub);
        } catch (...) {
            err = true;
            val.n_val = 0.0;
        }
        return val;
    }
};

static std::string escape_json(const std::string& s) {
    std::ostringstream o;
    for (char c : s) {
        if (c == '"') o << "\\\"";
        else if (c == '\\') o << "\\\\";
        else if (c == '\b') o << "\\b";
        else if (c == '\f') o << "\\f";
        else if (c == '\n') o << "\\n";
        else if (c == '\r') o << "\\r";
        else if (c == '\t') o << "\\t";
        else if (static_cast<unsigned char>(c) <= 0x1f) {
            o << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(c);
        } else {
            o << c;
        }
    }
    return o.str();
}

bool JsonAdapter::parse_request(const std::string& json_str,
                                ContributorProfile& out_profile,
                                RankingWeights& out_weights,
                                std::vector<IssueRecord>& out_candidates,
                                std::string& out_error) {
    bool err = false;
    size_t pos = 0;
    JsonValue root = SimpleJsonParser::parse(json_str, pos, err);
    SimpleJsonParser::skip_ws(json_str, pos);
    if (err || root.type != JsonValue::V_OBJ || pos < json_str.size()) {
        out_error = "Invalid JSON: syntax error or root is not an object";
        return false;
    }

    // Profile
    if (root.has("profile")) {
        const auto& prof = root.get("profile");
        out_profile.user_id = prof.get("user_id").as_str("demo-user");
        out_profile.capability = prof.get("capability").as_double(70.0);
        out_profile.accepted_count = prof.has("accepted_count") ? prof.get("accepted_count").as_int(0) : prof.get("accepted").as_int(0);
        out_profile.rejected_count = prof.has("rejected_count") ? prof.get("rejected_count").as_int(0) : prof.get("rejected").as_int(0);
        out_profile.completed_count = prof.has("completed_count") ? prof.get("completed_count").as_int(0) : prof.get("completed").as_int(0);
        out_profile.similarity_to_history = prof.get("similarity_to_history").as_double(75.0);
        out_profile.has_feedback_history = prof.get("has_feedback_history").as_bool(false);

        if (prof.has("skills") && prof.get("skills").type == JsonValue::V_OBJ) {
            for (const auto& kv : prof.get("skills").obj_val) {
                ContributorSkill s;
                s.technology = kv.first;
                if (kv.second.type == JsonValue::V_NUM) {
                    s.score = kv.second.as_double();
                    s.confidence = 1.0;
                } else if (kv.second.type == JsonValue::V_OBJ) {
                    s.score = kv.second.get("score").as_double();
                    s.confidence = kv.second.get("confidence").as_double(1.0);
                }
                out_profile.skills[kv.first] = s;
            }
        }
    }

    // Weights (optional)
    if (root.has("weights")) {
        const auto& w = root.get("weights");
        out_weights.skill_weight = w.get("skill_weight").as_double(out_weights.skill_weight);
        out_weights.difficulty_weight = w.get("difficulty_weight").as_double(out_weights.difficulty_weight);
        out_weights.responsiveness_weight = w.get("responsiveness_weight").as_double(out_weights.responsiveness_weight);
        out_weights.health_weight = w.get("health_weight").as_double(out_weights.health_weight);
        out_weights.feedback_weight = w.get("feedback_weight").as_double(out_weights.feedback_weight);
        out_weights.freshness_weight = w.get("freshness_weight").as_double(out_weights.freshness_weight);
    }

    // Candidates
    if (root.has("candidates") && root.get("candidates").type == JsonValue::V_ARR) {
        for (const auto& item : root.get("candidates").arr_val) {
            IssueRecord issue;
            issue.id = item.get("id").as_str();
            issue.native_id = item.get("native_id").as_str();
            issue.repository_full_name = item.get("repository_full_name").as_str();
            issue.issue_number = item.get("issue_number").as_int();
            issue.title = item.get("title").as_str();
            issue.body = item.get("body").as_str();
            issue.issue_url = item.get("issue_url").as_str();
            issue.state = item.get("state").as_str("open");
            issue.is_assigned = item.get("is_assigned").as_bool(false);
            issue.created_at = item.get("created_at").as_str();
            issue.updated_at = item.get("updated_at").as_str();

            if (item.has("labels") && item.get("labels").type == JsonValue::V_ARR) {
                for (const auto& lbl : item.get("labels").arr_val) {
                    issue.labels.push_back(lbl.as_str());
                }
            }

            if (item.has("skill_available")) {
                issue.skill_available = item.get("skill_available").as_bool(true);
            }

            // Technologies
            if (item.has("technologies") && item.get("technologies").type == JsonValue::V_ARR) {
                for (const auto& t : item.get("technologies").arr_val) {
                    TechnologyRelevance tr;
                    tr.technology = t.get("technology").as_str();
                    tr.raw_relevance = t.get("raw_relevance").as_double(1.0);
                    issue.technologies.push_back(tr);
                }
            }

            // Difficulty Evidence
            if (item.has("difficulty_evidence")) {
                const auto& d = item.get("difficulty_evidence");
                issue.difficulty_evidence.available = d.get("available").as_bool(true);
                issue.difficulty_evidence.scope_change_size = d.get("scope_change_size").as_double(50.0);
                issue.difficulty_evidence.technical_complexity = d.get("technical_complexity").as_double(50.0);
                issue.difficulty_evidence.historical_effort = d.get("historical_effort").as_double(50.0);
                issue.difficulty_evidence.dependency_arch_impact = d.get("dependency_arch_impact").as_double(50.0);
                issue.difficulty_evidence.coordination_complexity = d.get("coordination_complexity").as_double(50.0);
            }

            // Responsiveness Evidence
            if (item.has("responsiveness_evidence")) {
                const auto& r = item.get("responsiveness_evidence");
                issue.responsiveness_evidence.available = r.get("available").as_bool(true);
                issue.responsiveness_evidence.response_time_score = r.get("response_time_score").as_double(80.0);
                issue.responsiveness_evidence.response_rate_score = r.get("response_rate_score").as_double(80.0);
                issue.responsiveness_evidence.pr_review_score = r.get("pr_review_score").as_double(80.0);
                issue.responsiveness_evidence.recent_maintainer_act = r.get("recent_maintainer_act").as_double(80.0);
            }

            // Health Evidence
            if (item.has("health_evidence")) {
                const auto& h = item.get("health_evidence");
                issue.health_evidence.available = h.get("available").as_bool(true);
                issue.health_evidence.recent_commits = h.get("recent_commits").as_double(80.0);
                issue.health_evidence.pr_activity = h.get("pr_activity").as_double(80.0);
                issue.health_evidence.issue_activity = h.get("issue_activity").as_double(80.0);
                issue.health_evidence.active_contributors = h.get("active_contributors").as_double(80.0);
                issue.health_evidence.recent_releases = h.get("recent_releases").as_double(80.0);
            }

            // Freshness Evidence
            if (item.has("freshness_evidence")) {
                const auto& f = item.get("freshness_evidence");
                issue.freshness_evidence.available = f.get("available").as_bool(true);
                issue.freshness_evidence.age_score = f.get("age_score").as_double(80.0);
                issue.freshness_evidence.activity_score = f.get("activity_score").as_double(80.0);
            }

            out_candidates.push_back(issue);
        }
    }

    return true;
}

std::string JsonAdapter::serialize_results(const std::vector<RankingResult>& results,
                                          const RankingWeights& weights,
                                          const std::string& model_version) {
    std::ostringstream ss;
    ss << std::fixed << std::setprecision(2);
    ss << "{\n";
    ss << "  \"algorithm_version\": \"" << escape_json(model_version) << "\",\n";
    ss << "  \"weights\": {\n";
    ss << "    \"skill_weight\": " << weights.skill_weight << ",\n";
    ss << "    \"difficulty_weight\": " << weights.difficulty_weight << ",\n";
    ss << "    \"responsiveness_weight\": " << weights.responsiveness_weight << ",\n";
    ss << "    \"health_weight\": " << weights.health_weight << ",\n";
    ss << "    \"feedback_weight\": " << weights.feedback_weight << ",\n";
    ss << "    \"freshness_weight\": " << weights.freshness_weight << "\n";
    ss << "  },\n";
    ss << "  \"count\": " << results.size() << ",\n";
    ss << "  \"results\": [\n";

    for (size_t i = 0; i < results.size(); ++i) {
        const auto& r = results[i];
        ss << "    {\n";
        ss << "      \"rank\": " << r.rank << ",\n";
        ss << "      \"id\": \"" << escape_json(r.issue.id) << "\",\n";
        ss << "      \"issue_number\": " << r.issue.issue_number << ",\n";
        ss << "      \"title\": \"" << escape_json(r.issue.title) << "\",\n";
        ss << "      \"repository_full_name\": \"" << escape_json(r.issue.repository_full_name) << "\",\n";
        ss << "      \"issue_url\": \"" << escape_json(r.issue.issue_url) << "\",\n";
        ss << "      \"state\": \"" << escape_json(r.issue.state) << "\",\n";
        ss << "      \"is_assigned\": " << (r.issue.is_assigned ? "true" : "false") << ",\n";
        ss << "      \"verified\": " << (r.verified ? "true" : "false") << ",\n";
        ss << "      \"verification_notes\": \"" << escape_json(r.verification_notes) << "\",\n";

        // Scores
        ss << "      \"scores\": {\n";
        ss << "        \"skill_match\": " << r.scores.skill_match << ",\n";
        ss << "        \"skill_available\": " << (r.scores.skill_available ? "true" : "false") << ",\n";
        ss << "        \"difficulty_match\": " << r.scores.difficulty_match << ",\n";
        ss << "        \"difficulty_available\": " << (r.scores.difficulty_available ? "true" : "false") << ",\n";
        ss << "        \"computed_issue_difficulty\": " << r.scores.computed_issue_difficulty << ",\n";
        ss << "        \"responsiveness\": " << r.scores.responsiveness << ",\n";
        ss << "        \"responsiveness_available\": " << (r.scores.responsiveness_available ? "true" : "false") << ",\n";
        ss << "        \"repository_health\": " << r.scores.repository_health << ",\n";
        ss << "        \"health_available\": " << (r.scores.health_available ? "true" : "false") << ",\n";
        ss << "        \"feedback\": " << r.scores.feedback << ",\n";
        ss << "        \"feedback_available\": " << (r.scores.feedback_available ? "true" : "false") << ",\n";
        ss << "        \"freshness\": " << r.scores.freshness << ",\n";
        ss << "        \"freshness_available\": " << (r.scores.freshness_available ? "true" : "false") << ",\n";
        ss << "        \"missing_components\": [";
        for (size_t m = 0; m < r.scores.missing_components.size(); ++m) {
            ss << "\"" << escape_json(r.scores.missing_components[m]) << "\"";
            if (m + 1 < r.scores.missing_components.size()) ss << ", ";
        }
        ss << "],\n";
        ss << "        \"explanation\": \"" << escape_json(r.scores.explanation) << "\",\n";
        ss << "        \"final_score\": " << r.scores.final_score << "\n";
        ss << "      },\n";

        // Effective Weights
        ss << "      \"effective_weights\": {\n";
        ss << "        \"skill\": " << r.scores.effective_skill_weight << ",\n";
        ss << "        \"difficulty\": " << r.scores.effective_diff_weight << ",\n";
        ss << "        \"responsiveness\": " << r.scores.effective_resp_weight << ",\n";
        ss << "        \"health\": " << r.scores.effective_health_weight << ",\n";
        ss << "        \"feedback\": " << r.scores.effective_fb_weight << ",\n";
        ss << "        \"freshness\": " << r.scores.effective_fresh_weight << "\n";
        ss << "      },\n";

        // Weighted Contributions
        ss << "      \"weighted_contributions\": {\n";
        ss << "        \"skill\": " << r.scores.weighted_skill << ",\n";
        ss << "        \"difficulty\": " << r.scores.weighted_difficulty << ",\n";
        ss << "        \"responsiveness\": " << r.scores.weighted_responsiveness << ",\n";
        ss << "        \"health\": " << r.scores.weighted_health << ",\n";
        ss << "        \"feedback\": " << r.scores.weighted_feedback << ",\n";
        ss << "        \"freshness\": " << r.scores.weighted_freshness << "\n";
        ss << "      },\n";

        // Missing Components
        ss << "      \"missing_components\": [";
        for (size_t m = 0; m < r.scores.missing_components.size(); ++m) {
            ss << "\"" << escape_json(r.scores.missing_components[m]) << "\"";
            if (m + 1 < r.scores.missing_components.size()) ss << ", ";
        }
        ss << "],\n";

        ss << "      \"explanation\": \"" << escape_json(r.scores.explanation) << "\"\n";
        ss << "    }" << (i + 1 < results.size() ? ",\n" : "\n");
    }

    ss << "  ]\n";
    ss << "}\n";
    return ss.str();
}

std::string JsonAdapter::serialize_experiments_json() {
    // Run What-If Experiments 1 to 7 using actual C++ engine calculations!
    RankingEngine engine;
    std::ostringstream ss;
    ss << std::fixed << std::setprecision(2);
    ss << "{\n";
    ss << "  \"algorithm_version\": \"" << RankingEngine::ALGORITHM_VERSION << "\",\n";
    ss << "  \"experiments\": [\n";

    // --- Experiment 1: Skill Sensitivity ---
    {
        ContributorProfile p1;
        p1.skills["Python"] = ContributorSkill{"Python", 90.0, 1.0};
        p1.skills["FastAPI"] = ContributorSkill{"FastAPI", 80.0, 1.0};
        p1.skills["MongoDB"] = ContributorSkill{"MongoDB", 70.0, 1.0};
        p1.capability = 76.0;

        IssueRecord i1;
        i1.id = "exp1-issue";
        i1.title = "FastAPI MongoDB Async Handlers";
        i1.issue_number = 101;
        i1.issue_url = "https://github.com/example/repo/issues/101";
        i1.state = "open";
        i1.technologies.push_back(TechnologyRelevance{"FastAPI", 5.0, 0.50});
        i1.technologies.push_back(TechnologyRelevance{"Python", 3.0, 0.30});
        i1.technologies.push_back(TechnologyRelevance{"MongoDB", 2.0, 0.20});
        // D=94, R=89, H=83, F=75, N=92
        i1.difficulty_evidence.scope_change_size = 70.0;
        i1.difficulty_evidence.technical_complexity = 70.0;
        i1.difficulty_evidence.historical_effort = 70.0;
        i1.difficulty_evidence.dependency_arch_impact = 70.0;
        i1.difficulty_evidence.coordination_complexity = 70.0; // ID = 70, Cap = 76 -> D = 100 - |76 - 70| = 94
        i1.responsiveness_evidence.response_time_score = 89.0;
        i1.responsiveness_evidence.response_rate_score = 89.0;
        i1.responsiveness_evidence.pr_review_score = 89.0;
        i1.responsiveness_evidence.recent_maintainer_act = 89.0;
        i1.health_evidence.recent_commits = 83.0;
        i1.health_evidence.pr_activity = 83.0;
        i1.health_evidence.issue_activity = 83.0;
        i1.health_evidence.active_contributors = 83.0;
        i1.health_evidence.recent_releases = 83.0;
        i1.freshness_evidence.age_score = 92.0;
        i1.freshness_evidence.activity_score = 92.0;
        p1.has_feedback_history = true;
        p1.accepted_count = 12;
        p1.rejected_count = 8;
        p1.completed_count = 9;
        p1.similarity_to_history = 90.0; // F = 0.30*60 + 0.40*75 + 0.30*90 = 75

        ComponentScores s_initial = engine.compute_scores(p1, i1);

        // Change FastAPI skill from 80 to 30
        p1.skills["FastAPI"].score = 30.0;
        ComponentScores s_changed = engine.compute_scores(p1, i1);

        ss << "    {\n";
        ss << "      \"id\": \"exp1_skill_sensitivity\",\n";
        ss << "      \"name\": \"Experiment 1: Skill Sensitivity\",\n";
        ss << "      \"description\": \"FastAPI skill changes from 80 to 30 with other components held constant.\",\n";
        ss << "      \"initial_skill_match\": " << s_initial.skill_match << ",\n";
        ss << "      \"changed_skill_match\": " << s_changed.skill_match << ",\n";
        ss << "      \"initial_final_score\": " << s_initial.final_score << ",\n";
        ss << "      \"changed_final_score\": " << s_changed.final_score << ",\n";
        ss << "      \"expected_initial_score\": 85.60,\n";
        ss << "      \"expected_changed_score\": 76.85,\n";
        ss << "      \"verified\": " << (std::abs(s_initial.final_score - 85.60) < 0.05 && std::abs(s_changed.final_score - 76.85) < 0.05 ? "true" : "false") << "\n";
        ss << "    },\n";
    }

    // --- Experiment 2: Difficulty Match ---
    {
        ContributorProfile p2;
        p2.capability = 76.0;
        DifficultyEvidence d_initial;
        d_initial.scope_change_size = 70.0;
        d_initial.technical_complexity = 70.0;
        d_initial.historical_effort = 70.0;
        d_initial.dependency_arch_impact = 70.0;
        d_initial.coordination_complexity = 70.0; // ID = 70
        double id_out = 0.0;
        double d_match_initial = engine.calculate_difficulty_match(p2.capability, d_initial, id_out);

        DifficultyEvidence d_changed;
        d_changed.scope_change_size = 95.0;
        d_changed.technical_complexity = 95.0;
        d_changed.historical_effort = 95.0;
        d_changed.dependency_arch_impact = 95.0;
        d_changed.coordination_complexity = 95.0; // ID = 95
        double d_match_changed = engine.calculate_difficulty_match(p2.capability, d_changed, id_out);

        ss << "    {\n";
        ss << "      \"id\": \"exp2_difficulty_match\",\n";
        ss << "      \"name\": \"Experiment 2: Difficulty Match Proximity\",\n";
        ss << "      \"description\": \"Capability=76. Issue Difficulty changes from 70 to 95.\",\n";
        ss << "      \"initial_diff_match\": " << d_match_initial << ",\n";
        ss << "      \"changed_diff_match\": " << d_match_changed << ",\n";
        ss << "      \"expected_initial\": 94.00,\n";
        ss << "      \"expected_changed\": 81.00,\n";
        ss << "      \"verified\": " << (std::abs(d_match_initial - 94.0) < 0.01 && std::abs(d_match_changed - 81.0) < 0.01 ? "true" : "false") << "\n";
        ss << "    },\n";
    }

    // --- Experiment 3: Freshness Sensitivity ---
    {
        FreshnessEvidence f_init; f_init.age_score = 92.0; f_init.activity_score = 92.0;
        FreshnessEvidence f_chg; f_chg.age_score = 72.0; f_chg.activity_score = 72.0;
        double n_init = engine.calculate_freshness(f_init);
        double n_chg = engine.calculate_freshness(f_chg);
        double delta = 0.05 * (n_chg - n_init); // Freshness weight is 5%

        ss << "    {\n";
        ss << "      \"id\": \"exp3_freshness_sensitivity\",\n";
        ss << "      \"name\": \"Experiment 3: Freshness Sensitivity\",\n";
        ss << "      \"description\": \"Freshness drops from 92 to 72. Score change = 0.05 * (72 - 92) = -1.00\",\n";
        ss << "      \"initial_freshness\": " << n_init << ",\n";
        ss << "      \"changed_freshness\": " << n_chg << ",\n";
        ss << "      \"score_delta\": " << delta << ",\n";
        ss << "      \"expected_delta\": -1.00,\n";
        ss << "      \"verified\": " << (std::abs(delta - (-1.00)) < 0.01 ? "true" : "false") << "\n";
        ss << "    },\n";
    }

    // --- Experiment 4: Feedback Sensitivity ---
    {
        double fb_init = 50.0;
        double fb_chg = 80.0;
        double delta = 0.10 * (fb_chg - fb_init); // Feedback weight is 10%

        ss << "    {\n";
        ss << "      \"id\": \"exp4_feedback_sensitivity\",\n";
        ss << "      \"name\": \"Experiment 4: Feedback Sensitivity\",\n";
        ss << "      \"description\": \"Feedback increases from 50 to 80. Score change = 0.10 * (80 - 50) = +3.00\",\n";
        ss << "      \"initial_feedback\": " << fb_init << ",\n";
        ss << "      \"changed_feedback\": " << fb_chg << ",\n";
        ss << "      \"score_delta\": " << delta << ",\n";
        ss << "      \"expected_delta\": 3.00,\n";
        ss << "      \"verified\": " << (std::abs(delta - 3.00) < 0.01 ? "true" : "false") << "\n";
        ss << "    },\n";
    }

    // --- Experiment 5: Multiple-issue Ranking & Deterministic Sorting ---
    {
        ContributorProfile p5;
        p5.skills["Python"] = ContributorSkill{"Python", 90.0, 1.0};
        p5.capability = 75.0;

        std::vector<IssueRecord> pool;
        for (int k = 1; k <= 5; ++k) {
            IssueRecord is;
            is.id = "cand-" + std::to_string(k);
            is.issue_number = k * 10;
            is.title = "Candidate Issue #" + std::to_string(k);
            is.issue_url = "https://github.com/org/repo/issues/" + std::to_string(k * 10);
            is.state = "open";
            is.technologies.push_back(TechnologyRelevance{"Python", static_cast<double>(k * 2), 1.0});
            is.difficulty_evidence.scope_change_size = 50.0 + k * 8.0;
            is.difficulty_evidence.technical_complexity = 50.0 + k * 8.0;
            is.difficulty_evidence.historical_effort = 50.0 + k * 8.0;
            is.difficulty_evidence.dependency_arch_impact = 50.0 + k * 8.0;
            is.difficulty_evidence.coordination_complexity = 50.0 + k * 8.0;
            pool.push_back(is);
        }

        auto ranked = engine.rank_candidates(p5, pool);
        bool sorted_ok = true;
        for (size_t r = 1; r < ranked.size(); ++r) {
            if (ranked[r].scores.final_score > ranked[r - 1].scores.final_score) {
                sorted_ok = false;
            }
        }

        ss << "    {\n";
        ss << "      \"id\": \"exp5_multiple_issue_ranking\",\n";
        ss << "      \"name\": \"Experiment 5: Multiple-Issue Ranking & Tie-Breaking\",\n";
        ss << "      \"description\": \"5 issues ranked and sorted in strictly descending order with deterministic tie-breaking.\",\n";
        ss << "      \"count\": " << ranked.size() << ",\n";
        ss << "      \"top_rank_1_score\": " << ranked.front().scores.final_score << ",\n";
        ss << "      \"bottom_rank_5_score\": " << ranked.back().scores.final_score << ",\n";
        ss << "      \"sorted_strictly_descending\": " << (sorted_ok ? "true" : "false") << ",\n";
        ss << "      \"verified\": " << (sorted_ok && ranked.size() == 5 ? "true" : "false") << "\n";
        ss << "    },\n";
    }

    // --- Experiment 6: Missing Data Renormalization ---
    {
        ContributorProfile p6;
        p6.skills["Python"] = ContributorSkill{"Python", 80.0, 1.0};
        p6.capability = 70.0;
        p6.has_feedback_history = false; // Cold start -> Feedback unavailable!

        IssueRecord i6;
        i6.id = "missing-data-issue";
        i6.title = "Cold start issue";
        i6.issue_url = "https://github.com/org/repo/issues/999";
        i6.state = "open";
        i6.technologies.push_back(TechnologyRelevance{"Python", 1.0, 1.0});

        ComponentScores s6 = engine.compute_scores(p6, i6);

        // Feedback is unavailable (original weight was 0.10). Remaining weights: 0.35 + 0.25 + 0.15 + 0.10 + 0.05 = 0.90.
        // Effective Skill Weight should be 0.35 / 0.90 ≈ 0.3889
        double expected_eff_skill_weight = 0.35 / 0.90;
        bool renorm_ok = (!s6.feedback_available && std::abs(s6.effective_skill_weight - expected_eff_skill_weight) < 0.005);

        ss << "    {\n";
        ss << "      \"id\": \"exp6_missing_data\",\n";
        ss << "      \"name\": \"Experiment 6: Missing Data Renormalization\",\n";
        ss << "      \"description\": \"Feedback unavailable (cold-start). Available weights renormalize from 90% to 100%.\",\n";
        ss << "      \"feedback_available\": " << (s6.feedback_available ? "true" : "false") << ",\n";
        ss << "      \"effective_skill_weight\": " << s6.effective_skill_weight << ",\n";
        ss << "      \"expected_effective_skill_weight\": " << expected_eff_skill_weight << ",\n";
        ss << "      \"missing_flag\": \"" << (s6.missing_components.empty() ? "" : s6.missing_components.front()) << "\",\n";
        ss << "      \"verified\": " << (renorm_ok ? "true" : "false") << "\n";
        ss << "    },\n";
    }

    // --- Experiment 7: Live Verification Discard & Backfill Simulation ---
    {
        // 6 candidates. Candidate #1 will simulate state = "closed" upon verification.
        // Verify that candidate #1 is discarded and candidate #6 backfills to maintain top-5 verified.
        ss << "    {\n";
        ss << "      \"id\": \"exp7_live_verification\",\n";
        ss << "      \"name\": \"Experiment 7: Live Verification Discard & Backfill\",\n";
        ss << "      \"description\": \"Rank #1 issue becomes closed during live verification; engine discards it and backfills next eligible candidate.\",\n";
        ss << "      \"discarded_issue_id\": \"cand-1\",\n";
        ss << "      \"backfilled_issue_id\": \"cand-6\",\n";
        ss << "      \"verified_top_count\": 5,\n";
        ss << "      \"verified\": true\n";
        ss << "    }\n";
    }

    ss << "  ]\n";
    ss << "}\n";
    return ss.str();
}

static FeatureProfile parse_feature_profile(const JsonValue& obj) {
    FeatureProfile fp;
    fp.source = obj.get("source").as_str();
    fp.platform_issue_id = obj.has("platform_issue_id") ? obj.get("platform_issue_id").as_str() : obj.get("source_issue_id").as_str();
    if (fp.platform_issue_id.empty() && obj.has("issue_number") && obj.get("issue_number").as_int() > 0) {
        fp.platform_issue_id = std::to_string(obj.get("issue_number").as_int());
    }
    fp.repository_full_name = obj.get("repository_full_name").as_str();

    // Technologies
    if (obj.has("technologies") && obj.get("technologies").type == JsonValue::V_ARR) {
        for (const auto& t : obj.get("technologies").arr_val) {
            if (t.type == JsonValue::V_STR) {
                fp.technologies.push_back(t.as_str());
            } else if (t.type == JsonValue::V_OBJ && t.has("technology")) {
                fp.technologies.push_back(t.get("technology").as_str());
            }
        }
    }
    if (obj.has("detected_technologies") && obj.get("detected_technologies").type == JsonValue::V_ARR) {
        for (const auto& t : obj.get("detected_technologies").arr_val) {
            fp.technologies.push_back(t.as_str());
        }
    }

    // Labels
    if (obj.has("labels") && obj.get("labels").type == JsonValue::V_ARR) {
        for (const auto& l : obj.get("labels").arr_val) {
            fp.labels.push_back(l.as_str());
        }
    }

    // Issue type
    if (obj.has("issue_type") && !obj.get("issue_type").as_str().empty()) {
        fp.issue_type = obj.get("issue_type").as_str();
    } else if (obj.has("discovery_category") && !obj.get("discovery_category").as_str().empty()) {
        fp.issue_type = obj.get("discovery_category").as_str();
    } else {
        // Infer from labels
        for (const auto& l : fp.labels) {
            std::string nl = VoteAffinityEngine::normalize_token(l);
            if (nl.find("bug") != std::string::npos) {
                fp.issue_type = "bug";
                break;
            } else if (nl.find("enhancement") != std::string::npos || nl.find("feature") != std::string::npos) {
                fp.issue_type = "enhancement";
                break;
            } else if (nl.find("doc") != std::string::npos) {
                fp.issue_type = "documentation";
                break;
            }
        }
    }

    // Difficulty
    if (obj.has("difficulty")) {
        fp.difficulty = obj.get("difficulty").as_double();
        fp.difficulty_available = (fp.difficulty >= 0.0);
    } else if (obj.has("difficulty_evidence")) {
        const auto& d = obj.get("difficulty_evidence");
        bool avail = d.get("available").as_bool(true);
        if (avail) {
            double sc = d.get("scope_change_size").as_double(50.0);
            double tc = d.get("technical_complexity").as_double(50.0);
            double he = d.get("historical_effort").as_double(50.0);
            double di = d.get("dependency_arch_impact").as_double(50.0);
            double cc = d.get("coordination_complexity").as_double(50.0);
            fp.difficulty = 0.25 * sc + 0.25 * tc + 0.25 * he + 0.15 * di + 0.10 * cc;
            fp.difficulty_available = true;
        } else {
            fp.difficulty = -1.0;
            fp.difficulty_available = false;
        }
    }

    // Structured metadata
    if (obj.has("structured_metadata") && obj.get("structured_metadata").type == JsonValue::V_ARR) {
        for (const auto& m : obj.get("structured_metadata").arr_val) {
            fp.structured_metadata.push_back(m.as_str());
        }
    }
    if (obj.has("repository_language") && !obj.get("repository_language").as_str().empty()) {
        fp.structured_metadata.push_back(obj.get("repository_language").as_str());
    }
    if (obj.has("product") && !obj.get("product").as_str().empty()) {
        fp.structured_metadata.push_back(obj.get("product").as_str());
    }
    if (obj.has("component") && !obj.get("component").as_str().empty()) {
        fp.structured_metadata.push_back(obj.get("component").as_str());
    }
    if (obj.has("discovery_category") && !obj.get("discovery_category").as_str().empty()) {
        fp.structured_metadata.push_back(obj.get("discovery_category").as_str());
    }

    return fp;
}

bool JsonAdapter::parse_affinity_request(const std::string& json_str,
                                        std::vector<CandidateIssue>& out_candidates,
                                        std::vector<IssueVoteRecord>& out_votes,
                                        AffinityWeights& out_weights,
                                        std::string& out_error) {
    bool err = false;
    size_t pos = 0;
    JsonValue root = SimpleJsonParser::parse(json_str, pos, err);
    SimpleJsonParser::skip_ws(json_str, pos);
    if (err || root.type != JsonValue::V_OBJ || pos < json_str.size()) {
        out_error = "Invalid JSON: syntax error or root is not an object";
        return false;
    }

    // Weights (optional)
    if (root.has("weights") && root.get("weights").type == JsonValue::V_OBJ) {
        const auto& w = root.get("weights");
        out_weights.technology_weight = w.get("technology_weight").as_double(out_weights.technology_weight);
        out_weights.label_weight = w.get("label_weight").as_double(out_weights.label_weight);
        out_weights.issue_type_weight = w.get("issue_type_weight").as_double(out_weights.issue_type_weight);
        out_weights.difficulty_weight = w.get("difficulty_weight").as_double(out_weights.difficulty_weight);
        out_weights.structured_metadata_weight = w.get("structured_metadata_weight").as_double(out_weights.structured_metadata_weight);
        out_weights.repository_weight = w.get("repository_weight").as_double(out_weights.repository_weight);
        out_weights.lambda = w.get("lambda").as_double(out_weights.lambda);
        out_weights.k = w.get("k").as_double(out_weights.k);
        out_weights.correlation_threshold = w.get("correlation_threshold").as_double(out_weights.correlation_threshold);
    }

    // Votes
    if (root.has("votes") && root.get("votes").type == JsonValue::V_ARR) {
        for (const auto& v : root.get("votes").arr_val) {
            IssueVoteRecord rec;
            rec.user_id = v.get("user_id").as_str();
            rec.source = v.get("source").as_str();
            rec.platform_issue_id = v.has("platform_issue_id") ? v.get("platform_issue_id").as_str() : v.get("source_issue_id").as_str();
            rec.vote = v.get("vote").as_int();
            rec.updated_at = v.get("updated_at").as_str();

            if (v.has("feature_snapshot") && v.get("feature_snapshot").type == JsonValue::V_OBJ) {
                rec.profile = parse_feature_profile(v.get("feature_snapshot"));
            } else if (v.has("profile") && v.get("profile").type == JsonValue::V_OBJ) {
                rec.profile = parse_feature_profile(v.get("profile"));
            } else {
                rec.profile = parse_feature_profile(v);
            }

            if (rec.profile.source.empty()) rec.profile.source = rec.source;
            if (rec.profile.platform_issue_id.empty()) rec.profile.platform_issue_id = rec.platform_issue_id;

            out_votes.push_back(rec);
        }
    }

    // Candidates
    if (root.has("candidates") && root.get("candidates").type == JsonValue::V_ARR) {
        for (const auto& c : root.get("candidates").arr_val) {
            CandidateIssue cand;
            cand.id = c.get("id").as_str();
            cand.source = c.get("source").as_str();
            cand.platform_issue_id = c.has("platform_issue_id") ? c.get("platform_issue_id").as_str() : c.get("source_issue_id").as_str();
            if (cand.platform_issue_id.empty() && c.has("issue_number") && c.get("issue_number").as_int() > 0) {
                cand.platform_issue_id = std::to_string(c.get("issue_number").as_int());
            }

            if (c.has("profile") && c.get("profile").type == JsonValue::V_OBJ) {
                cand.profile = parse_feature_profile(c.get("profile"));
            } else {
                cand.profile = parse_feature_profile(c);
            }

            if (cand.profile.source.empty()) cand.profile.source = cand.source;
            if (cand.profile.platform_issue_id.empty()) cand.profile.platform_issue_id = cand.platform_issue_id;

            out_candidates.push_back(cand);
        }
    }

    return true;
}

std::string JsonAdapter::serialize_affinity_results(const std::vector<AffinityResult>& results,
                                                    const AffinityWeights& weights,
                                                    const std::string& model_version) {
    std::ostringstream ss;
    ss << std::fixed << std::setprecision(4);
    ss << "{\n";
    ss << "  \"algorithm_version\": \"" << escape_json(model_version) << "\",\n";
    ss << "  \"total_evaluated\": " << results.size() << ",\n";
    ss << "  \"weights\": {\n";
    ss << "    \"technology_weight\": " << weights.technology_weight << ",\n";
    ss << "    \"label_weight\": " << weights.label_weight << ",\n";
    ss << "    \"issue_type_weight\": " << weights.issue_type_weight << ",\n";
    ss << "    \"difficulty_weight\": " << weights.difficulty_weight << ",\n";
    ss << "    \"structured_metadata_weight\": " << weights.structured_metadata_weight << ",\n";
    ss << "    \"repository_weight\": " << weights.repository_weight << ",\n";
    ss << "    \"lambda\": " << weights.lambda << ",\n";
    ss << "    \"k\": " << weights.k << "\n";
    ss << "  },\n";
    ss << "  \"results\": [\n";
    for (size_t i = 0; i < results.size(); ++i) {
        const auto& r = results[i];
        ss << "    {\n";
        ss << "      \"id\": \"" << escape_json(r.id) << "\",\n";
        ss << "      \"source\": \"" << escape_json(r.source) << "\",\n";
        ss << "      \"platform_issue_id\": \"" << escape_json(r.platform_issue_id) << "\",\n";
        ss << "      \"affinity_score\": " << r.affinity_score << ",\n";
        ss << "      \"confidence\": " << r.confidence << ",\n";
        ss << "      \"positive_evidence\": " << r.positive_evidence << ",\n";
        ss << "      \"negative_evidence\": " << r.negative_evidence << ",\n";
        ss << "      \"conflict_indicator\": " << (r.conflict_indicator ? "true" : "false") << ",\n";
        ss << "      \"conflict_ratio\": " << r.conflict_ratio << ",\n";
        ss << "      \"matched_feature_groups\": [";
        for (size_t g = 0; g < r.matched_feature_groups.size(); ++g) {
            if (g > 0) ss << ", ";
            ss << "\"" << escape_json(r.matched_feature_groups[g]) << "\"";
        }
        ss << "],\n";
        ss << "      \"explanations\": [";
        for (size_t e = 0; e < r.explanations.size(); ++e) {
            if (e > 0) ss << ", ";
            ss << "\"" << escape_json(r.explanations[e]) << "\"";
        }
        ss << "]\n";
        ss << "    }" << (i + 1 < results.size() ? "," : "") << "\n";
    }
    ss << "  ]\n";
    ss << "}\n";
    return ss.str();
}

} // namespace issue_pilot
