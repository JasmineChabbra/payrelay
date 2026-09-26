package com.payrelay.api.bank.auth;

/**
 * Authentication could not be set up for a bank call.
 *
 * @see #retryable() true when the cause is temporary (token endpoint down), false when it's our
 * configuration (missing secret, credentials rejected)
 */
public class BankAuthException extends RuntimeException {

    private final boolean retryable;

    public BankAuthException(boolean retryable, String message) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
