package com.payrelay.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One call to an external system, with its full request and response. Append-only. */
@Entity
@Table(name = "step_attempts")
public class StepAttempt {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentStep step;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AttemptOutcome outcome;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "request_body", updatable = false)
    private String requestBody;

    @Column(name = "response_body", updatable = false)
    private String responseBody;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private String correlationId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "config_version", updatable = false)
    private Long configVersion;

    @Column(updatable = false)
    private String note;

    protected StepAttempt() {
    }

    public StepAttempt(UUID paymentId, PaymentStep step, int attemptNumber, AttemptOutcome outcome,
                       Integer httpStatus, String requestBody, String responseBody, String errorMessage,
                       long durationMs, String correlationId, Instant startedAt, Long configVersion, String note) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.step = step;
        this.attemptNumber = attemptNumber;
        this.outcome = outcome;
        this.httpStatus = httpStatus;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
        this.errorMessage = truncate(errorMessage, 1000);
        this.durationMs = durationMs;
        this.correlationId = correlationId;
        this.startedAt = startedAt;
        this.configVersion = configVersion;
        this.note = truncate(note, 300);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public PaymentStep getStep() { return step; }
    public int getAttemptNumber() { return attemptNumber; }
    public AttemptOutcome getOutcome() { return outcome; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getRequestBody() { return requestBody; }
    public String getResponseBody() { return responseBody; }
    public String getErrorMessage() { return errorMessage; }
    public long getDurationMs() { return durationMs; }
    public String getCorrelationId() { return correlationId; }
    public Instant getStartedAt() { return startedAt; }
    public Long getConfigVersion() { return configVersion; }
    public String getNote() { return note; }
}
