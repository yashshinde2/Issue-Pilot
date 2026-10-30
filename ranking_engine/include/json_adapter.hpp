#pragma once

#include "types.hpp"
#include "ranking_engine.hpp"
#include "vote_affinity.hpp"
#include <string>
#include <vector>
#include <map>
#include <cctype>
#include <stdexcept>

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

    // Parse affinity evaluation payload containing candidate issues and user vote records
    static bool parse_affinity_request(const std::string& json_str,
                                       std::vector<CandidateIssue>& out_candidates,
                                       std::vector<IssueVoteRecord>& out_votes,
                                       AffinityWeights& out_weights,
                                       std::string& out_error);

    // Serialize affinity results to JSON
    static std::string serialize_affinity_results(const std::vector<AffinityResult>& results,
                                                  const AffinityWeights& weights,
                                                  const std::string& model_version);

    // Serialize what-if experiments output to JSON
    static std::string serialize_experiments_json();
};

} // namespace issue_pilot
