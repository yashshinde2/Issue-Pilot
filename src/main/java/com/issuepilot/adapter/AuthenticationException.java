package com.issuepilot.adapter;

public class AuthenticationException extends SourceAPIException {
    public AuthenticationException(String message) {
        super(message, 401);
    }
    public AuthenticationException(String message, Integer statusCode) {
        super(message, statusCode);
    }
}
