package com.issuepilot.adapter;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public final class TechnologyDetectionHelper {

    private static final Map<String, List<String>> TECH_PATTERNS = new LinkedHashMap<>();

    static {
        TECH_PATTERNS.put("python", Arrays.asList("python", "py", "pytest", "pypi", "pip", "asyncio", "pydantic"));
        TECH_PATTERNS.put("fastapi", Arrays.asList("fastapi", "uvicorn", "starlette"));
        TECH_PATTERNS.put("flask", Arrays.asList("flask", "werkzeug", "jinja", "jinja2"));
        TECH_PATTERNS.put("django", Arrays.asList("django", "django-rest", "drf"));
        TECH_PATTERNS.put("react", Arrays.asList("react", "jsx", "tsx", "redux", "nextjs", "vite", "frontend"));
        TECH_PATTERNS.put("typescript", Arrays.asList("typescript", "ts", "tsc", "typing", "types"));
        TECH_PATTERNS.put("javascript", Arrays.asList("javascript", "js", "node", "nodejs", "npm", "ecmascript"));
        TECH_PATTERNS.put("c++", Arrays.asList("c++", "cpp", "cxx", "clang", "gcc", "stl", "boost", "cmake"));
        TECH_PATTERNS.put("c", Arrays.asList("\\bc\\b", "glibc", "posix", "kernel"));
        TECH_PATTERNS.put("rust", Arrays.asList("rust", "cargo", "crates", "rustc"));
        TECH_PATTERNS.put("go", Arrays.asList("golang", "\\bgo\\b", "goroutine"));
        TECH_PATTERNS.put("java", Arrays.asList("java", "jvm", "jdk", "gradle", "maven", "spring"));
        TECH_PATTERNS.put("spring", Arrays.asList("spring", "spring-boot", "springboot", "spring-data"));
        TECH_PATTERNS.put("mongodb", Arrays.asList("mongodb", "mongo", "pymongo", "mongoclient", "nosql"));
        TECH_PATTERNS.put("postgresql", Arrays.asList("postgres", "postgresql", "psycopg", "sql"));
        TECH_PATTERNS.put("docker", Arrays.asList("docker", "container", "dockerfile", "containerd"));
        TECH_PATTERNS.put("kubernetes", Arrays.asList("kubernetes", "k8s", "helm", "kubectl"));
        TECH_PATTERNS.put("cmake", Arrays.asList("cmake", "cmakelists", "build-system"));
        TECH_PATTERNS.put("linux", Arrays.asList("linux", "ubuntu", "debian", "fedora", "arch"));
        TECH_PATTERNS.put("documentation", Arrays.asList("docs", "documentation", "docstring", "tutorial", "readme", "guide"));
    }

    private TechnologyDetectionHelper() {}

    public static List<Map<String, Object>> detectTechnologies(String text, List<String> labels) {
        String combined = (text != null ? text.toLowerCase() : "") + " " + (labels != null ? String.join(" ", labels).toLowerCase() : "");
        Map<String, Double> detected = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : TECH_PATTERNS.entrySet()) {
            String tech = entry.getKey();
            for (String kw : entry.getValue()) {
                if (kw.startsWith("\\b")) {
                    if (Pattern.compile(kw, Pattern.CASE_INSENSITIVE).matcher(combined).find()) {
                        detected.put(capitalize(tech), detected.getOrDefault(capitalize(tech), 0.0) + 2.0);
                        break;
                    }
                } else if (combined.contains(kw.toLowerCase())) {
                    detected.put(capitalize(tech), detected.getOrDefault(capitalize(tech), 0.0) + 2.0);
                    break;
                }
            }
        }

        if (detected.isEmpty()) {
            detected.put("General", 1.0);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Double> e : detected.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("technology", e.getKey());
            item.put("raw_relevance", Math.min(5.0, e.getValue()));
            list.add(item);
        }
        return list;
    }

    public static Map<String, Object> estimateDifficulty(String title, String body, List<String> labels, int comments) {
        List<String> labelList = labels != null ? labels : Collections.emptyList();

        boolean isGoodFirst = labelList.stream().anyMatch(l -> {
            String s = l.toLowerCase();
            return s.contains("good first") || s.contains("beginner") || s.contains("easy") || s.contains("starter") || s.contains("help wanted");
        });

        boolean isComplex = labelList.stream().anyMatch(l -> {
            String s = l.toLowerCase();
            return s.contains("hard") || s.contains("epic") || s.contains("breaking") || s.contains("architecture") || s.contains("refactor") || s.contains("security");
        });

        double scope = isGoodFirst ? 30.0 : (isComplex ? 80.0 : 50.0);
        double complexity = isGoodFirst ? 35.0 : (isComplex ? 85.0 : 50.0);
        double effort = isGoodFirst ? 30.0 : (isComplex ? 75.0 : 50.0);
        double arch = isGoodFirst ? 25.0 : (isComplex ? 80.0 : 50.0);
        double coord = isGoodFirst ? 25.0 : (isComplex ? 70.0 : 40.0);

        if (comments > 15) {
            effort = Math.min(100.0, effort + 20.0);
            coord = Math.min(100.0, coord + 20.0);
        } else if (comments > 5) {
            effort = Math.min(100.0, effort + 10.0);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope_change_size", scope);
        m.put("technical_complexity", complexity);
        m.put("historical_effort", effort);
        m.put("dependency_arch_impact", arch);
        m.put("coordination_complexity", coord);
        return m;
    }

    public static Map<String, Object> computeFreshness(String createdAt, String updatedAt) {
        double ageScore = 70.0;
        double actScore = 70.0;

        try {
            Instant now = Instant.now();
            if (createdAt != null && !createdAt.isBlank()) {
                Instant c = Instant.parse(createdAt);
                long days = Duration.between(c, now).toDays();
                ageScore = Math.max(10.0, 100.0 - (days * 1.5));
            }
            if (updatedAt != null && !updatedAt.isBlank()) {
                Instant u = Instant.parse(updatedAt);
                long days = Duration.between(u, now).toDays();
                actScore = Math.max(10.0, 100.0 - (days * 2.5));
            }
        } catch (Exception ignored) {
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", true);
        m.put("age_score", Math.min(100.0, Math.max(0.0, ageScore)));
        m.put("activity_score", Math.min(100.0, Math.max(0.0, actScore)));
        return m;
    }

    public static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        if (str.equalsIgnoreCase("c++") || str.equalsIgnoreCase("cpp")) return "C++";
        if (str.equalsIgnoreCase("fastapi")) return "FastAPI";
        if (str.equalsIgnoreCase("mongodb")) return "MongoDB";
        if (str.equalsIgnoreCase("postgresql")) return "PostgreSQL";
        if (str.equalsIgnoreCase("javascript")) return "JavaScript";
        if (str.equalsIgnoreCase("typescript")) return "TypeScript";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
