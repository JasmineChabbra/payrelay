package com.payrelay.api.domain;

public enum AttemptOutcome {
    /** The step succeeded. */
    SUCCESS,
    /** A temporary failure (timeout, 5xx, connection error). Safe to retry. */
    RETRYABLE_FAILURE,
    /** The external system said no (KYC rejected, invalid account). Retrying won't help. */
    REJECTED,
    /**
     * Our own configuration is wrong (a mapping rule fails, credentials are rejected). Retrying won't
     * help and it isn't the customer's fault, so the payment goes straight to review until the config is fixed.
     */
    CONFIG_ERROR
}
