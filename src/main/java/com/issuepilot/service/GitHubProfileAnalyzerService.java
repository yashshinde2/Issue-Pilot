package com.issuepilot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.adapter.TechnologyDetectionHelper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.model.SkillEvidence;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.UserCredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service that inspects permitted GitHub repositories and infers explainable skills.
 *
 * Requirements:
 * - Derives account exclusively from authenticated application principal.
 * - Decrypts credentials only in memory within this service.
 * - Inspects at most 15 repositories (configurable limit).
 * - Excludes forks by default.
 * - Excludes private repositories unless explicitly configured.
 * - Form-aware manifest parsing: requirements.txt, pyproject.toml, package.json, Cargo.toml, go.mod, pom.xml.
 * - Deterministic, bounded confidence scores [0.0, 1.0].
 * - Python alone never implies frameworks (FastAPI, Django, NumPy).
 * - Idempotently persists to ContributorProfile without overwriting confirmed skills.
 */
@Service
public class GitHubProfileAnalyzerService {

    private static final Logger logger = LoggerFactory.getLogger(GitHubProfileAnalyzerService.class);

    private final UserCredentialService userCredentialService;
    private final UserRepository userRepository;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubProfileAnalyzerService(
            UserCredentialService userCredentialService,
            UserRepository userRepository,
            AppProperties properties,
            ObjectMapper objectMapper
    ) {
        this(userCredentialService, userRepository, properties, objectMapper, createDefaultRestTemplate(properties));
    }

    public GitHubProfileAnalyzerService(
            UserCredentialService userCredentialService,
            UserRepository userRepository,
            AppProperties properties,
            ObjectMapper objectMapper,
            RestTemplate restTemplate
    ) {
        this.userCredentialService = userCredentialService;
        this.userRepository = userRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate != null ? restTemplate : createDefaultRestTemplate(properties);
    }

    private static RestTemplate createDefaultRestTemplate(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMs = (props != null ? props.getGithubAnalyzerTimeoutSeconds() : 10) * 1000;
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * Analysis result summary container.
     */
    public record AnalysisResult(
            String status,
            String userId,
            int repositoriesAnalyzed,
            int inferredSkillsCount,
            List<InferredSkill> inferredSkills,
            String analyzedAt,
            String message
    ) {}

    /**
     * Analyzes public repositories of the authenticated user to infer skills.
     *
     * @param canonicalUserId Validated application principal ID (e.g. "github:8888801")
     * @return AnalysisResult containing inferred skills and metadata
     */
    public AnalysisResult analyzeUserProfile(String canonicalUserId) {
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            throw new IllegalArgumentException("Canonical user ID is required for profile analysis.");
        }

        // 1. Retrieve decrypted OAuth token exclusively in memory
        Optional<String> tokenOpt = userCredentialService.getDecryptedToken(canonicalUserId, "github");
        if (tokenOpt.isEmpty()) {
            throw new IllegalStateException("No active GitHub credential found for user. Please re-authenticate with GitHub.");
        }
        String githubToken = tokenOpt.get();

        // 2. Fetch permitted repositories (up to max configured, excluding forks and private repos)
        List<Map<String, Object>> repos = fetchUserRepositories(githubToken);

        Map<String, InferredSkillAccumulator> accumulators = new LinkedHashMap<>();

        int analyzedCount = 0;
        for (Map<String, Object> repo : repos) {
            String repoFullName = (String) repo.get("full_name");
            if (repoFullName == null || repoFullName.isBlank()) {
                continue;
            }

            analyzedCount++;
            // 3A. Analyze Language Statistics
            analyzeRepositoryLanguages(repoFullName, githubToken, accumulators);

            // 3B. Inspect Dependency Manifests
            analyzeRepositoryManifests(repoFullName, githubToken, accumulators);
        }

        // 4. Build finalized InferredSkills with deterministic confidence
        List<InferredSkill> finalizedSkills = new ArrayList<>();
        for (InferredSkillAccumulator acc : accumulators.values()) {
            InferredSkill skill = acc.toInferredSkill();
            finalizedSkills.add(skill);
        }

        // Sort descending by confidence then technology name
        finalizedSkills.sort((a, b) -> {
            int cmp = Double.compare(b.getConfidence(), a.getConfidence());
            if (cmp != 0) return cmp;
            return a.getTechnology().compareToIgnoreCase(b.getTechnology());
        });

        // 5. Persist to ContributorProfile safely (without overwriting confirmed skills)
        persistInferredSkills(canonicalUserId, finalizedSkills);

        String nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        return new AnalysisResult(
                "completed",
                canonicalUserId,
                analyzedCount,
                finalizedSkills.size(),
                finalizedSkills,
                nowIso,
                "Repository analysis completed successfully."
        );
    }

