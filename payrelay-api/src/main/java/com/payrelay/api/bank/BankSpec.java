package com.payrelay.api.bank;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Everything PayRelay needs to talk to one bank: where to send the transfer, how to authenticate,
 * how to build the request from a payment, and how to read the bank's answer.
 * Stored as JSON in {@code bank_configs.spec}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BankSpec(
        Endpoint endpoint,
        Auth auth,
        Request request,
        Response response,
        Map<String, Map<String, String>> lookups) {

    public record Endpoint(String path) {
    }

    public enum AuthType {
        /** A static key in a header, optionally with a prefix such as "ApiKey ". */
        API_KEY,
        /** OAuth2 client credentials: fetch a bearer token, cache it, refresh on expiry or 401. */
        OAUTH2_CLIENT_CREDENTIALS,
        /** Sign each request with HMAC-SHA256 over timestamp, method, path and body hash. */
        HMAC_SHA256
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Auth(
            AuthType type,
            String secretRef,
            // API_KEY
            String header,
            String prefix,
            // OAUTH2_CLIENT_CREDENTIALS
            String tokenPath,
            String clientId,
            String scope,
            // HMAC_SHA256
            String keyId,
            String signatureHeader,
            String timestampHeader,
            String keyIdHeader) {
    }

    public record Request(List<FieldRule> headers, List<FieldRule> body) {
        public List<FieldRule> headersOrEmpty() {
            return headers == null ? List.of() : headers;
        }

        public List<FieldRule> bodyOrEmpty() {
            return body == null ? List.of() : body;
        }
    }

    /**
     * One output field. The value comes from a payment field ({@code source}) or a fixed value
     * ({@code constant}), then passes through {@code transforms} in order.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldRule(
            String target,
            String source,
            String constant,
            List<String> transforms,
            Boolean required,
            String defaultValue) {

        public boolean isRequired() {
            return required == null || required;
        }

        public List<String> transformsOrEmpty() {
            return transforms == null ? List.of() : transforms;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            String statusPath,
            List<String> successValues,
            List<String> rejectedValues,
            String referencePath,
            String reasonPath) {
    }

    public Map<String, Map<String, String>> lookupsOrEmpty() {
        return lookups == null ? Map.of() : lookups;
    }
}
