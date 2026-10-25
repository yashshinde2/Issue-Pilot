package com.issuepilot.controller;

import com.issuepilot.config.MongoConfig;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.repository.UserRepository;
import com.issuepilot.security.UserIdentityResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final MongoConfig mongoConfig;
    private final UserIdentityResolver userIdentityResolver;

    public UserController(UserRepository userRepository, MongoConfig mongoConfig, UserIdentityResolver userIdentityResolver) {
        this.userRepository = userRepository;
        this.mongoConfig = mongoConfig;
        this.userIdentityResolver = userIdentityResolver;
    }

    private void ensureConnected() {
        if (!mongoConfig.isConnected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MongoDB Atlas connection is unavailable.");
        }
    }

    private String resolveActingUserId(Authentication auth, String suppliedUserId) {
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String id = userIdentityResolver.resolveUserId(auth);
            if (id != null) return id;
        }
        Authentication secAuth = SecurityContextHolder.getContext().getAuthentication();
        if (secAuth != null && secAuth.isAuthenticated() && !"anonymousUser".equals(secAuth.getPrincipal())) {
            String id = userIdentityResolver.resolveUserId(secAuth);
            if (id != null) return id;
        }
        return suppliedUserId;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createUserProfile(@RequestBody ContributorProfile profile, Authentication authentication) {
        ensureConnected();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile body is required.");
        }

        String actingUserId = resolveActingUserId(authentication, profile.getUserId());
        if (actingUserId != null && !actingUserId.isBlank()) {
            if (profile.getUserId() != null && !profile.getUserId().isBlank() && !profile.getUserId().equals(actingUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot create profile for another user.");
            }
            profile.setUserId(actingUserId);
        }

        try {
            Map<String, Object> created = userRepository.createUser(profile);
            return new ResponseEntity<>(created, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/{userId}")
    public Map<String, Object> getUserProfile(@PathVariable String userId, Authentication authentication) {
        ensureConnected();
        String actingUserId = resolveActingUserId(authentication, userId);
        if (actingUserId != null && !actingUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot view another user's profile.");
        }

        Map<String, Object> user = userRepository.getUser(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User '" + userId + "' not found.");
        }
        return user;
    }

    @PutMapping("/{userId}")
    public Map<String, Object> updateUserProfile(@PathVariable String userId, @RequestBody Map<String, Object> updates, Authentication authentication) {
        ensureConnected();
        String actingUserId = resolveActingUserId(authentication, userId);
        if (actingUserId != null && !actingUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot update another user's profile.");
        }

        Map<String, Object> updated = userRepository.updateUser(userId, updates);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User '" + userId + "' not found.");
        }
        return updated;
    }

    @PostMapping("/upsert")
    public Map<String, Object> upsertUserProfile(@RequestBody ContributorProfile profile, Authentication authentication) {
        ensureConnected();
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile body is required.");
        }

        String actingUserId = resolveActingUserId(authentication, profile.getUserId());
        if (actingUserId != null && !actingUserId.isBlank()) {
            if (profile.getUserId() != null && !profile.getUserId().isBlank() && !profile.getUserId().equals(actingUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot update profile for another user.");
            }
            profile.setUserId(actingUserId);
        }

        try {
            return userRepository.upsertUser(profile);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping
    public Map<String, Object> listUserProfiles(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int skip,
            Authentication authentication
    ) {
        // Enforce user privacy: arbitrary listing of user profiles is restricted
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            boolean isAdmin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            if (!isAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Listing all user profiles is restricted.");
            }
        }
        ensureConnected();
        List<Map<String, Object>> users = userRepository.listUsers(limit, skip);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("users", users);
        response.put("count", users.size());
        return response;
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> deleteUserProfile(@PathVariable String userId, Authentication authentication) {
        ensureConnected();
        String actingUserId = resolveActingUserId(authentication, userId);
        if (actingUserId != null && !actingUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot delete another user's profile.");
        }

        boolean deleted = userRepository.deleteUser(userId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User '" + userId + "' not found.");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "User '" + userId + "' deleted.");
        return response;
    }

    // Convenience overloads for direct Java service / test invocations
    public ResponseEntity<Map<String, Object>> createUserProfile(ContributorProfile profile) {
        return createUserProfile(profile, null);
    }

    public Map<String, Object> getUserProfile(String userId) {
        return getUserProfile(userId, null);
    }

    public Map<String, Object> updateUserProfile(String userId, Map<String, Object> updates) {
        return updateUserProfile(userId, updates, null);
    }

    public Map<String, Object> upsertUserProfile(ContributorProfile profile) {
        return upsertUserProfile(profile, null);
    }

    public Map<String, Object> listUserProfiles(int limit, int skip) {
        return listUserProfiles(limit, skip, null);
    }

    public Map<String, Object> deleteUserProfile(String userId) {
        return deleteUserProfile(userId, null);
    }
}
