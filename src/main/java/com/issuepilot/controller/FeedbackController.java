package com.issuepilot.controller;

import com.issuepilot.config.MongoConfig;
import com.issuepilot.model.FeedbackEvent;
import com.issuepilot.repository.FeedbackRepository;
import com.issuepilot.security.UserIdentityResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackRepository feedbackRepository;
    private final MongoConfig mongoConfig;
    private final UserIdentityResolver userIdentityResolver;

    public FeedbackController(FeedbackRepository feedbackRepository, MongoConfig mongoConfig, UserIdentityResolver userIdentityResolver) {
        this.feedbackRepository = feedbackRepository;
        this.mongoConfig = mongoConfig;
        this.userIdentityResolver = userIdentityResolver;
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
    public Map<String, Object> recordFeedback(@RequestBody FeedbackEvent event, Authentication authentication) {
        if (event == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Feedback body is required.");
        }

        String actingUserId = resolveActingUserId(authentication, event.getUserId());
        if (actingUserId == null || actingUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field 'user_id' is required.");
        }

        // Reject client-supplied spoofing
        if (event.getUserId() != null && !event.getUserId().isBlank() && !event.getUserId().equals(actingUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot record feedback for another user.");
        }
        event.setUserId(actingUserId);

        String eventType = event.getEventType();
        if (eventType == null || (!eventType.equals("accepted") && !eventType.equals("rejected") && !eventType.equals("completed"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event_type. Must be 'accepted', 'rejected', or 'completed'.");
        }

        String nowIso = event.getEventTimestamp();
        if (nowIso == null || nowIso.isBlank()) {
            nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            event.setEventTimestamp(nowIso);
        }

        if (mongoConfig.isConnected()) {
            try {
                feedbackRepository.recordFeedback(event);
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "success");
        res.put("message", "Recorded feedback '" + eventType + "' for user '" + event.getUserId() + "' on issue '" + event.getIssueId() + "'");
        res.put("timestamp", nowIso);
        return res;
    }

    @GetMapping("/{userId}")
    public Map<String, Object> getUserFeedback(
            @PathVariable String userId,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication
    ) {
        String actingUserId = resolveActingUserId(authentication, userId);
        if (actingUserId != null && !actingUserId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: Cannot view feedback for another user.");
        }
        return feedbackRepository.getUserFeedback(userId, limit);
    }

    // Convenience overload for direct unit tests
    public Map<String, Object> recordFeedback(FeedbackEvent event) {
        return recordFeedback(event, null);
    }

    public Map<String, Object> getUserFeedback(String userId, int limit) {
        return getUserFeedback(userId, limit, null);
    }
}
