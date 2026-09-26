package com.payrelay.api.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrelay.api.bank.BankConfigService;
import com.payrelay.api.bank.BankSpec;
import com.payrelay.api.client.ExternalRequest;
import com.payrelay.api.client.ExternalSystemClient;
import com.payrelay.api.domain.*;
import com.payrelay.api.repository.PaymentRepository;
import com.payrelay.api.repository.StepAttemptRepository;
import com.payrelay.api.service.PaymentProcessor;
import com.payrelay.api.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static com.payrelay.api.TestCalls.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests of the orchestration logic against a real (H2) database and the seeded bank
 * configs, with the external systems scripted. The worker is driven synchronously so every test is
 * deterministic.
 */
@SpringBootTest(properties = {
        "payrelay.worker.poll-interval-ms=3600000", // background polling off; tests drive the worker
        "payrelay.retry.base-delay=0ms",
        "payrelay.retry.max-attempts-per-step=3"
})
@ActiveProfiles("dev")
class PaymentOrchestrationTest {

    private static final String KYC = "/kyc/verify";
    private static final String RISK = "/risk/score";
    private static final String NOVA = "/banks/nova/v1/payouts";
    private static final String ZENITH = "/banks/zenith/v2/transfers";
    private static final String KESTREL = "/banks/kestrel/disbursements";
    private static final String ORBIT = "/banks/orbit/api/credit";

    @MockitoBean
    ExternalSystemClient client;

    @Autowired PaymentRepository payments;
    @Autowired StepAttemptRepository attempts;
    @Autowired PaymentWorker worker;
    @Autowired PaymentProcessor processor;
    @Autowired PaymentService service;
    @Autowired BankConfigService banks;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void happyDefaults() {
        when(client.json(any())).thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
        when(client.post(path(KYC))).thenReturn(ok(Map.of("status", "VERIFIED", "kycReference", "KYC-1")));
        when(client.post(path(RISK))).thenReturn(ok(Map.of("score", 720, "decision", "APPROVE")));
        when(client.post(path(NOVA))).thenReturn(ok(Map.of("status", "SUCCESS", "utr", "NOVA-REF-1")));
    }