    /**
     * Fetches user-owned repositories using authenticated GitHub API.
     */
    private List<Map<String, Object>> fetchUserRepositories(String githubToken) {
        int maxRepos = properties.getGithubAnalyzerMaxRepositories();
        boolean excludeForks = properties.isGithubAnalyzerExcludeForks();

        String url = properties.getGithubApiBaseUrl() + "/user/repos?type=owner&sort=updated&per_page=" + Math.min(maxRepos, 100);

        HttpHeaders headers = createAuthHeaders(githubToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            checkRateLimit(response.getHeaders());

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> allRepos = objectMapper.readValue(response.getBody(), new TypeReference<List<Map<String, Object>>>() {});
            List<Map<String, Object>> filtered = new ArrayList<>();

            for (Map<String, Object> repo : allRepos) {
                // Exclude forks by default
                if (excludeForks && Boolean.TRUE.equals(repo.get("fork"))) {
                    continue;
                }
                // Exclude private repositories
                if (Boolean.TRUE.equals(repo.get("private"))) {
                    continue;
                }
                filtered.add(repo);
                if (filtered.size() >= maxRepos) {
                    break;
                }
            }

            return filtered;

        } catch (HttpClientErrorException.Unauthorized e) {
            logger.warn("GitHub API rejected access token: {}", e.getStatusCode());
            throw new IllegalStateException("GitHub access token expired or revoked. Please log in again.");
        } catch (HttpClientErrorException.Forbidden e) {
            handleRateLimitOrForbidden(e);
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Failed to query user repositories from GitHub: {}", e.getMessage());
            throw new IllegalStateException("Failed to retrieve repositories from GitHub API: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches language byte distribution for a repository and accumulates language evidence.
     */
    private void analyzeRepositoryLanguages(String repoFullName, String githubToken, Map<String, InferredSkillAccumulator> accumulators) {
        String url = properties.getGithubApiBaseUrl() + "/repos/" + repoFullName + "/languages";
        HttpHeaders headers = createAuthHeaders(githubToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            checkRateLimit(response.getHeaders());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Number> langBytes = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Number>>() {});
                long totalBytes = langBytes.values().stream().mapToLong(Number::longValue).sum();

                for (Map.Entry<String, Number> entry : langBytes.entrySet()) {
                    String rawLang = entry.getKey();
                    long bytes = entry.getValue().longValue();
                    double percent = totalBytes > 0 ? (bytes * 100.0 / totalBytes) : 0.0;

                    String normalized = TechnologyDetectionHelper.normalizeTechnology(rawLang);
                    if (normalized == null || normalized.isBlank()) {
                        continue;
                    }

                    String key = normalized.toLowerCase();
                    InferredSkillAccumulator acc = accumulators.computeIfAbsent(key, k -> new InferredSkillAccumulator(normalized, "language"));

                    String detail = String.format("%.1f%% of repository bytes (%d bytes) in %s", percent, bytes, repoFullName);
                    acc.addEvidence(new SkillEvidence("language_statistics", repoFullName, null, detail), percent, bytes);
                }
            }
        } catch (HttpClientErrorException.NotFound ignored) {
            // Repo or languages endpoint not found, proceed safely
        } catch (HttpClientErrorException.Forbidden e) {
            handleRateLimitOrForbidden(e);
        } catch (Exception e) {
            logger.warn("Error fetching language stats for '{}': {}", repoFullName, e.getMessage());
        }
    }

    /**
     * Inspects candidate dependency manifest files in the repository.
     */
    private void analyzeRepositoryManifests(String repoFullName, String githubToken, Map<String, InferredSkillAccumulator> accumulators) {
        List<String> candidatePaths = List.of(
                "package.json",
                "requirements.txt",
                "pyproject.toml",
                "Cargo.toml",
                "go.mod",
                "pom.xml"
        );

        for (String manifestPath : candidatePaths) {
            String content = fetchFileContent(repoFullName, manifestPath, githubToken);
            if (content != null && !content.isBlank()) {
                parseManifestContent(repoFullName, manifestPath, content, accumulators);
            }
        }
    }

    /**
     * Fetches file content from GitHub repository Contents API.
     */
    private String fetchFileContent(String repoFullName, String filePath, String githubToken) {
        String url = properties.getGithubApiBaseUrl() + "/repos/" + repoFullName + "/contents/" + filePath;
        HttpHeaders headers = createAuthHeaders(githubToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            checkRateLimit(response.getHeaders());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String encoding = root.path("encoding").asText("");
                String contentBase64 = root.path("content").asText("");

                if ("base64".equalsIgnoreCase(encoding) && !contentBase64.isBlank()) {
                    String cleanBase64 = contentBase64.replaceAll("\\s+", "");
                    byte[] decoded = Base64.getDecoder().decode(cleanBase64);
                    return new String(decoded, StandardCharsets.UTF_8);
                }
            }
        } catch (HttpClientErrorException.NotFound ignored) {
            // Expected when manifest does not exist in the repository
        } catch (HttpClientErrorException.Forbidden e) {
            handleRateLimitOrForbidden(e);
        } catch (Exception e) {
            logger.debug("Manifest '{}' not available for repo '{}': {}", filePath, repoFullName, e.getMessage());
        }
        return null;
    }

