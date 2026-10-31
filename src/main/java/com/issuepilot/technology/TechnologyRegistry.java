package com.issuepilot.technology;

import com.issuepilot.model.TechnologyRelevance;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authoritative Technology Registry and Context-Aware Detection Engine
 * conforming strictly to Algorithm V2.3 Section 5.1.
 *
 * Rules:
 * - Versioned registry: TECHNOLOGY_REGISTRY_VERSION = "2.3.0"
 * - Whole-token or structured matching across evidence sources.
 * - Short aliases (C, R, Go, JS, TS) require contextual validation.
 * - Points per technology:
 *     1. Repository primary language match: 3 points (one per canonical technology)
 *     2. Whole-token title mention: 3 points (distinct title evidence)
 *     3. Code-fence language tag: 2 points (canonical language tag)
 *     4. Explicit file extension: 2 points (only when extension maps unambiguously)
 *     5. Curated technology label: 2 points (source-specific mapping)
 *     6. Body mention: 1 point each, max 2 (distinct sentences; context validated)
 *     7. Repository topic: 1 point (distinct topic evidence)
 * - Raw score: raw_t = min(5, sum(points_t))
 * - Retain only raw_t >= 1.
 * - Normalized relevance: rel_t = raw_t / sum_u(raw_u).
 * - If sum_u(raw_u) == 0, issue technology is unavailable (NO General fallback).
 * - Ties broken by raw_t descending then canonical technology name ascending (alphabetical).
 * - Relevance values sum strictly to 1.0.
 */
public final class TechnologyRegistry {

    public static final String TECHNOLOGY_REGISTRY_VERSION = "2.3.0";

    public record TechnologyDefinition(
            String canonicalName,
            String category,
            Set<String> aliases,
            Set<String> languageIds,
            Set<String> fileExtensions,
            Set<String> manifestFiles,
            Set<String> curatedLabels,
            boolean isShortAlias
    ) {}

    public record TechnologyScoreDetail(
            String technology,
            int primaryLanguagePoints,
            int titlePoints,
            int codeFencePoints,
            int fileExtensionPoints,
            int labelPoints,
            int bodyPoints,
            int topicPoints,
            double rawScore,
            double normalizedRelevance
    ) {}

    public record DetectionResult(
            boolean available,
            List<TechnologyRelevance> technologies,
            Map<String, TechnologyScoreDetail> scoreDetails
    ) {
        public static DetectionResult unavailable() {
            return new DetectionResult(false, Collections.emptyList(), Collections.emptyMap());
        }
    }

    private static final Map<String, TechnologyDefinition> REGISTRY = new LinkedHashMap<>();
    private static final Map<String, String> EXTENSION_TO_TECH = new HashMap<>();
    private static final Map<String, String> ALIAS_TO_TECH = new HashMap<>();
    private static final Map<String, String> MANIFEST_TO_TECH = new HashMap<>();

