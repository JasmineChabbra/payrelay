package com.payrelay.simulator.chaos;

import org.springframework.http.HttpStatus;

/** Thrown to produce an injected failure response. */
public class ChaosException extends RuntimeException {

    private final HttpStatus status;

    public ChaosException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
