package com.issuepilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.issuepilot.dto.OnboardingConfirmationRequest;
import com.issuepilot.dto.OnboardingStatusResponse;
import com.issuepilot.dto.SkillUpdateRequest;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.model.ContributorSkill;
import com.issuepilot.model.InferredSkill;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.UserIdentityResolver;
import com.issuepilot.service.GitHubProfileAnalyzerService;
import com.issuepilot.service.OnboardingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller providing protected endpoints for contributor profile analysis,
 * skill suggestions, onboarding confirmation, and profile management.
 *
 * Requirements:
 * - Endpoints derive target identity exclusively from validated application principal.
 * - Client-supplied user IDs are rejected as authority.
 * - Bounded analysis with clear status responses.
 * - Idempotent onboarding skill confirmation and dismissal management.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final GitHubProfileAnalyzerService analyzerService;
    private final OnboardingService onboardingService;
    private final UserRepository userRepository;
    private final UserIdentityResolver userIdentityResolver;
    private final ObjectMapper objectMapper;

    public ProfileController(
            GitHubProfileAnalyzerService analyzerService,
            OnboardingService onboardingService,
            UserRepository userRepository,
            UserIdentityResolver userIdentityResolver,
            ObjectMapper objectMapper) {
        this.analyzerService = analyzerService;
        this.onboardingService = onboardingService;
        this.userRepository = userRepository;
        this.userIdentityResolver = userIdentityResolver;
        this.objectMapper = objectMapper;
    }

    private String requireAuthenticatedUserId(Authentication authentication) {
        String canonicalUserId = userIdentityResolver.resolveUserId(authentication);
        if (canonicalUserId == null || canonicalUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Active security principal is required.");
        }
        return canonicalUserId;
    }

    /**
     * Retrieves the authenticated contributor's onboarding status and profile summary.
     */
    @GetMapping("/onboarding")
    public OnboardingStatusResponse getOnboardingStatus(Authentication authentication) {
        String canonicalUserId = requireAuthenticatedUserId(authentication);
        return onboardingService.getOnboardingStatus(canonicalUserId);
    }

    /**
     * Confirms selected skills, dismisses unwanted suggestions, and saves preferences,
     * marking onboarding complete.
     */
    @PostMapping("/onboarding/confirm")
    public OnboardingStatusResponse confirmOnboarding(
            @RequestBody OnboardingConfirmationRequest request,
            Authentication authentication
    ) {
        String canonicalUserId = requireAuthenticatedUserId(authentication);
        return onboardingService.confirmOnboarding(canonicalUserId, request);
    }

    /**
     * Retrieves the saved ContributorProfile for the authenticated user.
     */
    @GetMapping({"", "/me"})
    public ContributorProfile getProfile(Authentication authentication) {
        String canonicalUserId = requireAuthenticatedUserId(authentication);
        return onboardingService.getProfile(canonicalUserId);
    }

    /**
     * Updates or adds a confirmed skill on the user's profile post-onboarding.
     */
    @PutMapping("/skills/{technology}")
    public ContributorSkill updateConfirmedSkill(
            @PathVariable String technology,
            @RequestBody SkillUpdateRequest request,
            Authentication authentication
    ) {
        String canonicalUserId = requireAuthenticatedUserId(authentication);
        return onboardingService.updateConfirmedSkill(canonicalUserId, technology, request);
    }

    /**
     * Deletes a confirmed skill from the user's profile.
     */
    @DeleteMapping("/skills/{technology}")
    public ResponseEntity<Map<String, Object>> deleteConfirmedSkill(
            @PathVariable String technology,
            Authentication authentication
    ) {
        String canonicalUserId = requireAuthenticatedUserId(authentication);
        onboardingService.deleteConfirmedSkill(canonicalUserId, technology);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "success");
        resp.put("message", "Skill '" + technology + "' removed successfully.");
        return ResponseEntity.ok(resp);
    }

    /**
     * Updates user preferences.
     */
    @PutMapping("/preferences")
    public Map<String, Object> updatePreferences(
            @RequestBody Map<String, Object> preferences,
            Authentication authentication
    ) {
        String canonicalUserId = requireAuthenticatedUserId(authentication);
        return onboardingService.updatePreferences(canonicalUserId, preferences);
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
     * Retrieves currently persisted inferred skills and analysis metadata for the
     * authenticated user.
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
            } catch (Exception e) {
                // Return default empty list if document format is invalid
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("user_id", canonicalUserId);
        response.put("count", inferredSkills.size());
        response.put("inferred_skills", inferredSkills);
        response.put("updated_at", updatedAt);
        return response;
    }
}
