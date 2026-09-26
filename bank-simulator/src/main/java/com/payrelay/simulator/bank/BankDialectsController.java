package com.payrelay.simulator.bank;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrelay.simulator.api.SimulatedSystemsController;
import com.payrelay.simulator.chaos.ChaosEngine;
import com.payrelay.simulator.chaos.ChaosEngine.SimResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Function;

/**
 * Four banks, four API dialects. They share the same banking rules ({@link BankCore}) and chaos engine,
 * but each has its own URL, authentication, payload shape, idempotency key location and error format,
 * which is what PayRelay's config-driven mapping has to cope with.
 *
 * <table>
 *   <tr><th>Bank</th><th>Auth</th><th>Payload</th><th>Idempotency key</th><th>Rejections</th></tr>
 *   <tr><td>Nova</td><td>X-Nova-Key header</td><td>flat snake_case, rupees</td><td>Idempotency-Key header</td><td>422</td></tr>
 *   <tr><td>Zenith</td><td>OAuth2 bearer token</td><td>nested camelCase, paise</td><td>clientReference field</td><td>200 + state REJECTED</td></tr>
 *   <tr><td>Orbit</td><td>HMAC-SHA256 signature</td><td>terse ISO-style, amount as text</td><td>txn.ref field</td><td>200 + result code 14</td></tr>
 *   <tr><td>Kestrel</td><td>Authorization: ApiKey</td><td>flat camelCase, paise</td><td>X-Request-Id header</td><td>422</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/banks")
public class BankDialectsController {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final long ORBIT_MAX_CLOCK_SKEW_SECONDS = 300;

    private final ChaosEngine chaos;
    private final BankSecrets secrets;
    private final ZenithTokens zenithTokens;
    private final ObjectMapper mapper;

    public BankDialectsController(ChaosEngine chaos, BankSecrets secrets, ZenithTokens zenithTokens, ObjectMapper mapper) {
        this.chaos = chaos;
        this.secrets = secrets;
        this.zenithTokens = zenithTokens;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- Nova

    @PostMapping("/nova/v1/payouts")
    public ResponseEntity<Map<String, Object>> nova(@RequestHeader(value = "X-Nova-Key", required = false) String key,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                                    @RequestBody String raw) {
        Function<String, Map<String, Object>> error = msg -> Map.of("status", "FAILED", "message", msg);
        if (!constantTimeEquals(key, secrets.novaApiKey())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error.apply("Invalid API key"));
        }
        try {
            if (idem == null || idem.isBlank()) throw new Payload.BadRequest("Idempotency-Key header is required");
            Payload p = parse(raw);
            p.text("txn_ref");
            BigDecimal amount = new BigDecimal(p.number("amount").toString());
            String account = p.text("account_no");
            String ifsc = p.text("ifsc_code");
            p.text("beneficiary_name");
            return SimulatedSystemsController.respond(chaos.run("NOVA", idem, () ->
                    switch (BankCore.transfer("NOVA", account, ifsc, amount)) {
                        case BankCore.Ok ok -> new SimResponse(HttpStatus.OK,
                                Map.of("status", "SUCCESS", "utr", ok.reference()), ok.amountRupees().doubleValue());
                        case BankCore.Rejected r -> SimResponse.rejection(HttpStatus.UNPROCESSABLE_ENTITY, error.apply(r.reason()));
                    }));
        } catch (Payload.BadRequest e) {
            return ResponseEntity.badRequest().body(error.apply(e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- Zenith

    /** OAuth2 client-credentials token endpoint (form-encoded, as the spec requires). */
    @PostMapping(value = "/zenith/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> zenithToken(@RequestParam Map<String, String> form) {
        if (!"client_credentials".equals(form.get("grant_type"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported_grant_type"));
        }
        if (!secrets.zenithClientId().equals(form.get("client_id"))
                || !constantTimeEquals(form.get("client_secret"), secrets.zenithClientSecret())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_client"));
        }
        long ttl = secrets.zenithTokenTtlSeconds();
        return ResponseEntity.ok(Map.of("access_token", zenithTokens.issue(ttl), "token_type", "Bearer", "expires_in", ttl));
    }

    @PostMapping("/zenith/v2/transfers")
    public ResponseEntity<Map<String, Object>> zenith(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @RequestBody String raw) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (!zenithTokens.isValid(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_token"));
        }
        try {
            Payload p = parse(raw);
            String ref = p.text("clientReference");
            long paise = p.wholeNumber("payment.amountInPaise");
            p.text("payment.currency");
            p.text("beneficiary.name");
            String account = p.text("beneficiary.account.number");
            String ifsc = p.text("beneficiary.account.ifsc");
            p.text("purposeCode");
            BigDecimal amount = BigDecimal.valueOf(paise).movePointLeft(2);
            return SimulatedSystemsController.respond(chaos.run("ZENITH", ref, () ->
                    switch (BankCore.transfer("ZENITH", account, ifsc, amount)) {
                        case BankCore.Ok ok -> new SimResponse(HttpStatus.OK,
                                Map.of("data", Map.of("transferId", ok.reference(), "state", "PROCESSED")), ok.amountRupees().doubleValue());
                        // Zenith reports business rejections inside a 200, a common real-world pattern.
                        case BankCore.Rejected r -> SimResponse.rejection(HttpStatus.OK,
                                Map.of("data", Map.of("state", "REJECTED", "reason", r.reason())));
                    }));
        } catch (Payload.BadRequest e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "error_description", e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- Orbit

    @PostMapping("/orbit/api/credit")
    public ResponseEntity<Map<String, Object>> orbit(@RequestHeader(value = "X-Orbit-Key-Id", required = false) String keyId,
                                                     @RequestHeader(value = "X-Orbit-Timestamp", required = false) String timestamp,
                                                     @RequestHeader(value = "X-Orbit-Signature", required = false) String signature,
                                                     @RequestBody String raw, HttpServletRequest request) {
        Function<String, Map<String, Object>> result = (codeAndDesc) -> {
            String[] parts = codeAndDesc.split("\\|", 2);
            return Map.of("result", Map.of("code", parts[0], "desc", parts[1]));
        };
        String authError = verifyOrbitSignature(keyId, timestamp, signature, request.getRequestURI(), raw);
        if (authError != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result.apply("91|" + authError));
        }
        try {
            Payload p = parse(raw);
            String ref = p.text("txn.ref");
            String amt = p.text("txn.amt");
            if (!amt.matches("\\d+\\.\\d{2}")) throw new Payload.BadRequest("txn.amt must look like 25000.00");
            p.text("txn.ccy");
            p.text("cdtr.nm", 35);
            String account = p.text("cdtr.acct");
            String ifsc = p.text("cdtr.ifsc");
            p.text("purp");
            BigDecimal amount = new BigDecimal(amt);
            return SimulatedSystemsController.respond(chaos.run("ORBIT", ref, () ->
                    switch (BankCore.transfer("ORBIT", account, ifsc, amount)) {
                        case BankCore.Ok ok -> new SimResponse(HttpStatus.OK,
                                Map.of("result", Map.of("code", "00", "desc", "Approved", "bankRef", ok.reference())),
                                ok.amountRupees().doubleValue());
                        case BankCore.Rejected r -> SimResponse.rejection(HttpStatus.OK, result.apply("14|" + r.reason()));
                    }));
        } catch (Payload.BadRequest e) {
            return ResponseEntity.badRequest().body(result.apply("30|" + e.getMessage()));
        }
    }

    /** @return null if valid, otherwise why not */
    private String verifyOrbitSignature(String keyId, String timestamp, String signature, String path, String body) {
        if (keyId == null || timestamp == null || signature == null) {
            return "Missing signature headers";
        }
        if (!secrets.orbitKeyId().equals(keyId)) {
            return "Unknown key id";
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return "Bad timestamp";
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > ORBIT_MAX_CLOCK_SKEW_SECONDS) {
            return "Timestamp outside the allowed window (replayed or clock skew)";
        }
        String canonical = timestamp + "\n" + "POST" + "\n" + path + "\n" + sha256Hex(body);
        return constantTimeEquals(signature, hmacHex(secrets.orbitHmacSecret(), canonical)) ? null : "Signature does not match";
    }

    // ---------------------------------------------------------------- Kestrel

    @PostMapping("/kestrel/disbursements")
    public ResponseEntity<Map<String, Object>> kestrel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                       @RequestBody String raw) {
        Function<String, Map<String, Object>> declined = msg -> Map.of("status", "DECLINED", "declineReason", msg);
        String expected = "ApiKey " + secrets.kestrelApiKey();
        if (!constantTimeEquals(authorization, expected)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "ERROR", "declineReason", "Invalid API key"));
        }
        try {
            if (requestId == null || requestId.isBlank()) throw new Payload.BadRequest("X-Request-Id header is required");
            Payload p = parse(raw);
            long paise = p.wholeNumber("amountPaise");
            p.text("beneficiaryName", 50);
            String account = p.text("beneficiaryAccount");
            String ifsc = p.text("beneficiaryIfsc");
            BigDecimal amount = BigDecimal.valueOf(paise).movePointLeft(2);
            return SimulatedSystemsController.respond(chaos.run("KESTREL", requestId, () ->
                    switch (BankCore.transfer("KESTREL", account, ifsc, amount)) {
                        case BankCore.Ok ok -> new SimResponse(HttpStatus.CREATED,
                                Map.of("disbursementId", ok.reference(), "status", "COMPLETED"), ok.amountRupees().doubleValue());
                        case BankCore.Rejected r -> SimResponse.rejection(HttpStatus.UNPROCESSABLE_ENTITY, declined.apply(r.reason()));
                    }));
        } catch (Payload.BadRequest e) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "declineReason", e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private Payload parse(String raw) {
        try {
            return new Payload(mapper.readValue(raw, MAP));
        } catch (Exception e) {
            throw new Payload.BadRequest("Body is not valid JSON");
        }
    }

    static boolean constantTimeEquals(String provided, String expected) {
        return provided != null && expected != null
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
