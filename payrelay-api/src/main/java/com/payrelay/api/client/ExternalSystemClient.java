package com.payrelay.api.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrelay.api.web.CorrelationIdFilter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Thin HTTP layer over the external systems (KYC, risk, banks). It never throws for HTTP or network
 * failures; it classifies them instead, so the orchestrator can decide between retry and reject.
 *
 * <p>Every call returns a snapshot of what was sent, with sensitive values masked, for the audit trail.
 */
@Component
public class ExternalSystemClient {

    private static final int MAX_BODY_CHARS = 8000;
    private static final String HIDDEN = "[hidden]";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public ExternalSystemClient(RestClient simulatorRestClient, ObjectMapper mapper) {
        this.restClient = simulatorRestClient;
        this.mapper = mapper;
    }

    public String json(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize request body", e);
        }
    }

    public ExternalCallResult post(ExternalRequest req) {
        Map<String, String> headers = new LinkedHashMap<>(req.headers());
        headers.put(CorrelationIdFilter.HEADER, req.correlationId());
        String snapshot = redact(snapshot(req.path(), headers, req.bodyJson()), req.maskValues(), req.hideValues());
        return send(req.path(), MediaType.APPLICATION_JSON, req.bodyJson(), headers, snapshot, req.maskValues(), req.hideValues());
    }

    /** Form-encoded POST, used for OAuth2 token requests. */
    public ExternalCallResult postForm(String path, Map<String, String> form, String correlationId, Collection<String> hideValues) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CorrelationIdFilter.HEADER, correlationId);
        String encoded = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        String snapshot = redact(snapshot(path, headers, encoded), List.of(), hideValues);
        var multi = new LinkedMultiValueMap<String, String>();
        form.forEach(multi::add);
        return send(path, MediaType.APPLICATION_FORM_URLENCODED, multi, headers, snapshot, List.of(), hideValues);
    }

    private ExternalCallResult send(String path, MediaType type, Object body, Map<String, String> headers, String snapshot,
                                    Collection<String> maskValues, Collection<String> hideValues) {
        long start = System.nanoTime();
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(type)
                    .headers(h -> headers.forEach(h::set))
                    .body(body)
                    .exchange((req, res) -> {
                        String responseBody = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        HttpStatusCode status = res.getStatusCode();
                        return new ExternalCallResult(classify(status), status.value(), snapshot,
                                truncate(redact(responseBody, maskValues, hideValues)), parse(responseBody), null, elapsedMs(start));
                    });
        } catch (ResourceAccessException e) {
            // Timeouts and connection failures. The request may or may not have reached the other side,
            // which is exactly why every retried call carries an idempotency key.
            String reason = e.getMostSpecificCause().getClass().getSimpleName() + ": " + e.getMostSpecificCause().getMessage();
            return new ExternalCallResult(ExternalCallResult.Kind.TRANSIENT_ERROR, null, snapshot,
                    null, Map.of(), reason, elapsedMs(start));
        }
    }

    static ExternalCallResult.Kind classify(HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return ExternalCallResult.Kind.OK;
        }
        int code = status.value();
        if (status.is5xxServerError() || code == 408 || code == 429) {
            return ExternalCallResult.Kind.TRANSIENT_ERROR;
        }
        return ExternalCallResult.Kind.CLIENT_ERROR;
    }

    /**
     * Masks sensitive values wherever they appear (headers, body, any field name), because each bank
     * names its fields differently. Account numbers and PANs keep their last 4 characters; secrets and
     * tokens are hidden entirely.
     */
    static String redact(String text, Collection<String> maskValues, Collection<String> hideValues) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String v : hideValues) {
            if (v != null && v.length() >= 4) {
                out = out.replace(v, HIDDEN);
            }
        }
        for (String v : maskValues) {
            if (v != null && v.length() > 4) {
                out = out.replace(v, "X".repeat(v.length() - 4) + v.substring(v.length() - 4));
            }
        }
        return out;
    }

    private String snapshot(String path, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder("POST ").append(path).append('\n');
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        return sb.append('\n').append(body).toString();
    }

    private Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(body, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        return s.length() <= MAX_BODY_CHARS ? s : s.substring(0, MAX_BODY_CHARS) + "…[truncated]";
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