    static {
        register(new TechnologyDefinition(
                "Python", "language",
                Set.of("python", "py", "python3", "pytest", "pypi", "pip", "asyncio", "pydantic"),
                Set.of("python", "py", "python3"),
                Set.of(".py", ".pyw", ".pyi"),
                Set.of("requirements.txt", "pyproject.toml", "setup.py", "Pipfile"),
                Set.of("python", "lang:python", "area/python", "python3"),
                false
        ));

        register(new TechnologyDefinition(
                "JavaScript", "language",
                Set.of("javascript", "js", "node", "nodejs", "npm", "ecmascript", "vanilla-js"),
                Set.of("javascript", "js", "node"),
                Set.of(".js", ".mjs", ".cjs"),
                Set.of("package.json", "package-lock.json", "yarn.lock"),
                Set.of("javascript", "lang:javascript", "js", "node"),
                true // 'js' is a short alias
        ));

        register(new TechnologyDefinition(
                "TypeScript", "language",
                Set.of("typescript", "ts", "tsc"),
                Set.of("typescript", "ts"),
                Set.of(".ts", ".tsx"),
                Set.of("tsconfig.json"),
                Set.of("typescript", "lang:typescript", "ts"),
                true // 'ts' is a short alias
        ));

        register(new TechnologyDefinition(
                "Java", "language",
                Set.of("java", "jvm", "jdk", "maven", "gradle"),
                Set.of("java"),
                Set.of(".java", ".jar"),
                Set.of("pom.xml", "build.gradle", "build.gradle.kts"),
                Set.of("java", "lang:java", "jvm"),
                false
        ));

        register(new TechnologyDefinition(
                "C++", "language",
                Set.of("c++", "cpp", "cxx", "clang", "gcc", "stl", "boost"),
                Set.of("c++", "cpp", "cxx", "cc"),
                Set.of(".cpp", ".hpp", ".cxx", ".hxx", ".cc", ".hh"),
                Set.of("CMakeLists.txt"),
                Set.of("c++", "cpp", "lang:c++", "lang:cpp"),
                false
        ));

        register(new TechnologyDefinition(
                "C", "language",
                Set.of("c", "ansi-c", "c99", "c11", "glibc", "posix"),
                Set.of("c"),
                Set.of(".c", ".h"),
                Set.of("Makefile"),
                Set.of("lang:c", "c-lang"),
                true // 'c' is a short alias requiring strict context
        ));

        register(new TechnologyDefinition(
                "Go", "language",
                Set.of("go", "golang", "goroutine"),
                Set.of("go", "golang"),
                Set.of(".go"),
                Set.of("go.mod", "go.sum"),
                Set.of("go", "golang", "lang:go"),
                true // 'go' is an English word, requires strict context
        ));

        register(new TechnologyDefinition(
                "Rust", "language",
                Set.of("rust", "cargo", "crates", "rustc", "rustlang"),
                Set.of("rust", "rs"),
                Set.of(".rs"),
                Set.of("Cargo.toml", "Cargo.lock"),
                Set.of("rust", "lang:rust", "cargo"),
                false
        ));

        register(new TechnologyDefinition(
                "R", "language",
                Set.of("r", "rlang", "cran", "ggplot", "rstudio"),
                Set.of("r"),
                Set.of(".r", ".R"),
                Set.of("DESCRIPTION"),
                Set.of("lang:r", "r-project"),
                true // 'r' is a short alias requiring strict context
        ));

        register(new TechnologyDefinition(
                "React", "framework",
                Set.of("react", "reactjs", "react.js", "jsx"),
                Set.of("jsx", "tsx"),
                Set.of(".jsx"),
                Collections.emptySet(),
                Set.of("react", "reactjs", "area/react"),
                false
        ));

        register(new TechnologyDefinition(
                "Spring", "framework",
                Set.of("spring", "spring-boot", "springboot", "spring-data", "spring-mvc", "spring-security"),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of("spring", "spring-boot", "springboot"),
                false
        ));

        register(new TechnologyDefinition(
                "FastAPI", "framework",
                Set.of("fastapi", "uvicorn", "starlette"),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of("fastapi"),
                false
        ));

        register(new TechnologyDefinition(
                "Django", "framework",
                Set.of("django", "django-rest", "drf"),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of("django"),
                false
        ));

        register(new TechnologyDefinition(
                "Flask", "framework",
                Set.of("flask", "werkzeug", "jinja", "jinja2"),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of("flask"),
                false
        ));

        register(new TechnologyDefinition(
                "MongoDB", "database",
                Set.of("mongodb", "mongo", "pymongo", "mongoclient", "nosql"),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of("mongodb", "mongo"),
                false
        ));

        register(new TechnologyDefinition(
                "PostgreSQL", "database",
                Set.of("postgres", "postgresql", "psql", "psycopg", "pg"),
                Set.of("sql", "pgsql"),
                Set.of(".sql"),
                Collections.emptySet(),
                Set.of("postgres", "postgresql"),
                false
        ));

        register(new TechnologyDefinition(
                "Docker", "infrastructure",
                Set.of("docker", "dockerfile", "container", "containerd", "docker-compose"),
                Set.of("dockerfile"),
                Collections.emptySet(),
                Set.of("Dockerfile", "docker-compose.yml"),
                Set.of("docker", "container"),
                false
        ));

        register(new TechnologyDefinition(
                "Kubernetes", "infrastructure",
                Set.of("kubernetes", "k8s", "kubectl", "helm"),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of("kubernetes", "k8s"),
                false
        ));

        register(new TechnologyDefinition(
                "CMake", "tool",
                Set.of("cmake", "cmakelists"),
                Set.of("cmake"),
                Set.of(".cmake"),
                Set.of("CMakeLists.txt"),
                Set.of("cmake", "build-system"),
                false
        ));

        register(new TechnologyDefinition(
                "Linux", "infrastructure",
                Set.of("linux", "ubuntu", "debian", "fedora", "arch", "kernel", "systemd"),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of("linux", "kernel"),
                false
        ));
    }