    @Test
    void happyPathCompletesAllStepsOnce() {
        Payment p = drive(newPayment("NOVA"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(p.getKycReference()).isEqualTo("KYC-1");
        assertThat(p.getRiskScore()).isEqualTo(720);
        assertThat(p.getBankReference()).isEqualTo("NOVA-REF-1");
        assertThat(p.getTotalAttempts()).isEqualTo(3);
        List<StepAttempt> list = attempts.findByPaymentIdOrderByStartedAtAsc(p.getId());
        assertThat(list).extracting(StepAttempt::getStep)
                .containsExactly(PaymentStep.KYC_CHECK, PaymentStep.RISK_CHECK, PaymentStep.BANK_TRANSFER);
        assertThat(list.getLast().getConfigVersion()).isNotNull(); // records which bank config was used
    }

    @Test
    void novaRequestIsBuiltFromItsConfig() {
        Payment p = drive(newPayment("NOVA"));

        verify(client).post(argThat(r -> r != null && NOVA.equals(r.path())
                && "nova-demo-key".equals(r.headers().get("X-Nova-Key"))
                && (p.getId() + ":transfer").equals(r.headers().get("Idempotency-Key"))
                && r.bodyJson().contains("\"account_no\":\"123456789012\"")
                && r.hideValues().contains("nova-demo-key")));
    }

    @Test
    void retryResumesFromFailedStepWithoutRepeatingCompletedSteps() {
        when(client.post(path(NOVA))).thenReturn(serverError(), timeout(), ok(Map.of("status", "SUCCESS", "utr", "NOVA-REF-9")));

        Payment p = drive(newPayment("NOVA"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(p.getBankReference()).isEqualTo("NOVA-REF-9");
        verify(client, times(1)).post(path(KYC));
        verify(client, times(1)).post(path(RISK));
        verify(client, times(3)).post(path(NOVA));
    }

    @Test
    void everyTransferAttemptSendsTheSameIdempotencyKey() {
        when(client.post(path(NOVA))).thenReturn(timeout(), ok(Map.of("status", "SUCCESS", "utr", "NOVA-REF-2")));

        Payment p = drive(newPayment("NOVA"));

        String expectedKey = p.getId() + ":transfer";
        verify(client, times(2)).post(argThat(r -> r != null && NOVA.equals(r.path())
                && expectedKey.equals(r.headers().get("Idempotency-Key"))));
    }

    @Test
    void businessRejectionStopsImmediatelyWithoutRetrying() {
        when(client.post(path(KYC))).thenReturn(ok(Map.of("status", "REJECTED")));

        Payment p = drive(newPayment("NOVA"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).contains("KYC_CHECK rejected");
        verify(client, never()).post(path(RISK));
        verify(client, never()).post(path(NOVA));
    }

    @Test
    void invalidAccountFromBankIsARejectionNotARetry() {
        when(client.post(path(NOVA))).thenReturn(clientError(422, "Beneficiary account does not exist"));

        Payment p = drive(newPayment("NOVA"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).contains("Beneficiary account does not exist");
        verify(client, times(1)).post(path(NOVA));
    }

    @Test
    void rejectionReportedInsideA200IsReadFromTheConfiguredPaths() {
        when(client.postForm(eq("/banks/zenith/oauth/token"), anyMap(), anyString(), anyCollection()))
                .thenReturn(ok(Map.of("access_token", "tok-1", "expires_in", 300)));
        when(client.post(path(ZENITH))).thenReturn(ok(Map.of("data", Map.of("state", "REJECTED", "reason", "Account closed"))));

        Payment p = drive(newPayment("ZENITH"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).contains("REJECTED").contains("Account closed");
    }

    @Test
    void rejectedOAuthTokenIsRefreshedAndRetriedWithinTheSameAttempt() {
        when(client.postForm(eq("/banks/zenith/oauth/token"), anyMap(), anyString(), anyCollection()))
                .thenReturn(ok(Map.of("access_token", "old-token", "expires_in", 300)),
                        ok(Map.of("access_token", "new-token", "expires_in", 300)));
        when(client.post(path(ZENITH))).thenReturn(
                clientError(401, "invalid_token"),
                ok(Map.of("data", Map.of("state", "PROCESSED", "transferId", "ZEN-1"))));

        Payment p = drive(newPayment("ZENITH"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(p.getBankReference()).isEqualTo("ZEN-1");
        StepAttempt transfer = attempts.findByPaymentIdOrderByStartedAtAsc(p.getId()).getLast();
        assertThat(transfer.getAttemptNumber()).isEqualTo(1); // no retry cycle was spent
        assertThat(transfer.getNote()).contains("fetched a new one");
        verify(client).post(argThat(r -> r != null && ZENITH.equals(r.path())
                && "Bearer new-token".equals(r.headers().get("Authorization"))));
    }

    @Test
    void orbitRequestsAreSigned() {
        when(client.post(path(ORBIT))).thenReturn(ok(Map.of("result", Map.of("code", "00", "bankRef", "ORB-1"))));

        Payment p = drive(newPayment("ORBIT"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(client).post(argThat(r -> r != null && ORBIT.equals(r.path())
                && r.headers().get("X-Orbit-Signature") != null
                && r.headers().get("X-Orbit-Signature").matches("[0-9a-f]{64}")
                && "payrelay-01".equals(r.headers().get("X-Orbit-Key-Id"))
                && r.bodyJson().contains("\"nm\":\"AARAV SHARMA\"")));
    }

    @Test
    void brokenMappingGoesStraightToReviewAndReplaysAfterTheFix() {
        when(client.post(path(KESTREL))).thenReturn(ok(Map.of("status", "COMPLETED", "disbursementId", "KES-1")));
        var original = banks.get("KESTREL");
        try {
            // A lookup with no entry for "Loan disbursement" and no "*" fallback: valid config, fails at runtime.
            BankSpec broken = withNarrationLookup(original.spec(), Map.of("Refund", "RF"));
            var saved = banks.update("KESTREL", new BankConfigService.UpdateRequest(original.displayName(), true, original.version(), broken));

            Payment p = drive(newPayment("KESTREL"));
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.DEAD_LETTERED);
            assertThat(p.getFailureReason()).contains("Configuration error").contains("no entry for 'Loan disbursement'");
            assertThat(p.getStepAttempts()).isEqualTo(1); // no pointless retries
            verify(client, never()).post(path(KESTREL));

            BankSpec fixed = withNarrationLookup(original.spec(), Map.of("Refund", "RF", "*", "LOAN"));
            banks.update("KESTREL", new BankConfigService.UpdateRequest(original.displayName(), true, saved.version(), fixed));
            service.replayDeadLetter(p.getId());
            p = drive(p.getId());

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            verify(client, times(1)).post(path(KYC)); // earlier steps were not repeated
            verify(client).post(argThat(r -> r != null && KESTREL.equals(r.path()) && r.bodyJson().contains("\"narration\":\"LOAN\"")));
        } finally {
            restore("KESTREL", original);
        }
    }

    @Test
    void pausedBankWaitsInsteadOfFailing() {
        var original = banks.get("NOVA");
        try {
            banks.update("NOVA", new BankConfigService.UpdateRequest(original.displayName(), false, original.version(), original.spec()));
            UUID id = newPayment("NOVA");
            assertThat(worker.claim(id)).isTrue();
            processor.process(id);

            Payment p = payments.findById(id).orElseThrow();
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.RETRY_SCHEDULED);
            assertThat(p.getFailureReason()).contains("paused");
            verify(client, never()).post(path(NOVA));
        } finally {
            restore("NOVA", original);
        }
    }

    @Test
    void exhaustedRetriesGoToDeadLetterAndReplayResumesAtFailedStep() {
        when(client.post(path(NOVA))).thenReturn(serverError(), serverError(), serverError(),
                ok(Map.of("status", "SUCCESS", "utr", "NOVA-REF-3")));

        Payment p = drive(newPayment("NOVA"));
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.DEAD_LETTERED);
        assertThat(p.getCurrentStep()).isEqualTo(PaymentStep.BANK_TRANSFER);

        service.replayDeadLetter(p.getId());
        p = drive(p.getId());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(p.getBankReference()).isEqualTo("NOVA-REF-3");
        verify(client, times(1)).post(path(KYC));
        verify(client, times(1)).post(path(RISK));
    }

    @Test
    void onlyOneWorkerCanClaimAPayment() {
        UUID id = newPayment("NOVA");
        assertThat(worker.claim(id)).isTrue();
        assertThat(worker.claim(id)).isFalse();
    }

    @Test
    void unexpectedExceptionIsTreatedAsRetryableNotLost() {
        when(client.post(path(RISK)))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(ok(Map.of("score", 700, "decision", "APPROVE")));

        Payment p = drive(newPayment("NOVA"));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    // ---- helpers ----

    private static ExternalRequest path(String path) {
        return argThat(r -> r != null && path.equals(r.path()));
    }

    private UUID newPayment(String bank) {
        Payment p = Payment.receive("test-" + UUID.randomUUID(), "hash", "cid-" + UUID.randomUUID(),
                "CUST1", "ABCDE1234F", new BigDecimal("25000.00"), "INR", "Aarav Sharma",
                "123456789012", bank.substring(0, 4) + "0001234", bank, "Loan disbursement", Instant.now().minusSeconds(1));
        return payments.save(p).getId();
    }

    private static BankSpec withNarrationLookup(BankSpec spec, Map<String, String> table) {
        List<BankSpec.FieldRule> body = new ArrayList<>();
        for (var r : spec.request().body()) {
            body.add("narration".equals(r.target())
                    ? new BankSpec.FieldRule("narration", "purpose", null, List.of("LOOKUP:narrations"), true, null)
                    : r);
        }
        Map<String, Map<String, String>> lookups = new HashMap<>(spec.lookupsOrEmpty());
        lookups.put("narrations", table);
        return new BankSpec(spec.endpoint(), spec.auth(), new BankSpec.Request(spec.request().headers(), body), spec.response(), lookups);
    }

    private void restore(String code, BankConfigService.BankView original) {
        long current = banks.get(code).version();
        banks.update(code, new BankConfigService.UpdateRequest(original.displayName(), original.enabled(), current, original.spec()));
    }

    /** Claims and processes repeatedly, like the background worker would, until the payment settles. */
    private Payment drive(UUID id) {
        for (int i = 0; i < 20; i++) {
            Payment p = payments.findById(id).orElseThrow();
            if (p.getStatus().isTerminal() || p.getStatus() == PaymentStatus.DEAD_LETTERED) {
                return p;
            }
            if (worker.claim(id)) {
                processor.process(id);
            } else {
                sleep();
            }
        }
        throw new AssertionError("Payment did not settle: " + payments.findById(id).orElseThrow().getStatus());
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
