package com.payrelay.api.step;

import com.payrelay.api.bank.BankConfigService;
import com.payrelay.api.bank.BankConfigService.LoadedBank;
import com.payrelay.api.bank.BankSpec;
import com.payrelay.api.bank.auth.BankAuthException;
import com.payrelay.api.bank.auth.BankAuthenticator;
import com.payrelay.api.bank.auth.OAuthTokenService;
import com.payrelay.api.bank.mapping.JsonPaths;
import com.payrelay.api.bank.mapping.MappingEngine;
import com.payrelay.api.bank.mapping.MappingException;
import com.payrelay.api.bank.mapping.MappingSources;
import com.payrelay.api.client.ExternalCallResult;
import com.payrelay.api.client.ExternalRequest;
import com.payrelay.api.client.ExternalSystemClient;
import com.payrelay.api.domain.Payment;
import com.payrelay.api.domain.PaymentStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves the money, through whichever bank the payment is for. Nothing here is bank-specific: the
 * endpoint, authentication, payload and response format all come from the bank's stored config.
 *
 * <p>Every attempt maps the same transfer key into the request (the validator refuses configs that
 * don't), so if a previous attempt reached the bank but its response was lost, the bank returns the
 * original result instead of paying twice.
 */
@Component
public class BankTransferStepHandler implements StepHandler {

    private final BankConfigService banks;
    private final MappingEngine mapping;
    private final BankAuthenticator authenticator;
    private final OAuthTokenService tokens;
    private final ExternalSystemClient client;

    public BankTransferStepHandler(BankConfigService banks, MappingEngine mapping, BankAuthenticator authenticator,
                                   OAuthTokenService tokens, ExternalSystemClient client) {
        this.banks = banks;
        this.mapping = mapping;
        this.authenticator = authenticator;
        this.tokens = tokens;
        this.client = client;
    }

    @Override
    public PaymentStep step() {
        return PaymentStep.BANK_TRANSFER;
    }

    @Override
    public StepResult execute(Payment payment) {
        LoadedBank bank = banks.load(payment.getBankCode());
        return run(bank, payment).withConfigVersion(bank.version());
    }

    private StepResult run(LoadedBank bank, Payment payment) {
        if (!bank.enabled()) {
            return StepResult.retryable(null, bank.displayName() + " is paused by an operator");
        }
        BankSpec spec = bank.spec();

        MappingEngine.MappedRequest mapped;
        try {
            mapped = mapping.map(spec, MappingSources.from(payment));
        } catch (MappingException e) {
            return StepResult.configError(null, "Mapping failed: " + e.getMessage());
        }
        String body = client.json(mapped.body());

        try {
            Sent sent = send(bank, spec, mapped, body, payment);
            if (sent.call().httpStatus() != null && sent.call().httpStatus() == 401 && sent.bearerToken() != null) {
                // The bank rejected a token we thought was valid (revoked or rotated early).
                // Drop it, fetch a new one and try once more inside this same attempt.
                tokens.invalidate(bank.code(), spec.auth(), sent.bearerToken());
                Sent retried = send(bank, spec, mapped, body, payment);
                return interpret(retried.call(), spec.response())
                        .withNote("Access token was rejected; fetched a new one and retried");
            }
            return interpret(sent.call(), spec.response());
        } catch (BankAuthException e) {
            return e.retryable()
                    ? StepResult.retryable(null, "Authentication: " + e.getMessage())
                    : StepResult.configError(null, "Authentication: " + e.getMessage());
        }
    }

    private record Sent(ExternalCallResult call, String bearerToken) {
    }

    private Sent send(LoadedBank bank, BankSpec spec, MappingEngine.MappedRequest mapped, String body, Payment payment) {
        String path = spec.endpoint().path();
        var auth = authenticator.authenticate(bank.code(), spec.auth(), path, body, payment.getCorrelationId());
        Map<String, String> headers = new LinkedHashMap<>(mapped.headers());
        headers.putAll(auth.headers());
        List<String> hide = new ArrayList<>(auth.secretsToHide());
        ExternalCallResult call = client.post(new ExternalRequest(path, body, headers, payment.getCorrelationId(),
                List.of(payment.getAccountNumber(), payment.getCustomerPan()), hide));
        return new Sent(call, auth.bearerToken());
    }

    /** Reads the bank's answer using the paths and status values from its config. */
    static StepResult interpret(ExternalCallResult call, BankSpec.Response r) {
        switch (call.kind()) {
            case TRANSIENT_ERROR -> {
                return StepResult.retryable(call, StepResult.describe(call));
            }
            case CLIENT_ERROR -> {
                if (call.httpStatus() == 401 || call.httpStatus() == 403) {
                    return StepResult.configError(call, "Bank rejected our credentials (HTTP " + call.httpStatus() + ")");
                }
                String reason = JsonPaths.getString(call.json(), r.reasonPath());
                return StepResult.rejected(call, reason != null ? reason : StepResult.describe(call));
            }
            default -> {
            }
        }
        String status = JsonPaths.getString(call.json(), r.statusPath());
        if (status != null && r.successValues().contains(status)) {
            String reference = JsonPaths.getString(call.json(), r.referencePath());
            if (reference == null) {
                // Success without a reference: we can't prove the transfer happened. Ask again (same key).
                return StepResult.retryable(call, "Bank reported " + status + " without a reference");
            }
            return StepResult.success(call, p -> p.setBankReference(reference));
        }
        if (status != null && r.rejectedValues() != null && r.rejectedValues().contains(status)) {
            String reason = JsonPaths.getString(call.json(), r.reasonPath());
            return StepResult.rejected(call, "Bank status " + status + (reason != null ? ": " + reason : ""));
        }
        // Unknown or pending status. Retrying with the same key is safe and will get the final answer.
        return StepResult.retryable(call, "Unrecognised bank status '" + status + "'");
    }

    public static String transferKey(Payment payment) {
        return payment.getId() + ":transfer";
    }
}
