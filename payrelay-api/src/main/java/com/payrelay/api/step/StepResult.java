package com.payrelay.api.step;

import com.payrelay.api.client.ExternalCallResult;
import com.payrelay.api.domain.AttemptOutcome;
import com.payrelay.api.domain.Payment;

import java.util.function.Consumer;

/**
 * What happened when a step ran. On success, {@code applyToPayment} stores the step's output
 * (e.g. the bank reference) on the payment.
 *
 * @param configVersion the bank config version used, for bank steps
 * @param note          something worth knowing that isn't an error, e.g. "token refreshed"
 */
public record StepResult(
        AttemptOutcome outcome,
        String reason,
        ExternalCallResult call,
        Consumer<Payment> applyToPayment,
        Long configVersion,
        String note) {

    public static StepResult success(ExternalCallResult call, Consumer<Payment> apply) {
        return new StepResult(AttemptOutcome.SUCCESS, null, call, apply, null, null);
    }

    public static StepResult retryable(ExternalCallResult call, String reason) {
        return new StepResult(AttemptOutcome.RETRYABLE_FAILURE, reason, call, p -> { }, null, null);
    }

    public static StepResult rejected(ExternalCallResult call, String reason) {
        return new StepResult(AttemptOutcome.REJECTED, reason, call, p -> { }, null, null);
    }

    public static StepResult configError(ExternalCallResult call, String reason) {
        return new StepResult(AttemptOutcome.CONFIG_ERROR, reason, call, p -> { }, null, null);
    }

    public StepResult withConfigVersion(long version) {
        return new StepResult(outcome, reason, call, applyToPayment, version, note);
    }

    public StepResult withNote(String note) {
        return new StepResult(outcome, reason, call, applyToPayment, configVersion, note);
    }

    /** Default mapping from a raw call to an outcome; handlers add business checks on top. */
    public static StepResult fromCall(ExternalCallResult call, Consumer<Payment> onSuccess) {
        return switch (call.kind()) {
            case OK -> success(call, onSuccess);
            case TRANSIENT_ERROR -> retryable(call, describe(call));
            case CLIENT_ERROR -> rejected(call, describe(call));
        };
    }

    public static String describe(ExternalCallResult call) {
        if (call.error() != null) {
            return call.error();
        }
        String message = call.str("message");
        return "HTTP " + call.httpStatus() + (message != null ? ": " + message : "");
    }
}
