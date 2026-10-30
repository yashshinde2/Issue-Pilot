#include "../include/technology_registry.hpp"
#include <algorithm>
#include <regex>
#include <sstream>

namespace issue_pilot {

static std::unordered_map<std::string, TechnologyDefinition> s_registry;
static std::unordered_map<std::string, std::string> s_alias_map;
static std::unordered_map<std::string, std::string> s_ext_map;
static bool s_initialized = false;


void TechnologyRegistry::init_registry() {
    if (s_initialized) return;

    auto reg = [](TechnologyDefinition def) {
        std::string c_lower = to_lower_str(def.canonical_name);
        s_registry[def.canonical_name] = def;
        s_alias_map[c_lower] = def.canonical_name;
        for (const auto& a : def.aliases) {
            s_alias_map[to_lower_str(a)] = def.canonical_name;
        }
        for (const auto& ext : def.file_extensions) {
            std::string ext_lower = to_lower_str(ext);
            if (s_ext_map.find(ext_lower) == s_ext_map.end()) {
                s_ext_map[ext_lower] = def.canonical_name;
            }
        }
    };

    reg({"Python", "language", {"python", "py", "python3", "pytest", "pypi", "pip", "asyncio", "pydantic"}, {"python", "py", "python3"}, {".py", ".pyw", ".pyi"}, {"requirements.txt", "pyproject.toml"}, {"python", "lang:python", "area/python"}, false});
    reg({"JavaScript", "language", {"javascript", "js", "node", "nodejs", "npm", "ecmascript"}, {"javascript", "js", "node"}, {".js", ".mjs", ".cjs"}, {"package.json"}, {"javascript", "lang:javascript", "js", "node"}, true});
    reg({"TypeScript", "language", {"typescript", "ts", "tsc"}, {"typescript", "ts"}, {".ts", ".tsx"}, {"tsconfig.json"}, {"typescript", "lang:typescript", "ts"}, true});
    reg({"Java", "language", {"java", "jvm", "jdk", "maven", "gradle"}, {"java"}, {".java", ".jar"}, {"pom.xml", "build.gradle"}, {"java", "lang:java", "jvm"}, false});
    reg({"C++", "language", {"c++", "cpp", "cxx", "clang", "gcc", "stl", "boost"}, {"c++", "cpp", "cxx", "cc"}, {".cpp", ".hpp", ".cxx", ".hxx", ".cc", ".hh"}, {"CMakeLists.txt"}, {"c++", "cpp", "lang:c++", "lang:cpp"}, false});
    reg({"C", "language", {"c", "ansi-c", "c99", "c11", "glibc", "posix"}, {"c"}, {".c", ".h"}, {"Makefile"}, {"lang:c", "c-lang"}, true});
    reg({"Go", "language", {"go", "golang", "goroutine"}, {"go", "golang"}, {".go"}, {"go.mod", "go.sum"}, {"go", "golang", "lang:go"}, true});
    reg({"Rust", "language", {"rust", "cargo", "crates", "rustc", "rustlang"}, {"rust", "rs"}, {".rs"}, {"Cargo.toml", "Cargo.lock"}, {"rust", "lang:rust", "cargo"}, false});
    reg({"R", "language", {"r", "rlang", "cran", "ggplot", "rstudio"}, {"r"}, {".r", ".R"}, {"DESCRIPTION"}, {"lang:r", "r-project"}, true});
    reg({"React", "framework", {"react", "reactjs", "react.js", "jsx"}, {"jsx", "tsx"}, {".jsx"}, {}, {"react", "reactjs", "area/react"}, false});
    reg({"Spring", "framework", {"spring", "spring-boot", "springboot", "spring-data", "spring-mvc"}, {}, {}, {}, {"spring", "spring-boot", "springboot"}, false});
    reg({"FastAPI", "framework", {"fastapi", "uvicorn", "starlette"}, {}, {}, {}, {"fastapi"}, false});
    reg({"Django", "framework", {"django", "django-rest", "drf"}, {}, {}, {}, {"django"}, false});
    reg({"Flask", "framework", {"flask", "werkzeug", "jinja", "jinja2"}, {}, {}, {}, {"flask"}, false});
    reg({"MongoDB", "database", {"mongodb", "mongo", "pymongo", "mongoclient", "nosql"}, {}, {}, {}, {"mongodb", "mongo"}, false});
    reg({"PostgreSQL", "database", {"postgres", "postgresql", "psql", "psycopg", "pg"}, {"sql", "pgsql"}, {".sql"}, {}, {"postgres", "postgresql"}, false});
    reg({"Docker", "infrastructure", {"docker", "dockerfile", "container", "containerd", "docker-compose"}, {"dockerfile"}, {}, {"Dockerfile", "docker-compose.yml"}, {"docker", "container"}, false});
    reg({"Kubernetes", "infrastructure", {"kubernetes", "k8s", "kubectl", "helm"}, {}, {}, {}, {"kubernetes", "k8s"}, false});
    reg({"CMake", "tool", {"cmake", "cmakelists"}, {"cmake"}, {".cmake"}, {"CMakeLists.txt"}, {"cmake", "build-system"}, false});
    reg({"Linux", "infrastructure", {"linux", "ubuntu", "debian", "fedora", "arch", "kernel", "systemd"}, {}, {}, {}, {"linux", "kernel"}, false});

    s_initialized = true;
}

const std::unordered_map<std::string, TechnologyDefinition>& TechnologyRegistry::get_registry() {
    init_registry();
    return s_registry;
}

std::string TechnologyRegistry::resolve_canonical_name(const std::string& raw) {
    init_registry();
    std::string lower = to_lower_str(raw);
    auto it = s_alias_map.find(lower);
    if (it != s_alias_map.end()) return it->second;
    return "";
}

bool TechnologyRegistry::matches_short_alias_with_context(const TechnologyDefinition& def, const std::string& text) {
    std::string canonical = def.canonical_name;
    std::string lower = to_lower_str(text);

    if (canonical == "Go") {
        if (std::regex_search(text, std::regex("\\b(golang|goroutine|go\\.mod|go\\.sum)\\b", std::regex_constants::icase))) {
            return true;
        }
        std::regex go_context("(?:\\bin\\s+Go\\b|\\bGo\\s+language\\b|\\bGo\\s+1\\.[0-9]+|\\bGo\\s+(?:compiler|runtime|package|module|sdk|code|struct|routine|channel|pointer|interface)\\b)", std::regex_constants::icase);
        return std::regex_search(text, go_context);
    }

    if (canonical == "C") {
        if (std::regex_search(text, std::regex("\\b(ansi-c|c99|c11|glibc)\\b", std::regex_constants::icase))) {
            return true;
        }
        std::regex c_context("(?:\\bin\\s+C\\b|\\bC/C\\+\\+|\\bC\\s+language\\b|\\bC\\s+(?:compiler|header|library|code|preprocessor|macro|struct|standard|runtime)\\b)", std::regex_constants::icase);
        return std::regex_search(text, c_context);
    }

    if (canonical == "R") {
        if (std::regex_search(text, std::regex("\\b(rlang|cran|ggplot|rstudio)\\b", std::regex_constants::icase))) {
            return true;
        }
        std::regex r_context("(?:\\bin\\s+R\\b|\\bR\\s+language\\b|\\bR\\s+(?:package|script|library|code|session|data\\.frame)\\b)", std::regex_constants::icase);
        return std::regex_search(text, r_context);
    }

    if (canonical == "JavaScript") {
        if (std::regex_search(text, std::regex("\\b(javascript|node|nodejs|ecmascript)\\b", std::regex_constants::icase))) {
            return true;
        }
        return std::regex_search(text, std::regex("\\bjs\\b", std::regex_constants::icase));
    }

    if (canonical == "TypeScript") {
        if (std::regex_search(text, std::regex("\\b(typescript|tsc)\\b", std::regex_constants::icase))) {
            return true;
        }
        return std::regex_search(text, std::regex("\\bts\\b", std::regex_constants::icase));
    }

    return false;
}

bool TechnologyRegistry::matches_with_context(const TechnologyDefinition& def, const std::string& text) {
    if (def.is_short_alias) {
        return matches_short_alias_with_context(def, text);
    }
    for (const auto& a : def.aliases) {
        std::string pattern = "\\b" + a + "\\b";
        try {
            if (std::regex_search(text, std::regex(pattern, std::regex_constants::icase))) {
                return true;
            }
        } catch (...) {
            if (to_lower_str(text).find(to_lower_str(a)) != std::string::npos) {
                return true;
            }
        }
    }
    return false;
}

TechnologyDetectionResult TechnologyRegistry::detect(
    const std::string& repository_language,
    const std::string& title,
    const std::string& body,
    const std::vector<std::string>& labels,
    const std::vector<std::string>& topics
) {
    init_registry();
    TechnologyDetectionResult result;

    struct Points {
        int primary{0};
        int title{0};
        int code_fence{0};
        int ext{0};
        int label{0};
        int body{0};
        int topic{0};
    };
    std::unordered_map<std::string, Points> points_map;

    for (const auto& kv : s_registry) {
        points_map[kv.first] = Points{};
    }

    // 1. Primary language (3 points)
    if (!repository_language.empty()) {
        std::string canonical = resolve_canonical_name(repository_language);
        if (!canonical.empty()) {
            points_map[canonical].primary = 3;
        }
    }

    // 2. Title (3 points)
    if (!title.empty()) {
        for (const auto& kv : s_registry) {
            if (matches_with_context(kv.second, title)) {
                points_map[kv.first].title = 3;
            }
        }
    }

    // 3. Code fences (2 points) & 4. File extensions (2 points) & 6. Body mentions (max 2 points)
    if (!body.empty()) {
        // Code fence tags
        std::regex fence_re("```([a-zA-Z0-9_+#-]+)");
        std::smatch m;
        std::string body_copy = body;
        while (std::regex_search(body_copy, m, fence_re)) {
            std::string tag = to_lower_str(m[1].str());
            for (const auto& kv : s_registry) {
                for (const auto& lid : kv.second.language_ids) {
                    if (to_lower_str(lid) == tag) {
                        points_map[kv.first].code_fence = 2;
                        break;
                    }
                }
            }
            body_copy = m.suffix().str();
        }

        // File extensions
        std::regex ext_re("[a-zA-Z0-9_/-]+(\\.[a-zA-Z0-9]{1,5})\\b");
        body_copy = body;
        while (std::regex_search(body_copy, m, ext_re)) {
            std::string ext = to_lower_str(m[1].str());
            auto it = s_ext_map.find(ext);
            if (it != s_ext_map.end()) {
                points_map[it->second].ext = 2;
            }
            body_copy = m.suffix().str();
        }

        // Sentences in body
        std::vector<std::string> sentences;
        std::stringstream ss(body);
        std::string line;
        while (std::getline(ss, line)) {
            if (!line.empty()) sentences.push_back(line);
        }

        for (const auto& kv : s_registry) {
            int hits = 0;
            for (const auto& sent : sentences) {
                if (matches_with_context(kv.second, sent)) {
                    hits++;
                    if (hits >= 2) break;
                }
            }
            points_map[kv.first].body = std::min(2, hits);
        }
    }

    // 5. Curated Labels (2 points)
    for (const auto& label : labels) {
        std::string norm = to_lower_str(label);
        for (const auto& kv : s_registry) {
            bool matched = false;
            for (const auto& cl : kv.second.curated_labels) {
                if (norm == cl || norm.find("/" + cl) != std::string::npos || norm.find(cl + ":") != std::string::npos) {
                    matched = true;
                    break;
                }
            }
            if (matched) {
                points_map[kv.first].label = 2;
            }
        }
    }

    // 7. Topics (1 point)
    for (const auto& topic : topics) {
        std::string canonical = resolve_canonical_name(topic);
        if (!canonical.empty()) {
            points_map[canonical].topic = 1;
        }
    }

    // Accumulate raw scores
    double sum_raw = 0.0;
    std::unordered_map<std::string, double> raw_scores;

    for (const auto& kv : points_map) {
        const std::string& tech = kv.first;
        const Points& p = kv.second;
        int total = p.primary + p.title + p.code_fence + p.ext + p.label + p.body + p.topic;
        double raw = std::min(5.0, static_cast<double>(total));
        if (raw >= 1.0) {
            raw_scores[tech] = raw;
            sum_raw += raw;
            TechnologyScoreDetail detail;
            detail.technology = tech;
            detail.primary_language_points = p.primary;
            detail.title_points = p.title;
            detail.code_fence_points = p.code_fence;
            detail.file_extension_points = p.ext;
            detail.label_points = p.label;
            detail.body_points = p.body;
            detail.topic_points = p.topic;
            detail.raw_score = raw;
            detail.normalized_relevance = 0.0;
            result.score_details[tech] = detail;
        }
    }

    if (sum_raw <= 0.0 || raw_scores.empty()) {
        result.available = false;
        return result;
    }

    result.available = true;
    for (const auto& kv : raw_scores) {
        double rel = kv.second / sum_raw;
        TechnologyRelevance tr;
        tr.technology = kv.first;
        tr.raw_relevance = kv.second;
        tr.normalized_relevance = rel;
        result.technologies.push_back(tr);
        result.score_details[kv.first].normalized_relevance = rel;
    }

    // Tie-break sorting: raw_relevance descending, then canonical name ascending
    std::sort(result.technologies.begin(), result.technologies.end(), [](const TechnologyRelevance& a, const TechnologyRelevance& b) {
        if (std::abs(a.raw_relevance - b.raw_relevance) > 1e-6) {
            return a.raw_relevance > b.raw_relevance;
        }
        return a.technology < b.technology;
    });

    return result;
}

} // namespace issue_pilot
