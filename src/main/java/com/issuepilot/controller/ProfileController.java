package com.issuepilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.UserIdentityResolver;
import com.issuepilot.service.GitHubProfileAnalyzerService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Controller providing protected endpoints for contributor profile analysis and skill suggestions.
 *
 * Requirements:
 * - Endpoints derive target identity exclusively from validated application principal.
 * - Client-supplied user IDs are rejected as authority.
 * - Bounded analysis with clear status responses.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final GitHubProfileAnalyzerService analyzerService;
    private final UserRepository userRepository;
    private final UserIdentityResolver userIdentityResolver;
    private final ObjectMapper objectMapper;

    public ProfileController(
            GitHubProfileAnalyzerService analyzerService,
            UserRepository userRepository,
            UserIdentityResolver userIdentityResolver,
            ObjectMapper objectMapper
    ) {
        this.analyzerService = analyzerService;
        this.userRepository = userRepository;
        this.userIdentityResolver = userIdentityResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Initiates GitHub repository analysis for the currently authenticated user.
     */
    @PostMapping("/analyze")
    public GitHubProfileAnalyzerService.AnalysisResult analyzeProfile(Authentication authentication) {
        String canonicalUserId = userIdentityResolver.resolveUserId(authentication);
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Active security principal is required.");
        }

        try {
            return analyzerService.analyzeUserProfile(canonicalUserId);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("rate limit")) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
            }
            if (e.getMessage() != null && e.getMessage().contains("re-authenticate")) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
    }

    /**
     * Retrieves currently persisted inferred skills and analysis metadata for the authenticated user.
     */
    @GetMapping("/inferred-skills")
    public Map<String, Object> getInferredSkills(Authentication authentication) {
        String canonicalUserId = userIdentityResolver.resolveUserId(authentication);
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Active security principal is required.");
        }

        Map<String, Object> userDoc = userRepository.getUser(canonicalUserId);
        List<InferredSkill> inferredSkills = Collections.emptyList();
        String updatedAt = null;

        if (userDoc != null) {
            try {
                ContributorProfile profile = objectMapper.convertValue(userDoc, ContributorProfile.class);
                if (profile != null && profile.getInferredSkills() != null) {
                    inferredSkills = profile.getInferredSkills();
                    updatedAt = profile.getUpdatedAt();
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user_id", canonicalUserId);
        response.put("count", inferredSkills.size());
        response.put("inferred_skills", inferredSkills);
        response.put("updated_at", updatedAt);
        return response;
    }
}
