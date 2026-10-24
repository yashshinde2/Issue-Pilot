package com.issuepilot.controller;

import com.issuepilot.config.MongoConfig;
import com.issuepilot.model.FeedbackEvent;
import com.issuepilot.repository.FeedbackRepository;
import org.springframework.http.HttpStatus;
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

    public FeedbackController(FeedbackRepository feedbackRepository, MongoConfig mongoConfig) {
        this.feedbackRepository = feedbackRepository;
        this.mongoConfig = mongoConfig;
    }

    @PostMapping
    public Map<String, Object> recordFeedback(@RequestBody FeedbackEvent event) {
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
            @RequestParam(defaultValue = "50") int limit
    ) {
        return feedbackRepository.getUserFeedback(userId, limit);
    }
}
