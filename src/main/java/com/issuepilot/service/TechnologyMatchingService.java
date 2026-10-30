package com.issuepilot.service;

import com.issuepilot.adapter.TechnologyDetectionHelper;
import com.issuepilot.controller.RecommendationController.RecommendationRequest;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.repository.IssueRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service managing technology matching, preference integration, candidate prioritization,
 * and explainable recommendation annotations for Phase 6.
 *
 * Adheres strictly to:
 * - Priority 1: User-confirmed skills.
 * - Priority 2: Inferred skills (lower confidence, never treated as confirmed or proficiency).
 * - Priority 3: Explicit user discovery preferences.
 * - Clear distinction between hard filters and soft preferences.
 * - Preservation of V1 ranking engine contract and formula.
 */
@Service
public class TechnologyMatchingService {

    public enum MatchPriority {
        CONFIRMED_SKILL,
        INFERRED_SKILL,
        DISCOVERY_PREFERENCE
    }

    public static class TechnologyMatch {
        private final String technology;
        private final MatchPriority priority;
        private final Double proficiencyScore;
        private final String level;
        private final Double confidence;
        private final String explanation;

        public TechnologyMatch(
                String technology,
                MatchPriority priority,
                Double proficiencyScore,
                String level,
                Double confidence,
                String explanation
        ) {
            this.technology = technology;
            this.priority = priority;
            this.proficiencyScore = proficiencyScore;
            this.level = level;
            this.confidence = confidence;
            this.explanation = explanation;
        }

        public String getTechnology() { return technology; }
        public MatchPriority getPriority() { return priority; }
        public Double getProficiencyScore() { return proficiencyScore; }
        public String getLevel() { return level; }
        public Double getConfidence() { return confidence; }
        public String getExplanation() { return explanation; }
    }

    /**
     * Extracts normalized confirmed skills mapped by canonical technology name.
     * Dismissed skills are strictly excluded.
     */
    public Map<String, ContributorSkill> getConfirmedSkills(ContributorProfile profile) {
        if (profile == null || profile.getSkills() == null) {
            return Collections.emptyMap();
        }
        Set<String> dismissed = getNormalizedDismissedSkills(profile);
        Map<String, ContributorSkill> confirmed = new LinkedHashMap<>();

        for (Map.Entry<String, ContributorSkill> entry : profile.getSkills().entrySet()) {
            ContributorSkill skill = entry.getValue();
            if (skill == null) continue;
            String rawTech = skill.getTechnology() != null ? skill.getTechnology() : entry.getKey();
            String norm = TechnologyDetectionHelper.normalizeTechnology(rawTech);
            if (norm == null || dismissed.contains(norm)) continue;

            confirmed.put(norm, skill);
        }
        return Collections.unmodifiableMap(confirmed);
    }

    /**
     * Extracts normalized inferred skills mapped by canonical technology name.
     * Excludes dismissed skills, skills already confirmed, and skills marked dismissed.
     */
    public Map<String, InferredSkill> getInferredSkills(ContributorProfile profile) {
        if (profile == null || profile.getInferredSkills() == null) {
            return Collections.emptyMap();
        }
        Set<String> dismissed = getNormalizedDismissedSkills(profile);
        Map<String, ContributorSkill> confirmed = getConfirmedSkills(profile);
        Map<String, InferredSkill> inferred = new LinkedHashMap<>();

        for (InferredSkill skill : profile.getInferredSkills()) {
            if (skill == null || "dismissed".equalsIgnoreCase(skill.getStatus())) continue;
            String norm = TechnologyDetectionHelper.normalizeTechnology(skill.getTechnology());
            if (norm == null || dismissed.contains(norm) || confirmed.containsKey(norm)) continue;

            // Retain the highest confidence entry if duplicates exist
            InferredSkill existing = inferred.get(norm);
            if (existing == null || skill.getConfidence() > existing.getConfidence()) {
                inferred.put(norm, skill);
            }
        }
        return Collections.unmodifiableMap(inferred);
    }