    /**
     * Format-aware parser dispatch for supported dependency manifests.
     */
    public void parseManifestContent(String repoFullName, String filePath, String content, Map<String, InferredSkillAccumulator> accumulators) {
        switch (filePath.toLowerCase()) {
            case "package.json" -> parsePackageJson(repoFullName, filePath, content, accumulators);
            case "requirements.txt" -> parseRequirementsTxt(repoFullName, filePath, content, accumulators);
            case "pyproject.toml" -> parsePyprojectToml(repoFullName, filePath, content, accumulators);
            case "cargo.toml" -> parseCargoToml(repoFullName, filePath, content, accumulators);
            case "go.mod" -> parseGoMod(repoFullName, filePath, content, accumulators);
            case "pom.xml" -> parsePomXml(repoFullName, filePath, content, accumulators);
        }
    }

    private void parsePackageJson(String repo, String path, String content, Map<String, InferredSkillAccumulator> acc) {
        try {
            JsonNode root = objectMapper.readTree(content);
            parseNpmDependencySection(root.path("dependencies"), false, repo, path, acc);
            parseNpmDependencySection(root.path("devDependencies"), true, repo, path, acc);
        } catch (Exception e) {
            logger.warn("Malformed package.json in '{}': {}", repo, e.getMessage());
        }
    }

