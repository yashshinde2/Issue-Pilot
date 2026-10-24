package com.issuepilot.adapter;

public class RateLimitExceededException extends SourceAPIException {
    private final Long resetTimestamp;
    private final int remaining;

    public RateLimitExceededException(String message) {
        this(message, null, 0);
    }

    public RateLimitExceededException(String message, Long resetTimestamp, int remaining) {
        super(message, 429);
        this.resetTimestamp = resetTimestamp;
        this.remaining = remaining;
    }

    public Long getResetTimestamp() {
        return resetTimestamp;
    }

    public int getRemaining() {
        return remaining;
    }
}