    private static void register(TechnologyDefinition def) {
        REGISTRY.put(def.canonicalName(), def);
        for (String alias : def.aliases()) {
            ALIAS_TO_TECH.put(alias.toLowerCase(Locale.ROOT), def.canonicalName());
        }
        for (String ext : def.fileExtensions()) {
            // Only unambiguous extensions
            if (!EXTENSION_TO_TECH.containsKey(ext)) {
                EXTENSION_TO_TECH.put(ext.toLowerCase(Locale.ROOT), def.canonicalName());
            }
        }
        for (String mf : def.manifestFiles()) {
            if (!MANIFEST_TO_TECH.containsKey(mf.toLowerCase(Locale.ROOT))) {
                MANIFEST_TO_TECH.put(mf.toLowerCase(Locale.ROOT), def.canonicalName());
            }
        }
    }

    public static Map<String, TechnologyDefinition> getRegistry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    public static TechnologyDefinition getDefinition(String canonicalName) {
        return REGISTRY.get(canonicalName);
    }

    public static String resolveCanonicalName(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        for (TechnologyDefinition def : REGISTRY.values()) {
            if (def.canonicalName().equalsIgnoreCase(trimmed)) {
                return def.canonicalName();
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return ALIAS_TO_TECH.get(lower);
    }

    /**
     * Context-aware technology detection conforming strictly to Section 5.1.
     *
     * @param repositoryLanguage Primary language from repository metadata (can be null)
     * @param title              Issue title
     * @param body               Issue body/description
     * @param labels             Issue labels
     * @param topics             Repository topics
     */
    public static DetectionResult detect(
            String repositoryLanguage,
            String title,
            String body,
            List<String> labels,
            List<String> topics
    ) {
        Map<String, int[]> pointsMap = new LinkedHashMap<>(); // [0: primary, 1: title, 2: codeFence, 3: ext, 4: label, 5: body, 6: topic]

        for (String canonical : REGISTRY.keySet()) {
            pointsMap.put(canonical, new int[7]);
        }

        // 1. Repository Primary Language Match (3 points)
        if (repositoryLanguage != null && !repositoryLanguage.isBlank()) {
            String canonicalPrimary = resolveCanonicalName(repositoryLanguage);
            if (canonicalPrimary != null && pointsMap.containsKey(canonicalPrimary)) {
                pointsMap.get(canonicalPrimary)[0] = 3;
            }
        }

        // 2. Whole-Token Title Mention (3 points)
        if (title != null && !title.isBlank()) {
            for (TechnologyDefinition def : REGISTRY.values()) {
                if (matchesInTextWithContext(def, title, true)) {
                    pointsMap.get(def.canonicalName())[1] = 3;
                }
            }
        }

        // 3. Code-Fence Language Tags (2 points)
        if (body != null && !body.isBlank()) {
            Set<String> codeFenceTags = extractCodeFenceTags(body);
            for (String tag : codeFenceTags) {
                String canonical = resolveLanguageTag(tag);
                if (canonical != null && pointsMap.containsKey(canonical)) {
                    pointsMap.get(canonical)[2] = 2;
                }
            }

            // 4. Explicit File Extensions & Manifest Files (2 points)
            Set<String> extensions = extractFileExtensions(body);
            for (String ext : extensions) {
                String canonical = EXTENSION_TO_TECH.get(ext.toLowerCase(Locale.ROOT));
                if (canonical != null && pointsMap.containsKey(canonical)) {
                    pointsMap.get(canonical)[3] = 2;
                }
            }
            String lowerBody = body.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : MANIFEST_TO_TECH.entrySet()) {
                if (lowerBody.contains(entry.getKey())) {
                    pointsMap.get(entry.getValue())[3] = 2;
                }
            }

            // 6. Body Mention (1 each, max 2 points, across distinct sentences)
            List<String> sentences = splitIntoSentences(body);
            for (TechnologyDefinition def : REGISTRY.values()) {
                int sentenceHits = 0;
                for (String sentence : sentences) {
                    if (matchesInTextWithContext(def, sentence, false)) {
                        sentenceHits++;
                        if (sentenceHits >= 2) break;
                    }
                }
                pointsMap.get(def.canonicalName())[5] = Math.min(2, sentenceHits);
            }
        }

        // 5. Curated Technology Label (2 points)
        if (labels != null) {
            for (String label : labels) {
                if (label == null || label.isBlank()) continue;
                String normLabel = label.trim().toLowerCase(Locale.ROOT);
                for (TechnologyDefinition def : REGISTRY.values()) {
                    boolean labelMatch = false;
                    for (String curLabel : def.curatedLabels()) {
                        if (normLabel.equals(curLabel) || normLabel.endsWith("/" + curLabel) || normLabel.startsWith(curLabel + ":")) {
                            labelMatch = true;
                            break;
                        }
                    }
                    if (labelMatch) {
                        pointsMap.get(def.canonicalName())[4] = 2;
                    }
                }
            }
        }

        // 7. Repository Topic (1 point)
        if (topics != null) {
            for (String topic : topics) {
                if (topic == null || topic.isBlank()) continue;
                String canonical = resolveCanonicalName(topic);
                if (canonical != null && pointsMap.containsKey(canonical)) {
                    pointsMap.get(canonical)[6] = 1;
                }
            }
        }

        // Aggregate points and retain raw_t >= 1
        Map<String, Double> rawScores = new LinkedHashMap<>();
        Map<String, TechnologyScoreDetail> scoreDetails = new LinkedHashMap<>();
        double sumRaw = 0.0;

        for (Map.Entry<String, int[]> entry : pointsMap.entrySet()) {
            String tech = entry.getKey();
            int[] pts = entry.getValue();
            int sumPoints = pts[0] + pts[1] + pts[2] + pts[3] + pts[4] + pts[5] + pts[6];
            double raw = Math.min(5.0, (double) sumPoints);

            if (raw >= 1.0) {
                rawScores.put(tech, raw);
                sumRaw += raw;
                scoreDetails.put(tech, new TechnologyScoreDetail(
                        tech, pts[0], pts[1], pts[2], pts[3], pts[4], pts[5], pts[6], raw, 0.0
                ));
            }
        }

        if (sumRaw <= 0.0 || rawScores.isEmpty()) {
            return DetectionResult.unavailable();
        }

        // Normalize relevance: rel_t = raw_t / sum_u(raw_u)
        List<TechnologyRelevance> results = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rawScores.entrySet()) {
            String tech = entry.getKey();
            double raw = entry.getValue();
            double rel = raw / sumRaw;
            TechnologyRelevance tr = new TechnologyRelevance(tech, raw);
            tr.setNormalizedRelevance(rel);
            results.add(tr);

            TechnologyScoreDetail detail = scoreDetails.get(tech);
            if (detail != null) {
                scoreDetails.put(tech, new TechnologyScoreDetail(
                        tech, detail.primaryLanguagePoints(), detail.titlePoints(),
                        detail.codeFencePoints(), detail.fileExtensionPoints(),
                        detail.labelPoints(), detail.bodyPoints(), detail.topicPoints(),
                        raw, rel
                ));
            }
        }

        // Tie-breaking: raw_t descending, then canonical technology name ascending
        results.sort((a, b) -> {
            int cmp = Double.compare(b.getRawRelevance(), a.getRawRelevance());
            if (cmp != 0) return cmp;
            return a.getTechnology().compareTo(b.getTechnology());
        });

        return new DetectionResult(true, results, scoreDetails);
    }

