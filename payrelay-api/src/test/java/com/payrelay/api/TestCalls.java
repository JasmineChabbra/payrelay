package com.payrelay.api;

import com.payrelay.api.client.ExternalCallResult;

import java.util.Map;

/** Builders for scripted external-system responses in tests. */
public final class TestCalls {

    private TestCalls() {
    }

    public static ExternalCallResult ok(Map<String, Object> json) {
        return new ExternalCallResult(ExternalCallResult.Kind.OK, 200, "req", json.toString(), json, null, 5);
    }

    public static ExternalCallResult serverError() {
        return new ExternalCallResult(ExternalCallResult.Kind.TRANSIENT_ERROR, 503, "req",
                "{\"message\":\"Service unavailable\"}", Map.of("message", "Service unavailable"), null, 5);
    }

    public static ExternalCallResult timeout() {
        return new ExternalCallResult(ExternalCallResult.Kind.TRANSIENT_ERROR, null, "req", null, Map.of(),
                "HttpTimeoutException: request timed out", 3000);
    }

    public static ExternalCallResult clientError(int status, String message) {
        return new ExternalCallResult(ExternalCallResult.Kind.CLIENT_ERROR, status, "req",
                "{\"message\":\"" + message + "\"}", Map.of("message", message), null, 5);
    }
}
