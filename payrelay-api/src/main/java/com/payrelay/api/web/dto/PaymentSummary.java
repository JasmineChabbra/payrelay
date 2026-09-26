package com.payrelay.api.web.dto;

import com.payrelay.api.domain.Payment;
import com.payrelay.api.domain.PaymentStatus;
import com.payrelay.api.domain.PaymentStep;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentSummary(
        UUID id,
        String idempotencyKey,
        String customerId,
        BigDecimal amount,
        String currency,
        String beneficiaryName,
        String bankCode,
        PaymentStatus status,
        PaymentStep currentStep,
        int totalAttempts,
        String failureReason,
        String bankReference,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant completedAt) {

    public static PaymentSummary from(Payment p) {
        return new PaymentSummary(p.getId(), p.getIdempotencyKey(), p.getCustomerId(), p.getAmount(), p.getCurrency(),
                p.getBeneficiaryName(), p.getBankCode(), p.getStatus(),
                p.getStatus() == PaymentStatus.COMPLETED ? null : p.getCurrentStep(),
                p.getTotalAttempts(), p.getFailureReason(), p.getBankReference(), p.getNextAttemptAt(),
                p.getCreatedAt(), p.getCompletedAt());
    }
}