    /**
     * Checks if text contains technology mention respecting whole-token boundaries and context rules.
     */
    private static boolean matchesInTextWithContext(TechnologyDefinition def, String text, boolean isTitle) {
        if (def.isShortAlias()) {
            return matchesShortAliasWithContext(def, text);
        }

        // Canonical name or primary aliases
        for (String alias : def.aliases()) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(alias.toLowerCase(Locale.ROOT)) + "\\b", Pattern.CASE_INSENSITIVE);
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesShortAliasWithContext(TechnologyDefinition def, String text) {
        String canonical = def.canonicalName();

        if ("Go".equals(canonical)) {
            // Check for unambiguous keywords first: 'golang', 'goroutine', 'go.mod', 'go.sum'
            if (Pattern.compile("\\bgolang\\b|\\bgoroutine\\b|\\bgo\\.mod\\b|\\bgo\\.sum\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                return true;
            }
            // If checking "Go", require context qualification to reject English "go"
            // e.g. "in Go", "Go 1.", "Go language", "Go compiler", "Go runtime", "Go package", "Go module", "Go SDK", "Go code"
            Pattern goContext = Pattern.compile(
                    "(?:\\bin\\s+Go\\b|\\bGo\\s+language\\b|\\bGo\\s+1\\.[0-9]+|\\bGo\\s+(?:compiler|runtime|package|module|sdk|code|struct|routine|channel|pointer|interface)\\b)",
                    Pattern.CASE_INSENSITIVE
            );
            return goContext.matcher(text).find();
        }

        if ("C".equals(canonical)) {
            // Must not match arbitrary 'C'
            if (Pattern.compile("\\bansi-c\\b|\\bc99\\b|\\bc11\\b|\\bglibc\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                return true;
            }
            Pattern cContext = Pattern.compile(
                    "(?:\\bin\\s+C\\b|\\bC/C\\+\\+|\\bC\\s+language\\b|\\bC\\s+(?:compiler|header|library|code|preprocessor|macro|struct|standard|runtime)\\b)",
                    Pattern.CASE_INSENSITIVE
            );
            return cContext.matcher(text).find();
        }

        if ("R".equals(canonical)) {
            if (Pattern.compile("\\brlang\\b|\\bcran\\b|\\bggplot\\b|\\brstudio\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                return true;
            }
            Pattern rContext = Pattern.compile(
                    "(?:\\bin\\s+R\\b|\\bR\\s+language\\b|\\bR\\s+(?:package|script|library|code|session|data\\.frame)\\b)",
                    Pattern.CASE_INSENSITIVE
            );
            return rContext.matcher(text).find();
        }

        if ("JavaScript".equals(canonical)) {
            if (Pattern.compile("\\bjavascript\\b|\\bnodejs\\b|\\bnode\\.js\\b|\\becmascript\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                return true;
            }
            // Short alias 'js' requires contextual qualification in prose
            Pattern jsContext = Pattern.compile(
                    "(?:\\bin\\s+JS\\b|\\bJS\\s+code\\b|\\bJS\\s+bundle\\b|\\bJS\\s+engine\\b|\\bJS\\s+runtime\\b|\\bvanilla\\s+JS\\b|\\bNode\\s+JS\\b|\\bJS/TS\\b|\\bTS/JS\\b)",
                    Pattern.CASE_INSENSITIVE
            );
            return jsContext.matcher(text).find();
        }

        if ("TypeScript".equals(canonical)) {
            if (Pattern.compile("\\btypescript\\b|\\btsc\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) {
                return true;
            }
            // Short alias 'ts' requires contextual qualification in prose (to reject 'ts' = timestamp / time series)
            Pattern tsContext = Pattern.compile(
                    "(?:\\bin\\s+TS\\b|\\bTS\\s+types\\b|\\bTS\\s+compiler\\b|\\bTS\\s+config\\b|\\bTS\\s+file\\b|\\bTS\\s+code\\b|\\bTS\\s+interface\\b|\\bJS/TS\\b|\\bTS/JS\\b)",
                    Pattern.CASE_INSENSITIVE
            );
            return tsContext.matcher(text).find();
        }

        return false;
    }

    private static Set<String> extractCodeFenceTags(String text) {
        Set<String> tags = new HashSet<>();
        Pattern fencePattern = Pattern.compile("```([a-zA-Z0-9_+#-]+)");
        Matcher matcher = fencePattern.matcher(text);
        while (matcher.find()) {
            tags.add(matcher.group(1).trim().toLowerCase(Locale.ROOT));
        }
        return tags;
    }

    private static String resolveLanguageTag(String tag) {
        for (TechnologyDefinition def : REGISTRY.values()) {
            for (String langId : def.languageIds()) {
                if (langId.equalsIgnoreCase(tag)) {
                    return def.canonicalName();
                }
            }
        }
        return null;
    }

    private static Set<String> extractFileExtensions(String text) {
        Set<String> exts = new HashSet<>();
        // Matches tokens ending in known extensions like file.py or src/main.rs
        Pattern filePattern = Pattern.compile("[a-zA-Z0-9_/-]+(\\.[a-zA-Z0-9]{1,5})\\b");
        Matcher matcher = filePattern.matcher(text);
        while (matcher.find()) {
            String ext = matcher.group(1).toLowerCase(Locale.ROOT);
            if (EXTENSION_TO_TECH.containsKey(ext)) {
                exts.add(ext);
            }
        }
        return exts;
    }

    private static List<String> splitIntoSentences(String text) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        String[] parts = text.split("(?<=[.!?\\n])\\s+");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) list.add(trimmed);
        }
        return list;
    }
}


