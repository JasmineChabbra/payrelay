package com.payrelay.api.client;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * One outgoing JSON POST.
 *
 * @param bodyJson        the exact bytes to send (HMAC signatures are computed over this string)
 * @param maskValues      values to partly mask in the stored snapshot, e.g. account numbers (last 4 kept)
 * @param hideValues      values to hide completely in the stored snapshot, e.g. secrets and tokens
 */
public record ExternalRequest(
        String path,
        String bodyJson,
        Map<String, String> headers,
        String correlationId,
        Collection<String> maskValues,
        Collection<String> hideValues) {

    public ExternalRequest(String path, String bodyJson, Map<String, String> headers, String correlationId,
                           Collection<String> maskValues) {
        this(path, bodyJson, headers, correlationId, maskValues, List.of());
    }
}
