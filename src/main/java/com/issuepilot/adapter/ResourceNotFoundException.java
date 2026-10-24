package com.issuepilot.adapter;

public class ResourceNotFoundException extends SourceAPIException {
    public ResourceNotFoundException(String message) {
        super(message, 404);
    }
}