    /**
     * Extracts normalized discovery technology preferences from user profile.
     * Excludes dismissed skills and technologies already confirmed.
     */
    public Set<String> getPreferredTechnologies(ContributorProfile profile) {
        if (profile == null || profile.getPreferences() == null) {
            return Collections.emptySet();
        }
        Set<String> dismissed = getNormalizedDismissedSkills(profile);
        Map<String, ContributorSkill> confirmed = getConfirmedSkills(profile);
        Set<String> preferred = new LinkedHashSet<>();

        List<String> prefKeys = List.of(
                "preferred_technologies", "technologies", "tech_stack", "languages", "preferred_languages"
        );

        Map<String, Object> prefs = profile.getPreferences();
        for (String key : prefKeys) {
            Object val = prefs.get(key);
            if (val instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        String norm = TechnologyDetectionHelper.normalizeTechnology(item.toString());
                        if (norm != null && !dismissed.contains(norm) && !confirmed.containsKey(norm)) {
                            preferred.add(norm);
                        }
                    }
                }
            } else if (val instanceof String str) {
                for (String token : str.split("[,; ]+")) {
                    String norm = TechnologyDetectionHelper.normalizeTechnology(token);
                    if (norm != null && !dismissed.contains(norm) && !confirmed.containsKey(norm)) {
                        preferred.add(norm);
                    }
                }
            }
        }
        return Collections.unmodifiableSet(preferred);
    }

    /**
     * Extracts canonical dismissed technology identifiers.
     */
    public Set<String> getNormalizedDismissedSkills(ContributorProfile profile) {
        if (profile == null || profile.getDismissedSkills() == null) {
            return Collections.emptySet();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String d : profile.getDismissedSkills()) {
            if (d != null && !d.isBlank()) {
                String norm = TechnologyDetectionHelper.normalizeTechnology(d);
                set.add(norm != null ? norm : d.trim());
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Extracts all detected technologies from an issue's existing metadata.
     * Does NOT infer technology matches from arbitrary words in the title.
     */
    public Set<String> getIssueTechnologies(Map<String, Object> issue) {
        if (issue == null) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();

        // 1. detected_technologies list
        Object dtObj = issue.get("detected_technologies");
        if (dtObj instanceof List<?> dtList) {
            for (Object item : dtList) {
                if (item != null) {
                    String norm = TechnologyDetectionHelper.normalizeTechnology(item.toString());
                    if (norm != null) result.add(norm);
                }
            }
        }

        // 2. technologies array with relevance
        Object techObj = issue.get("technologies");
        if (techObj instanceof List<?> techList) {
            for (Object item : techList) {
                if (item instanceof Map<?, ?> tm) {
                    Object tName = tm.get("technology");
                    if (tName != null) {
                        String norm = TechnologyDetectionHelper.normalizeTechnology(tName.toString());
                        if (norm != null) result.add(norm);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Matches an issue's technology metadata against the contributor's profile,
     * following the strict priority order:
     * 1. User-confirmed skills
     * 2. Inferred skills (lower-confidence)
     * 3. Explicit discovery preferences
     */
    public List<TechnologyMatch> matchTechnologies(Map<String, Object> issue, ContributorProfile profile) {
        if (issue == null || profile == null) {
            return Collections.emptyList();
        }

        Set<String> issueTechs = getIssueTechnologies(issue);
        if (issueTechs.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, ContributorSkill> confirmed = getConfirmedSkills(profile);
        Map<String, InferredSkill> inferred = getInferredSkills(profile);
        Set<String> preferred = getPreferredTechnologies(profile);

        List<TechnologyMatch> matches = new ArrayList<>();
        Set<String> matchedTechs = new HashSet<>();

        // Priority 1: User-confirmed skills
        for (String it : issueTechs) {
            if (confirmed.containsKey(it) && !matchedTechs.contains(it)) {
                ContributorSkill cs = confirmed.get(it);
                String explanation = "Matches a confirmed skill: " + it;
                if (cs.getLevel() != null && !cs.getLevel().isBlank()) {
                    explanation += " (" + cs.getLevel() + ")";
                }
                matches.add(new TechnologyMatch(
                        it,
                        MatchPriority.CONFIRMED_SKILL,
                        cs.getScore(),
                        cs.getLevel(),
                        1.0,
                        explanation
                ));
                matchedTechs.add(it);
            }
        }

        // Priority 2: Inferred skills (lower confidence, not confirmed)
        for (String it : issueTechs) {
            if (inferred.containsKey(it) && !matchedTechs.contains(it)) {
                InferredSkill is = inferred.get(it);
                int confPercent = (int) Math.round(is.getConfidence() * 100);
                String explanation = "Related to an inferred skill: " + it + " (" + confPercent + "% confidence)";
                matches.add(new TechnologyMatch(
                        it,
                        MatchPriority.INFERRED_SKILL,
                        null, // Never use confidence as proficiency score!
                        null,
                        is.getConfidence(),
                        explanation
                ));
                matchedTechs.add(it);
            }
        }

        // Priority 3: Explicit discovery preferences
        for (String it : issueTechs) {
            if (preferred.contains(it) && !matchedTechs.contains(it)) {
                String explanation = "Matches a selected discovery preference: " + it;
                matches.add(new TechnologyMatch(
                        it,
                        MatchPriority.DISCOVERY_PREFERENCE,
                        null,
                        null,
                        0.5,
                        explanation
                ));
                matchedTechs.add(it);
            }
        }

        return matches;
    }

    /**
     * Determines whether an issue violates any active hard filter.
     * Hard filters include:
     * - Request technology_filter
     * - Request source_filter
     * - Request min_difficulty / max_difficulty
     * - Profile preferences with hard_filters enabled
     */
    public boolean violatesHardFilters(
            Map<String, Object> issue,
            ContributorProfile profile,
            RecommendationRequest req
    ) {
        if (issue == null) return true;

        // 1. Request Hard Filter: Technology
        if (req != null && req.technology_filter != null && !req.technology_filter.isBlank()) {
            String normReqTech = TechnologyDetectionHelper.normalizeTechnology(req.technology_filter);
            Set<String> issueTechs = getIssueTechnologies(issue);
            boolean matched = false;
            for (String it : issueTechs) {
                if (it.equalsIgnoreCase(normReqTech)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return true;
        }

        // 2. Request Hard Filter: Source / Platform
        if (req != null && req.source_filter != null && !req.source_filter.isBlank() && !"all".equalsIgnoreCase(req.source_filter)) {
            String issueSource = (String) issue.get("source");
            if (issueSource == null || !issueSource.equalsIgnoreCase(req.source_filter.trim())) {
                return true;
            }
        }

        // 3. Request Hard Filter: Difficulty range
        Double minDiff = req != null ? req.min_difficulty : null;
        Double maxDiff = req != null ? req.max_difficulty : null;
        if (minDiff != null || maxDiff != null) {
            Double complexity = extractTechnicalComplexity(issue);
            if (complexity != null) {
                if (minDiff != null && complexity < minDiff) return true;
                if (maxDiff != null && complexity > maxDiff) return true;
            }
        }

        // 4. User Profile Hard Filters (strict mode)
        if (profile != null && profile.getPreferences() != null) {
            Object hardFiltersObj = profile.getPreferences().get("hard_filters");
            Set<String> activeHardFilters = parseHardFilters(hardFiltersObj);

            if (activeHardFilters.contains("technologies") || activeHardFilters.contains("technology")) {
                Set<String> allowedTechs = new LinkedHashSet<>();
                allowedTechs.addAll(getConfirmedSkills(profile).keySet());
                allowedTechs.addAll(getPreferredTechnologies(profile));

                if (!allowedTechs.isEmpty()) {
                    Set<String> issueTechs = getIssueTechnologies(issue);
                    boolean match = false;
                    for (String it : issueTechs) {
                        if (allowedTechs.contains(it)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) return true;
                }
            }

            if (activeHardFilters.contains("sources") || activeHardFilters.contains("platforms")) {
                Object sourcesObj = profile.getPreferences().get("preferred_sources");
                if (sourcesObj instanceof List<?> srcList && !srcList.isEmpty()) {
                    String issueSource = (String) issue.get("source");
                    boolean match = false;
                    for (Object s : srcList) {
                        if (s != null && s.toString().equalsIgnoreCase(issueSource)) {
                            match = true;
                            break;
                        }
                    }
                    if (!match) return true;
                }
            }
        }

        return false;
    }

    private Set<String> parseHardFilters(Object obj) {
        Set<String> set = new HashSet<>();
        if (obj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) set.add(item.toString().toLowerCase().trim());
            }
        } else if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    set.add(entry.getKey().toString().toLowerCase().trim());
                }
            }
        }
        return set;
    }

    private Double extractTechnicalComplexity(Map<String, Object> issue) {
        Object diffEv = issue.get("difficulty_evidence");
        if (diffEv instanceof Map<?, ?> dm) {
            Object tc = dm.get("technical_complexity");
            if (tc instanceof Number num) return num.doubleValue();
        }
        return null;
    }

    /**
     * Prioritizes and deduplicates an in-memory candidate pool (e.g. sample or pre-fetched issues).
     */
    public List<Map<String, Object>> prioritizeCandidates(
            List<Map<String, Object>> candidatePool,
            ContributorProfile profile,
            RecommendationRequest req,
            int targetLimit
    ) {
        if (candidatePool == null || candidatePool.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> priority1Confirmed = new ArrayList<>();
        List<Map<String, Object>> priority2Inferred = new ArrayList<>();
        List<Map<String, Object>> priority3Preferred = new ArrayList<>();
        List<Map<String, Object>> generalBackfill = new ArrayList<>();

        Set<String> seenIds = new HashSet<>();

        for (Map<String, Object> candidate : candidatePool) {
            if (candidate == null) continue;
            String id = (String) candidate.get("id");
            if (id == null || seenIds.contains(id)) continue;

            if (violatesHardFilters(candidate, profile, req)) {
                continue;
            }

            List<TechnologyMatch> matches = matchTechnologies(candidate, profile);
            if (!matches.isEmpty()) {
                TechnologyMatch topMatch = matches.get(0);
                if (topMatch.getPriority() == MatchPriority.CONFIRMED_SKILL) {
                    priority1Confirmed.add(candidate);
                } else if (topMatch.getPriority() == MatchPriority.INFERRED_SKILL) {
                    priority2Inferred.add(candidate);
                } else {
                    priority3Preferred.add(candidate);
                }
            } else {
                generalBackfill.add(candidate);
            }
            seenIds.add(id);
        }

        // Assemble ordered candidate pool: Confirmed -> Inferred -> Preferred -> General Backfill
        List<Map<String, Object>> prioritized = new ArrayList<>();
        prioritized.addAll(priority1Confirmed);
        prioritized.addAll(priority2Inferred);
        prioritized.addAll(priority3Preferred);
        prioritized.addAll(generalBackfill);

        if (targetLimit > 0 && prioritized.size() > targetLimit) {
            return new ArrayList<>(prioritized.subList(0, targetLimit));
        }
        return prioritized;
    }

    /**
     * Resolves the candidate pool from MongoDB Atlas using single-query multi-technology lookup
     * to avoid N+1 queries, while respecting hard filters and soft preferences.
     */
    public List<Map<String, Object>> fetchCandidatePool(
            IssueRepository issueRepository,
            ContributorProfile profile,
            RecommendationRequest req,
            int targetLimit
    ) {
        if (issueRepository == null) return Collections.emptyList();

        Map<String, ContributorSkill> confirmed = getConfirmedSkills(profile);
        Map<String, InferredSkill> inferred = getInferredSkills(profile);
        Set<String> preferred = getPreferredTechnologies(profile);

        Set<String> targetTechs = new LinkedHashSet<>();
        // Priority 1
        targetTechs.addAll(confirmed.keySet());
        // Priority 2
        targetTechs.addAll(inferred.keySet());
        // Priority 3
        targetTechs.addAll(preferred);

        // If explicit technology_filter is set in request, it overrides soft technology search
        if (req != null && req.technology_filter != null && !req.technology_filter.isBlank()) {
            targetTechs = Set.of(TechnologyDetectionHelper.normalizeTechnology(req.technology_filter));
        }

        String sourceFilter = req != null ? req.source_filter : null;
        Double minDiff = req != null ? req.min_difficulty : null;
        Double maxDiff = req != null ? req.max_difficulty : null;

        List<Map<String, Object>> fetched;
        if (!targetTechs.isEmpty()) {
            // Perform single multi-tech query avoiding N+1 round-trips
            fetched = issueRepository.queryIssuesMultiTech(
                    "open",
                    false,
                    targetTechs,
                    minDiff,
                    maxDiff,
                    null,
                    sourceFilter,
                    targetLimit,
                    0
            );
        } else {
            fetched = issueRepository.queryIssues(
                    "open",
                    false,
                    null,
                    minDiff,
                    maxDiff,
                    null,
                    sourceFilter,
                    targetLimit,
                    0
            );
        }

        // If matching candidates are fewer than target and soft preferences allow backfill, fetch general open issues
        boolean isHardTechFilter = (req != null && req.technology_filter != null && !req.technology_filter.isBlank());
        if (profile != null && profile.getPreferences() != null) {
            Set<String> hardFilters = parseHardFilters(profile.getPreferences().get("hard_filters"));
            if (hardFilters.contains("technologies") || hardFilters.contains("technology")) {
                isHardTechFilter = true;
            }
        }

        if (fetched.size() < targetLimit && !isHardTechFilter) {
            int needed = targetLimit - fetched.size();
            List<Map<String, Object>> backfill = issueRepository.queryIssues(
                    "open",
                    false,
                    null,
                    minDiff,
                    maxDiff,
                    null,
                    sourceFilter,
                    needed * 2,
                    0
            );
            Set<String> existingIds = new HashSet<>();
            for (Map<String, Object> item : fetched) {
                existingIds.add((String) item.get("id"));
            }
            for (Map<String, Object> bf : backfill) {
                String id = (String) bf.get("id");
                if (id != null && !existingIds.contains(id) && !violatesHardFilters(bf, profile, req)) {
                    fetched.add(bf);
                    existingIds.add(id);
                    if (fetched.size() >= targetLimit) break;
                }
            }
        }

        return prioritizeCandidates(fetched, profile, req, targetLimit);
    }

    /**
     * Enriches a recommendation result map with clear, human-readable match explanations
     * and match reason lists without modifying internal ranking scores.
     */
    public void enrichWithExplanations(Map<String, Object> recommendation, ContributorProfile profile) {
        if (recommendation == null || profile == null) return;

        List<TechnologyMatch> matches = matchTechnologies(recommendation, profile);
        List<String> reasons = new ArrayList<>();
        List<String> techReasons = new ArrayList<>();

        for (TechnologyMatch m : matches) {
            reasons.add(m.getExplanation());
            techReasons.add(m.getTechnology());
        }

        if (reasons.isEmpty()) {
            reasons.add("General open issue matching your experience level");
        }

        recommendation.put("match_reasons", reasons);
        recommendation.put("technology_match_reasons", techReasons);

        // Append to existing explanation if scores map is present
        Object scoresObj = recommendation.get("scores");
        if (scoresObj instanceof Map<?, ?> scMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> writableScores = (Map<String, Object>) scMap;
            writableScores.put("technology_match_reasons", reasons);
        }
    }
}

