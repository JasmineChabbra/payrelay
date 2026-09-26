package com.payrelay.api.domain;

/**
 * Lifecycle of a payment.
 *
 * <pre>
 * RECEIVED ──► PROCESSING ──► COMPLETED
 *                 │  ▲
 *                 ▼  │
 *          RETRY_SCHEDULED ──(attempts exhausted)──► DEAD_LETTERED ──(manual replay)──► RETRY_SCHEDULED
 *                 │
 *   (business rejection, e.g. KYC failed) ──► FAILED
 * </pre>
 */
public enum PaymentStatus {
    RECEIVED,
    PROCESSING,
    RETRY_SCHEDULED,
    COMPLETED,
    FAILED,
    DEAD_LETTERED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
