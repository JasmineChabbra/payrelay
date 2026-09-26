package com.payrelay.api.client;

import java.util.Map;

/**
 * The raw outcome of one HTTP call to an external system. Captures everything needed for the
 * payment timeline: what we sent, what came back, and how long it took.
 */
public record ExternalCallResult(
        Kind kind,
        Integer httpStatus,
        String requestSnapshot,
        String responseBody,
        Map<String, Object> json,
        String error,
        long durationMs) {

    public enum Kind {
        /** 2xx response. */
        OK,
        /** 4xx response: the system understood us and said no. Retrying won't change the answer. */
        CLIENT_ERROR,
        /** 5xx, 408, 429, timeout or connection failure: worth retrying. */
        TRANSIENT_ERROR
    }

    public String str(String field) {
        Object v = json == null ? null : json.get(field);
        return v == null ? null : v.toString();
    }

    public Integer integer(String field) {
        Object v = json == null ? null : json.get(field);
        return v instanceof Number n ? n.intValue() : null;
    }
}
