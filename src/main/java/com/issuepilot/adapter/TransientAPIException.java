package com.issuepilot.adapter;

public class TransientAPIException extends SourceAPIException {
    public TransientAPIException(String message) {
        super(message);
    }
    public TransientAPIException(String message, Integer statusCode) {
        super(message, statusCode);
    }
}
