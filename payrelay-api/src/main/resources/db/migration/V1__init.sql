-- PayRelay schema v1
-- Kept to portable SQL so it runs on PostgreSQL and on H2 (PostgreSQL mode) for local dev.

CREATE TABLE payments (
    id                  UUID PRIMARY KEY,
    idempotency_key     VARCHAR(100)   NOT NULL,
    request_hash        VARCHAR(64)    NOT NULL,
    correlation_id      VARCHAR(64)    NOT NULL,

    customer_id         VARCHAR(50)    NOT NULL,
    customer_pan        VARCHAR(10)    NOT NULL,
    amount              NUMERIC(18, 2) NOT NULL,
    currency            VARCHAR(3)     NOT NULL,
    beneficiary_name    VARCHAR(120)   NOT NULL,
    account_number      VARCHAR(20)    NOT NULL,
    ifsc                VARCHAR(11)    NOT NULL,
    bank_code           VARCHAR(20)    NOT NULL,
    purpose             VARCHAR(200),

    status              VARCHAR(20)    NOT NULL,
    current_step        VARCHAR(20),
    step_attempts       INT            NOT NULL DEFAULT 0,
    total_attempts      INT            NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP WITH TIME ZONE,
    locked_until        TIMESTAMP WITH TIME ZONE,
    failure_reason      VARCHAR(500),

    kyc_reference       VARCHAR(50),
    risk_score          INT,
    bank_reference      VARCHAR(50),

    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at        TIMESTAMP WITH TIME ZONE,
    version             BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key)
);

-- The worker polls for due work using this index.
CREATE INDEX idx_payments_status_next_attempt ON payments (status, next_attempt_at);
CREATE INDEX idx_payments_bank_code ON payments (bank_code);
CREATE INDEX idx_payments_created_at ON payments (created_at);

-- Every call to an external system is recorded, so any payment can be debugged step by step.
CREATE TABLE step_attempts (
    id                  UUID PRIMARY KEY,
    payment_id          UUID          NOT NULL REFERENCES payments (id),
    step                VARCHAR(20)   NOT NULL,
    attempt_number      INT           NOT NULL,
    outcome             VARCHAR(20)   NOT NULL,
    http_status         INT,
    request_body        TEXT,
    response_body       TEXT,
    error_message       VARCHAR(1000),
    duration_ms         BIGINT        NOT NULL,
    correlation_id      VARCHAR(64)   NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_step_attempts_payment ON step_attempts (payment_id, started_at);