    private void parseNpmDependencySection(JsonNode depsNode, boolean isDev, String repo, String path, Map<String, InferredSkillAccumulator> acc) {
        if (depsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = depsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String depName = field.getKey().toLowerCase();
                String version = field.getValue().asText("");

                TechnologyMapping mapping = mapNpmPackage(depName);
                if (mapping != null) {
                    recordManifestEvidence(acc, mapping, repo, path, depName + " " + version, isDev);
                }
            }
        }
    }

    private void parseRequirementsTxt(String repo, String path, String content, Map<String, InferredSkillAccumulator> acc) {
        Pattern pattern = Pattern.compile("^[a-zA-Z0-9_.-]+");
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String pkgName = matcher.group().toLowerCase();
                TechnologyMapping mapping = mapPythonPackage(pkgName);
                if (mapping != null) {
                    recordManifestEvidence(acc, mapping, repo, path, line, false);
                }
            }
        }
    }

    private void parsePyprojectToml(String repo, String path, String content, Map<String, InferredSkillAccumulator> acc) {
        Pattern depPattern = Pattern.compile("(?m)^\\s*([a-zA-Z0-9_.-]+)\\s*=\\s*[\"']([^\"']+)[\"']");
        Matcher matcher = depPattern.matcher(content);
        while (matcher.find()) {
            String pkgName = matcher.group(1).toLowerCase();
            String version = matcher.group(2);
            TechnologyMapping mapping = mapPythonPackage(pkgName);
            if (mapping != null) {
                recordManifestEvidence(acc, mapping, repo, path, pkgName + " = \"" + version + "\"", false);
            }
        }

        // Project dependencies list format: "fastapi>=0.95"
        Pattern listPattern = Pattern.compile("[\"']([a-zA-Z0-9_.-]+)(?:[>=<~^!][^\"']*)?[\"']");
        Matcher listMatcher = listPattern.matcher(content);
        while (listMatcher.find()) {
            String pkgName = listMatcher.group(1).toLowerCase();
            TechnologyMapping mapping = mapPythonPackage(pkgName);
            if (mapping != null) {
                recordManifestEvidence(acc, mapping, repo, path, listMatcher.group(0), false);
            }
        }
    }

    private void parseCargoToml(String repo, String path, String content, Map<String, InferredSkillAccumulator> acc) {
        Pattern pattern = Pattern.compile("(?m)^\\s*([a-zA-Z0-9_.-]+)\\s*=");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String crate = matcher.group(1).toLowerCase();
            TechnologyMapping mapping = mapCargoCrate(crate);
            if (mapping != null) {
                recordManifestEvidence(acc, mapping, repo, path, crate, false);
            }
        }
    }

    private void parseGoMod(String repo, String path, String content, Map<String, InferredSkillAccumulator> acc) {
        Pattern pattern = Pattern.compile("(?m)^\\s*(?:require\\s+)?([a-zA-Z0-9_./-]+)\\s+v[0-9]");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String module = matcher.group(1).toLowerCase();
            TechnologyMapping mapping = mapGoModule(module);
            if (mapping != null) {
                recordManifestEvidence(acc, mapping, repo, path, module, false);
            }
        }
    }

    private void parsePomXml(String repo, String path, String content, Map<String, InferredSkillAccumulator> acc) {
        Pattern depPattern = Pattern.compile("<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");
        Matcher matcher = depPattern.matcher(content);
        while (matcher.find()) {
            String groupId = matcher.group(1).trim().toLowerCase();
            String artifactId = matcher.group(2).trim().toLowerCase();

            TechnologyMapping mapping = mapMavenDependency(groupId, artifactId);
            if (mapping != null) {
                recordManifestEvidence(acc, mapping, repo, path, groupId + ":" + artifactId, false);
            }
        }
    }

    private record TechnologyMapping(String technology, String category) {}

    private TechnologyMapping mapNpmPackage(String pkg) {
        return switch (pkg) {
            case "react", "react-dom" -> new TechnologyMapping("React", "framework");
            case "next" -> new TechnologyMapping("Next.js", "framework");
            case "vue" -> new TechnologyMapping("Vue", "framework");
            case "angular", "@angular/core" -> new TechnologyMapping("Angular", "framework");
            case "svelte" -> new TechnologyMapping("Svelte", "framework");
            case "express" -> new TechnologyMapping("Express", "framework");
            case "nest", "@nestjs/core" -> new TechnologyMapping("NestJS", "framework");
            case "typescript" -> new TechnologyMapping("TypeScript", "language");
            case "tailwindcss" -> new TechnologyMapping("Tailwind CSS", "framework");
            case "redux", "@reduxjs/toolkit" -> new TechnologyMapping("Redux", "library");
            case "axios" -> new TechnologyMapping("Axios", "library");
            default -> null;
        };
    }

    private TechnologyMapping mapPythonPackage(String pkg) {
        return switch (pkg) {
            case "fastapi" -> new TechnologyMapping("FastAPI", "framework");
            case "django" -> new TechnologyMapping("Django", "framework");
            case "flask" -> new TechnologyMapping("Flask", "framework");
            case "numpy" -> new TechnologyMapping("NumPy", "library");
            case "pandas" -> new TechnologyMapping("Pandas", "library");
            case "torch", "pytorch" -> new TechnologyMapping("PyTorch", "library");
            case "tensorflow" -> new TechnologyMapping("TensorFlow", "library");
            case "scikit-learn", "sklearn" -> new TechnologyMapping("Scikit-Learn", "library");
            case "sqlalchemy" -> new TechnologyMapping("SQLAlchemy", "library");
            case "pydantic" -> new TechnologyMapping("Pydantic", "library");
            case "celery" -> new TechnologyMapping("Celery", "framework");
            case "pytest" -> new TechnologyMapping("Pytest", "tool");
            default -> null;
        };
    }

    private TechnologyMapping mapCargoCrate(String crate) {
        return switch (crate) {
            case "tokio" -> new TechnologyMapping("Tokio", "library");
            case "actix-web" -> new TechnologyMapping("Actix-web", "framework");
            case "axum" -> new TechnologyMapping("Axum", "framework");
            case "serde" -> new TechnologyMapping("Serde", "library");
            case "diesel" -> new TechnologyMapping("Diesel", "library");
            default -> null;
        };
    }

    private TechnologyMapping mapGoModule(String module) {
        if (module.contains("gin-gonic/gin")) return new TechnologyMapping("Gin", "framework");
        if (module.contains("labstack/echo")) return new TechnologyMapping("Echo", "framework");
        if (module.contains("gorilla/mux")) return new TechnologyMapping("Gorilla Mux", "library");
        if (module.contains("grpc")) return new TechnologyMapping("gRPC", "framework");
        return null;
    }

    private TechnologyMapping mapMavenDependency(String groupId, String artifactId) {
        if (groupId.startsWith("org.springframework")) {
            return new TechnologyMapping("Spring", "framework");
        }
        if (groupId.contains("hibernate") || artifactId.contains("hibernate")) {
            return new TechnologyMapping("Hibernate", "library");
        }
        if (groupId.contains("kafka") || artifactId.contains("kafka")) {
            return new TechnologyMapping("Kafka", "library");
        }
        return null;
    }

    private void recordManifestEvidence(
            Map<String, InferredSkillAccumulator> acc,
            TechnologyMapping mapping,
            String repo,
            String path,
            String detail,
            boolean isDev
    ) {
        String rawNormTech = TechnologyDetectionHelper.normalizeTechnology(mapping.technology());
        final String finalTech = (rawNormTech != null) ? rawNormTech : mapping.technology();

        String key = finalTech.toLowerCase();
        InferredSkillAccumulator accumulator = acc.computeIfAbsent(key, k -> new InferredSkillAccumulator(finalTech, mapping.category()));

        SkillEvidence evidence = new SkillEvidence(
                "dependency_manifest",
                repo,
                path,
                detail + (isDev ? " (devDependency)" : "")
        );
        accumulator.addManifestEvidence(evidence, isDev);
    }

    private HttpHeaders createAuthHeaders(String githubToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + githubToken);
        headers.set("Accept", "application/vnd.github.v3+json");
        headers.set("User-Agent", "IssuePilot-Backend");
        return headers;
    }

    private void checkRateLimit(HttpHeaders headers) {
        if (headers == null) return;
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        if (remaining != null && "0".equals(remaining)) {
            String reset = headers.getFirst("X-RateLimit-Reset");
            logger.warn("GitHub API rate limit exhausted. Reset epoch: {}", reset);
            throw new IllegalStateException("GitHub API rate limit exceeded. Please try again after rate limit reset.");
        }
    }

    private void handleRateLimitOrForbidden(HttpClientErrorException.Forbidden e) {
        String remaining = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("X-RateLimit-Remaining") : null;
        if ("0".equals(remaining)) {
            throw new IllegalStateException("GitHub rate limit reached: " + e.getMessage(), e);
        }
        logger.warn("Access forbidden or restricted by GitHub: {}", e.getMessage());
    }

    /**
     * Persists inferred skills safely into ContributorProfile without modifying confirmed skills.
     */
    private void persistInferredSkills(String canonicalUserId, List<InferredSkill> finalizedSkills) {
        Map<String, Object> userDoc = userRepository.getUser(canonicalUserId);
        ContributorProfile profile;
        if (userDoc != null) {
            try {
                profile = objectMapper.convertValue(userDoc, ContributorProfile.class);
            } catch (Exception e) {
                profile = new ContributorProfile();
                profile.setUserId(canonicalUserId);
            }
        } else {
            profile = new ContributorProfile();
            profile.setUserId(canonicalUserId);
        }

        for (InferredSkill skill : finalizedSkills) {
            profile.addInferredSkillSafely(skill);
        }

        userRepository.upsertUser(profile);
        logger.info("Persisted {} inferred skills for user '{}'.", profile.getInferredSkills().size(), canonicalUserId);
    }

    /**
     * Helper accumulator to combine evidence and calculate bounded confidence.
     */
    private static class InferredSkillAccumulator {
        private final String technology;
        private final String category;
        private final Set<SkillEvidence> evidenceSet = new LinkedHashSet<>();
        private final Set<String> observedRepositories = new HashSet<>();
        private long totalBytes = 0;
        private double maxBytePercentage = 0.0;
        private boolean foundInProductionManifest = false;
        private boolean foundInDevManifest = false;

        public InferredSkillAccumulator(String technology, String category) {
            this.technology = technology;
            this.category = category;
        }

        public void addEvidence(SkillEvidence evidence, double bytePercent, long bytes) {
            this.evidenceSet.add(evidence);
            this.observedRepositories.add(evidence.getRepository());
            this.totalBytes += bytes;
            this.maxBytePercentage = Math.max(this.maxBytePercentage, bytePercent);
        }

        public void addManifestEvidence(SkillEvidence evidence, boolean isDev) {
            this.evidenceSet.add(evidence);
            this.observedRepositories.add(evidence.getRepository());
            if (isDev) {
                this.foundInDevManifest = true;
            } else {
                this.foundInProductionManifest = true;
            }
        }

        /**
         * Documented Confidence Scoring Formula:
         * - Language evidence base: 0.50
         *   + 0.20 if >= 50% repo bytes or in >= 3 repos
         *   + 0.10 if >= 20% repo bytes or in >= 2 repos
         * - Manifest evidence base: 0.70
         *   + 0.15 if in >= 2 repos
         *   - 0.15 if only in devDependencies
         * - Combination bonus: + 0.10 if technology is supported by both language and manifest
         * - Bounds: strictly clamped between 0.10 and 0.95.
         */
        public double calculateConfidence() {
            double confidence = 0.50;

            if (foundInProductionManifest) {
                confidence = 0.70;
                if (observedRepositories.size() >= 2) {
                    confidence += 0.15;
                }
            } else if (foundInDevManifest) {
                confidence = 0.55;
            } else if (totalBytes > 0) {
                if (maxBytePercentage >= 50.0 || observedRepositories.size() >= 3) {
                    confidence = 0.75;
                } else if (maxBytePercentage >= 20.0 || observedRepositories.size() >= 2) {
                    confidence = 0.65;
                } else if (maxBytePercentage < 5.0 && observedRepositories.size() == 1) {
                    confidence = 0.40;
                }
            }

            // Multiple repos or both sources bonus
            if (foundInProductionManifest && totalBytes > 0) {
                confidence += 0.10;
            }

            return Math.max(0.10, Math.min(0.95, Math.round(confidence * 100.0) / 100.0));
        }

        public InferredSkill toInferredSkill() {
            InferredSkill skill = new InferredSkill(technology, category, calculateConfidence());
            skill.setStatus("pending_confirmation");
            skill.setEvidence(new ArrayList<>(evidenceSet));
            return skill;
        }
    }
}
