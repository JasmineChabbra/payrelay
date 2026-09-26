package com.payrelay.api.service;

import org.springframework.http.HttpStatus;

/** A domain error that maps to a specific HTTP status. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, "Not found", detail);
    }

    public static ApiException idempotencyMismatch() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused",
                "This Idempotency-Key was already used with a different request body.");
    }

    public static ApiException requestInProgress() {
        return new ApiException(HttpStatus.CONFLICT, "Request in progress",
                "A request with this Idempotency-Key is being processed. Retry shortly.");
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "Bad request", detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "Conflict", detail);
    }
}
