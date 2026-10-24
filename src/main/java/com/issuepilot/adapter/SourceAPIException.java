package com.issuepilot.adapter;

import java.util.Collections;
import java.util.Map;

public class SourceAPIException extends RuntimeException {
    private final Integer statusCode;
    private final Map<String, Object> details;

    public SourceAPIException(String message) {
        this(message, null, Collections.emptyMap());
    }

    public SourceAPIException(String message, Integer statusCode) {
        this(message, statusCode, Collections.emptyMap());
    }

    public SourceAPIException(String message, Integer statusCode, Map<String, Object> details) {
        super(message);
        this.statusCode = statusCode;
        this.details = details != null ? details : Collections.emptyMap();
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
