package com.payrelay.api.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "customer_pan", nullable = false)
    private String customerPan;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "beneficiary_name", nullable = false)
    private String beneficiaryName;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(nullable = false)
    private String ifsc;

    @Column(name = "bank_code", nullable = false)
    private String bankCode;

    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step")
    private PaymentStep currentStep;

    /** Attempts made on the current step. Resets when a step succeeds. */
    @Column(name = "step_attempts", nullable = false)
    private int stepAttempts;

    /** Attempts made across all steps, for reporting. */
    @Column(name = "total_attempts", nullable = false)
    private int totalAttempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** Lease: while in the future, one worker owns this payment. */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "kyc_reference")
    private String kycReference;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    protected Payment() {
        // for JPA
    }

    public static Payment receive(String idempotencyKey, String requestHash, String correlationId,
                                  String customerId, String customerPan, BigDecimal amount, String currency,
                                  String beneficiaryName, String accountNumber, String ifsc,
                                  String bankCode, String purpose, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.idempotencyKey = idempotencyKey;
        p.requestHash = requestHash;
        p.correlationId = correlationId;
        p.customerId = customerId;
        p.customerPan = customerPan;
        p.amount = amount;
        p.currency = currency;
        p.beneficiaryName = beneficiaryName;
        p.accountNumber = accountNumber;
        p.ifsc = ifsc;
        p.bankCode = bankCode;
        p.purpose = purpose;
        p.status = PaymentStatus.RECEIVED;
        p.currentStep = PaymentStep.first();
        p.nextAttemptAt = now;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    // ---- State transitions. All status changes go through these methods. ----

    public void recordAttempt(Instant now) {
        stepAttempts++;
        totalAttempts++;
        updatedAt = now;
    }

    /** Current step succeeded: move to the next step, or complete the payment. */
    public void advance(Instant now) {
        stepAttempts = 0;
        failureReason = null;
        updatedAt = now;
        currentStep.next().ifPresentOrElse(
                next -> currentStep = next,
                () -> {
                    status = PaymentStatus.COMPLETED;
                    completedAt = now;
                    nextAttemptAt = null;
                    lockedUntil = null;
                });
    }

    public void scheduleRetry(Instant retryAt, String reason, Instant now) {
        status = PaymentStatus.RETRY_SCHEDULED;
        nextAttemptAt = retryAt;
        lockedUntil = null;
        failureReason = reason;
        updatedAt = now;
    }

    public void reject(String reason, Instant now) {
        status = PaymentStatus.FAILED;
        failureReason = reason;
        nextAttemptAt = null;
        lockedUntil = null;
        completedAt = now;
        updatedAt = now;
    }

    public void deadLetter(String reason, Instant now) {
        status = PaymentStatus.DEAD_LETTERED;
        failureReason = reason;
        nextAttemptAt = null;
        lockedUntil = null;
        updatedAt = now;
    }

    /** Manual replay from the dead-letter queue. Resumes at the step that failed. */
    public void requeue(Instant now) {
        if (status != PaymentStatus.DEAD_LETTERED) {
            throw new IllegalStateException("Only dead-lettered payments can be replayed, current status: " + status);
        }
        status = PaymentStatus.RETRY_SCHEDULED;
        stepAttempts = 0;
        nextAttemptAt = now;
        updatedAt = now;
    }

    public boolean isStepCompleted(PaymentStep step) {
        if (status == PaymentStatus.COMPLETED) {
            return true;
        }
        return currentStep != null && step.ordinal() < currentStep.ordinal();
    }

    // ---- Step results ----

    public void setKycReference(String kycReference) { this.kycReference = kycReference; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }
    public void setBankReference(String bankReference) { this.bankReference = bankReference; }

    // ---- Getters ----

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getCorrelationId() { return correlationId; }
    public String getCustomerId() { return customerId; }
    public String getCustomerPan() { return customerPan; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getAccountNumber() { return accountNumber; }
    public String getIfsc() { return ifsc; }
    public String getBankCode() { return bankCode; }
    public String getPurpose() { return purpose; }
    public PaymentStatus getStatus() { return status; }
    public PaymentStep getCurrentStep() { return currentStep; }
    public int getStepAttempts() { return stepAttempts; }
    public int getTotalAttempts() { return totalAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getFailureReason() { return failureReason; }
    public String getKycReference() { return kycReference; }
    public Integer getRiskScore() { return riskScore; }
    public String getBankReference() { return bankReference; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public long getVersion() { return version; }
}
