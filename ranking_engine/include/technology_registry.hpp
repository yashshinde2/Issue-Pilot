#ifndef ISSUE_PILOT_TECHNOLOGY_REGISTRY_HPP
#define ISSUE_PILOT_TECHNOLOGY_REGISTRY_HPP

#include "types.hpp"
#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>

namespace issue_pilot {

struct TechnologyDefinition {
    std::string canonical_name;
    std::string category;
    std::vector<std::string> aliases;
    std::vector<std::string> language_ids;
    std::vector<std::string> file_extensions;
    std::vector<std::string> manifest_files;
    std::vector<std::string> curated_labels;
    bool is_short_alias{false};
};

struct TechnologyScoreDetail {
    std::string technology;
    int primary_language_points{0};
    int title_points{0};
    int code_fence_points{0};
    int file_extension_points{0};
    int label_points{0};
    int body_points{0};
    int topic_points{0};
    double raw_score{0.0};
    double normalized_relevance{0.0};
};

struct TechnologyDetectionResult {
    bool available{false};
    std::vector<TechnologyRelevance> technologies;
    std::unordered_map<std::string, TechnologyScoreDetail> score_details;
};

class TechnologyRegistry {
public:
    static constexpr const char* VERSION = "2.3.0";

    static const std::unordered_map<std::string, TechnologyDefinition>& get_registry();
    static std::string resolve_canonical_name(const std::string& raw);

    static TechnologyDetectionResult detect(
        const std::string& repository_language,
        const std::string& title,
        const std::string& body,
        const std::vector<std::string>& labels,
        const std::vector<std::string>& topics
    );

private:
    static void init_registry();
    static bool matches_with_context(const TechnologyDefinition& def, const std::string& text);
    static bool matches_short_alias_with_context(const TechnologyDefinition& def, const std::string& text);
};

} // namespace issue_pilot

#endif // ISSUE_PILOT_TECHNOLOGY_REGISTRY_HPP

