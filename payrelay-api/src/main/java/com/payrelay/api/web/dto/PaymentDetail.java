package com.payrelay.api.web.dto;

import com.payrelay.api.domain.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentDetail(
        UUID id,
        String idempotencyKey,
        String correlationId,
        String customerId,
        String customerPan,
        BigDecimal amount,
        String currency,
        String beneficiaryName,
        String accountNumber,
        String ifsc,
        String bankCode,
        String purpose,
        PaymentStatus status,
        PaymentStep currentStep,
        int stepAttempts,
        int totalAttempts,
        String failureReason,
        String kycReference,
        Integer riskScore,
        String bankReference,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        List<Attempt> attempts) {

    public record Attempt(
            UUID id,
            PaymentStep step,
            int attemptNumber,
            AttemptOutcome outcome,
            Integer httpStatus,
            String request,
            String response,
            String error,
            long durationMs,
            Instant startedAt,
            Long configVersion,
            String note) {

        static Attempt from(StepAttempt a) {
            return new Attempt(a.getId(), a.getStep(), a.getAttemptNumber(), a.getOutcome(), a.getHttpStatus(),
                    a.getRequestBody(), a.getResponseBody(), a.getErrorMessage(), a.getDurationMs(), a.getStartedAt(),
                    a.getConfigVersion(), a.getNote());
        }
    }

    public static PaymentDetail from(Payment p, List<StepAttempt> attempts) {
        return new PaymentDetail(p.getId(), p.getIdempotencyKey(), p.getCorrelationId(), p.getCustomerId(),
                maskPan(p.getCustomerPan()), p.getAmount(), p.getCurrency(), p.getBeneficiaryName(),
                maskAccount(p.getAccountNumber()), p.getIfsc(), p.getBankCode(), p.getPurpose(), p.getStatus(),
                p.getStatus() == PaymentStatus.COMPLETED ? null : p.getCurrentStep(),
                p.getStepAttempts(), p.getTotalAttempts(), p.getFailureReason(), p.getKycReference(),
                p.getRiskScore(), p.getBankReference(), p.getNextAttemptAt(), p.getCreatedAt(), p.getUpdatedAt(),
                p.getCompletedAt(), attempts.stream().map(Attempt::from).toList());
    }

    /** Sensitive identifiers are masked in API responses. */
    static String maskPan(String pan) {
        return pan == null || pan.length() < 10 ? pan : pan.substring(0, 3) + "XXXXX" + pan.substring(8);
    }

    static String maskAccount(String account) {
        return account == null || account.length() <= 4 ? account : "X".repeat(account.length() - 4) + account.substring(account.length() - 4);
    }
}
