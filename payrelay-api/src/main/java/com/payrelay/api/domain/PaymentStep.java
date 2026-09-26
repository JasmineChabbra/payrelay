package com.payrelay.api.domain;

import java.util.Optional;

/**
 * The ordered steps every payment goes through. A payment records the step it is on,
 * so a retry resumes from that step and never repeats a step that already succeeded.
 */
public enum PaymentStep {
    KYC_CHECK,
    RISK_CHECK,
    BANK_TRANSFER;

    public static PaymentStep first() {
        return KYC_CHECK;
    }

    public Optional<PaymentStep> next() {
        PaymentStep[] all = values();
        int nextIndex = ordinal() + 1;
        return nextIndex < all.length ? Optional.of(all[nextIndex]) : Optional.empty();
    }
}
