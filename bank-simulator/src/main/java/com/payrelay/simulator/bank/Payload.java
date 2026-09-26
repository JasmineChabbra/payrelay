package com.payrelay.simulator.bank;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Reads fields out of a parsed JSON payload, failing with a clear 400 if the client sent the wrong shape. */
final class Payload {

    /** A malformed request; answered with 400 and the bank's own error format. */
    static class BadRequest extends RuntimeException {
        final HttpStatus status = HttpStatus.BAD_REQUEST;

        BadRequest(String message) {
            super(message);
        }
    }

    private final Map<String, Object> root;

    Payload(Map<String, Object> root) {
        this.root = root;
    }

    Object get(String path) {
        Object node = root;
        for (String key : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> m)) {
                return null;
            }
            node = m.get(key);
        }
        return node;
    }

    String text(String path) {
        Object v = get(path);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new BadRequest("Field '" + path + "' is required and must be text");
        }
        return s;
    }

    String text(String path, int maxLength) {
        String s = text(path);
        if (s.length() > maxLength) {
            throw new BadRequest("Field '" + path + "' must be at most " + maxLength + " characters, got " + s.length());
        }
        return s;
    }

    Number number(String path) {
        Object v = get(path);
        if (!(v instanceof Number n)) {
            throw new BadRequest("Field '" + path + "' is required and must be a number");
        }
        return n;
    }

    long wholeNumber(String path) {
        Number n = number(path);
        if (n instanceof Double || n instanceof Float || n instanceof java.math.BigDecimal) {
            if (n.doubleValue() != Math.floor(n.doubleValue())) {
                throw new BadRequest("Field '" + path + "' must be a whole number");
            }
        }
        return n.longValue();
    }
}
