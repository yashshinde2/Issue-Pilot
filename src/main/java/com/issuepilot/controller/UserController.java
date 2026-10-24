package com.issuepilot.controller;

import com.issuepilot.config.MongoConfig;
import com.issuepilot.model.ContributorProfile;
import com.issuepilot.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public UserController(UserRepository userRepository, MongoConfig mongoConfig) {
        this.userRepository = userRepository;
        this.mongoConfig = mongoConfig;
    }

    private void ensureConnected() {
        if (!mongoConfig.isConnected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MongoDB Atlas connection is unavailable.");
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createUserProfile(@RequestBody ContributorProfile profile) {
        ensureConnected();
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
    public Map<String, Object> getUserProfile(@PathVariable String userId) {
        ensureConnected();
        Map<String, Object> user = userRepository.getUser(userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User '" + userId + "' not found.");
        }
        return user;
    }

    @PutMapping("/{userId}")
    public Map<String, Object> updateUserProfile(@PathVariable String userId, @RequestBody Map<String, Object> updates) {
        ensureConnected();
        Map<String, Object> updated = userRepository.updateUser(userId, updates);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User '" + userId + "' not found.");
        }
        return updated;
    }

    @PostMapping("/upsert")
    public Map<String, Object> upsertUserProfile(@RequestBody ContributorProfile profile) {
        ensureConnected();
        try {
            return userRepository.upsertUser(profile);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping
    public Map<String, Object> listUserProfiles(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int skip
    ) {
        ensureConnected();
        List<Map<String, Object>> users = userRepository.listUsers(limit, skip);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("users", users);
        response.put("count", users.size());
        return response;
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> deleteUserProfile(@PathVariable String userId) {
        ensureConnected();
        boolean deleted = userRepository.deleteUser(userId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User '" + userId + "' not found.");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("message", "User '" + userId + "' deleted.");
        return response;
    }
}
