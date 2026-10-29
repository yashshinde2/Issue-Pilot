package com.issuepilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.config.AppProperties;
import com.issuepilot.config.EnvLoader;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.model.SkillEvidence;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.TokenService;
import com.issuepilot.security.UserCredentialService;
import com.issuepilot.service.GitHubProfileAnalyzerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "APP_JWT_SECRET=test_very_secure_secret_key_with_at_least_32_bytes_of_entropy_12345",
        "APP_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "APP_JWT_ISSUER=https://issuepilot-test.com",
        "GITHUB_ANALYZER_MAX_REPOSITORIES=15",
        "GITHUB_ANALYZER_EXCLUDE_FORKS=true"
})
@SuppressWarnings("null")
public class GitHubProfileAnalyzerTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
        EnvLoader.loadEnv();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialService userCredentialService;

    @Autowired
    private AppProperties appProperties;

    private RestTemplate mockRestTemplate;
    private GitHubProfileAnalyzerService analyzerService;

    @BeforeEach
    public void setup() {
        mockRestTemplate = Mockito.mock(RestTemplate.class);
        analyzerService = new GitHubProfileAnalyzerService(
                userCredentialService,
                userRepository,
                appProperties,
                objectMapper,
                mockRestTemplate
        );
    }

    private String encodeBase64Content(String text) {
        String b64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return "{\"content\":\"" + b64 + "\",\"encoding\":\"base64\"}";
    }

    @Test
    @DisplayName("Language evidence produces normalized language suggestions without implying frameworks")
    public void testLanguageStatsDoNotInferFrameworks() {
        String canonicalUserId = "github:20001";
        userCredentialService.storeCredential(canonicalUserId, "github", "20001", "gho_mock_token_1", "read:user");

        // Mock repos: python-only repo
        List<Map<String, Object>> repos = List.of(
                Map.of("full_name", "user/repo-python", "fork", false, "private", false)
        );
        when(mockRestTemplate.exchange(contains("/user/repos"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(objectMapper.valueToTree(repos).toString()));

        // Mock languages: 100% Python
        when(mockRestTemplate.exchange(contains("/repos/user/repo-python/languages"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"Python\": 50000}"));

        // All manifest files return 404
        when(mockRestTemplate.exchange(contains("/contents/"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        GitHubProfileAnalyzerService.AnalysisResult result = analyzerService.analyzeUserProfile(canonicalUserId);

        assertEquals("completed", result.status());
        assertEquals(1, result.repositoriesAnalyzed());

        // Verify Python is inferred
        Optional<InferredSkill> pythonOpt = result.inferredSkills().stream()
                .filter(s -> "Python".equalsIgnoreCase(s.getTechnology()))
                .findFirst();
        assertTrue(pythonOpt.isPresent());
        assertEquals("language", pythonOpt.get().getCategory());
        assertTrue(pythonOpt.get().getConfidence() >= 0.50 && pythonOpt.get().getConfidence() <= 0.95);

        // Verify FastAPI, Django, NumPy are strictly NOT inferred
        boolean containsFastApi = result.inferredSkills().stream().anyMatch(s -> "FastAPI".equalsIgnoreCase(s.getTechnology()));
        boolean containsDjango = result.inferredSkills().stream().anyMatch(s -> "Django".equalsIgnoreCase(s.getTechnology()));
        boolean containsNumPy = result.inferredSkills().stream().anyMatch(s -> "NumPy".equalsIgnoreCase(s.getTechnology()));

        assertFalse(containsFastApi, "Python alone must NOT imply FastAPI");
        assertFalse(containsDjango, "Python alone must NOT imply Django");
        assertFalse(containsNumPy, "Python alone must NOT imply NumPy");
    }

    @Test
    @DisplayName("Dependency manifests identify supported libraries and frameworks with concrete file evidence")
    public void testDependencyManifestInference() {
        String canonicalUserId = "github:20002";
        userCredentialService.storeCredential(canonicalUserId, "github", "20002", "gho_mock_token_2", "read:user");

        List<Map<String, Object>> repos = List.of(
                Map.of("full_name", "user/backend-app", "fork", false, "private", false)
        );
        when(mockRestTemplate.exchange(contains("/user/repos"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(objectMapper.valueToTree(repos).toString()));

        when(mockRestTemplate.exchange(contains("/languages"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"Python\": 40000}"));

        // requirements.txt contains fastapi and numpy
        String reqTxt = "fastapi>=0.100.0\nnumpy==1.24.3\nrequests\n";
        when(mockRestTemplate.exchange(contains("/contents/requirements.txt"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(encodeBase64Content(reqTxt)));

        when(mockRestTemplate.exchange(contains("/contents/package.json"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(mockRestTemplate.exchange(contains("/contents/pyproject.toml"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(mockRestTemplate.exchange(contains("/contents/Cargo.toml"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(mockRestTemplate.exchange(contains("/contents/go.mod"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(mockRestTemplate.exchange(contains("/contents/pom.xml"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        GitHubProfileAnalyzerService.AnalysisResult result = analyzerService.analyzeUserProfile(canonicalUserId);

        // Verify FastAPI & NumPy are inferred with requirements.txt evidence
        Optional<InferredSkill> fastapiOpt = result.inferredSkills().stream()
                .filter(s -> "FastAPI".equalsIgnoreCase(s.getTechnology()))
                .findFirst();
        assertTrue(fastapiOpt.isPresent());
        assertEquals("framework", fastapiOpt.get().getCategory());
        assertEquals("requirements.txt", fastapiOpt.get().getEvidence().get(0).getFilePath());

        Optional<InferredSkill> numpyOpt = result.inferredSkills().stream()
                .filter(s -> "NumPy".equalsIgnoreCase(s.getTechnology()))
                .findFirst();
        assertTrue(numpyOpt.isPresent());
        assertEquals("library", numpyOpt.get().getCategory());
    }

    @Test
    @DisplayName("Package.json, Cargo.toml, go.mod, and pom.xml manifests are format-accurately parsed")
    public void testMultiEcosystemManifestParsing() {
        String canonicalUserId = "github:20003";
        userCredentialService.storeCredential(canonicalUserId, "github", "20003", "gho_mock_token_3", "read:user");

        List<Map<String, Object>> repos = List.of(
                Map.of("full_name", "user/polyglot", "fork", false, "private", false)
        );
        when(mockRestTemplate.exchange(contains("/user/repos"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(objectMapper.valueToTree(repos).toString()));
        when(mockRestTemplate.exchange(contains("/languages"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        // package.json with react
        String pkgJson = "{\"dependencies\": {\"react\": \"^18.2.0\"}}";
        when(mockRestTemplate.exchange(contains("/contents/package.json"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(encodeBase64Content(pkgJson)));

        // Cargo.toml with tokio
        String cargoToml = "[dependencies]\ntokio = \"1.0\"\n";
        when(mockRestTemplate.exchange(contains("/contents/Cargo.toml"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(encodeBase64Content(cargoToml)));

        // go.mod with gin
        String goMod = "module example.com/app\nrequire github.com/gin-gonic/gin v1.9.0\n";
        when(mockRestTemplate.exchange(contains("/contents/go.mod"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(encodeBase64Content(goMod)));

        // pom.xml with spring-boot
        String pomXml = "<project><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>";
        when(mockRestTemplate.exchange(contains("/contents/pom.xml"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(encodeBase64Content(pomXml)));

        when(mockRestTemplate.exchange(contains("/contents/requirements.txt"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        when(mockRestTemplate.exchange(contains("/contents/pyproject.toml"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        GitHubProfileAnalyzerService.AnalysisResult result = analyzerService.analyzeUserProfile(canonicalUserId);

        Set<String> techNames = new HashSet<>();
        for (InferredSkill s : result.inferredSkills()) {
            techNames.add(s.getTechnology());
        }

        assertTrue(techNames.contains("React"), "React inferred from package.json");
        assertTrue(techNames.contains("Tokio"), "Tokio inferred from Cargo.toml");
        assertTrue(techNames.contains("Gin"), "Gin inferred from go.mod");
        assertTrue(techNames.contains("Spring"), "Spring inferred from pom.xml");
    }

    @Test
    @DisplayName("Forks and private repositories are excluded by default")
    public void testForksAndPrivateReposExcluded() {
        String canonicalUserId = "github:20004";
        userCredentialService.storeCredential(canonicalUserId, "github", "20004", "gho_mock_token_4", "read:user");

        List<Map<String, Object>> repos = List.of(
                Map.of("full_name", "user/forked-repo", "fork", true, "private", false),
                Map.of("full_name", "user/private-repo", "fork", false, "private", true),
                Map.of("full_name", "user/own-repo", "fork", false, "private", false)
        );
        when(mockRestTemplate.exchange(contains("/user/repos"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(objectMapper.valueToTree(repos).toString()));
        when(mockRestTemplate.exchange(contains("/languages"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"Rust\": 1000}"));
        when(mockRestTemplate.exchange(contains("/contents/"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        GitHubProfileAnalyzerService.AnalysisResult result = analyzerService.analyzeUserProfile(canonicalUserId);

        assertEquals(1, result.repositoriesAnalyzed(), "Only 1 non-fork public repository should be analyzed");
    }

    @Test
    @DisplayName("Re-analysis does NOT overwrite user-confirmed skills or preferences")
    public void testReanalysisPreservesConfirmedSkills() {
        String canonicalUserId = "github:20005";
        userCredentialService.storeCredential(canonicalUserId, "github", "20005", "gho_mock_token_5", "read:user");

        // User already has confirmed skill for Python
        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        profile.setSkills(Map.of("Python", new ContributorSkill("Python", 95.0, 1.0, "expert", "user_confirmed")));
        profile.setPreferences(Map.of("preferred_technologies", List.of("Python", "Rust")));
        userRepository.upsertUser(profile);

        List<Map<String, Object>> repos = List.of(
                Map.of("full_name", "user/python-repo", "fork", false, "private", false)
        );
        when(mockRestTemplate.exchange(contains("/user/repos"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(objectMapper.valueToTree(repos).toString()));
        when(mockRestTemplate.exchange(contains("/languages"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"Python\": 10000}"));
        when(mockRestTemplate.exchange(contains("/contents/"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        analyzerService.analyzeUserProfile(canonicalUserId);

        Map<String, Object> reloaded = userRepository.getUser(canonicalUserId);
        assertNotNull(reloaded);

        Map<?, ?> skills = (Map<?, ?>) reloaded.get("skills");
        assertNotNull(skills);
        assertTrue(skills.containsKey("Python"));

        // Inferred skills list should NOT contain Python since it's already confirmed
        List<?> inferred = (List<?>) reloaded.get("inferred_skills");
        if (inferred != null) {
            boolean hasInferredPython = inferred.stream().anyMatch(item -> {
                if (item instanceof Map<?, ?> m) {
                    return "Python".equalsIgnoreCase(String.valueOf(m.get("technology")));
                }
                return false;
            });
            assertFalse(hasInferredPython, "Inferred skill must not supersede confirmed skill");
        }
    }

    @Test
    @DisplayName("GitHub rate limit failure is safely handled and surfaces error")
    public void testRateLimitHandledSafely() {
        String canonicalUserId = "github:20006";
        userCredentialService.storeCredential(canonicalUserId, "github", "20006", "gho_mock_token_6", "read:user");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "0");
        headers.set("X-RateLimit-Reset", "1700000000");

        when(mockRestTemplate.exchange(contains("/user/repos"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("[]", headers, HttpStatus.OK));

        assertThrows(IllegalStateException.class, () -> analyzerService.analyzeUserProfile(canonicalUserId));
    }

    @Test
    @DisplayName("Protected API endpoints /api/profile/analyze and /api/profile/inferred-skills reject unauthenticated requests")
    public void testApiEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/profile/analyze"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/profile/inferred-skills"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Authenticated user cannot trigger analysis for another user's identity")
    public void testCrossUserAnalysisImpossible() throws Exception {
        String userA = "github:20007";
        String userB = "github:20008";

        // Store credential for user B, but user A logs in
        userCredentialService.storeCredential(userB, "github", "20008", "gho_token_b", "read:user");

        String jwtUserA = tokenService.issueToken(userA, "userA", List.of("ROLE_USER"));

        // User A attempts to analyze - principal resolves to userA, which has no credentials
        mockMvc.perform(post("/api/profile/analyze")
                        .header("Authorization", "Bearer " + jwtUserA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/profile/inferred-skills returns persisted skills and never leaks credentials")
    public void testInferredSkillsEndpointDoesNotLeakCredentials() throws Exception {
        String canonicalUserId = "github:20009";
        userCredentialService.storeCredential(canonicalUserId, "github", "20009", "gho_secret_not_leaked", "read:user");

        ContributorProfile profile = new ContributorProfile();
        profile.setUserId(canonicalUserId);
        InferredSkill s1 = new InferredSkill("TypeScript", "language", 0.85);
        s1.addEvidence(new SkillEvidence("language_statistics", "user/repo", null, "85% TypeScript"));
        profile.setInferredSkills(List.of(s1));
        userRepository.upsertUser(profile);

        String jwt = tokenService.issueToken(canonicalUserId, "user_ts", List.of("ROLE_USER"));

        mockMvc.perform(get("/api/profile/inferred-skills")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(canonicalUserId))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.inferred_skills[0].technology").value("TypeScript"))
                .andExpect(jsonPath("$.inferred_skills[0].confidence").value(0.85))
                .andExpect(jsonPath("$.inferred_skills[0].encrypted_access_token").doesNotExist())
                .andExpect(jsonPath("$.inferred_skills[0].nonce").doesNotExist());
    }
}
