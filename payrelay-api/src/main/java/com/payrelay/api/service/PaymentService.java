package com.payrelay.api.service;

import com.payrelay.api.config.PayRelayProperties;
import com.payrelay.api.domain.Payment;
import com.payrelay.api.domain.PaymentStatus;
import com.payrelay.api.bank.BankConfigService;
import com.payrelay.api.idempotency.IdempotencyLock;
import com.payrelay.api.repository.PaymentRepository;
import com.payrelay.api.repository.StepAttemptRepository;
import com.payrelay.api.web.dto.CreatePaymentRequest;
import com.payrelay.api.web.dto.PaymentDetail;
import com.payrelay.api.web.dto.PaymentSummary;
import com.payrelay.api.worker.PaymentWorker;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final StepAttemptRepository attempts;
    private final IdempotencyLock idempotencyLock;
    private final PaymentWorker worker;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration lockTtl;
    private final BankConfigService banks;

    public PaymentService(PaymentRepository payments, StepAttemptRepository attempts, IdempotencyLock idempotencyLock,
                          PaymentWorker worker, TransactionTemplate tx, Clock clock, PayRelayProperties props,
                          BankConfigService banks) {
        this.payments = payments;
        this.attempts = attempts;
        this.idempotencyLock = idempotencyLock;
        this.worker = worker;
        this.tx = tx;
        this.clock = clock;
        this.lockTtl = props.idempotency().lockTtl();
        this.banks = banks;
    }

    public record CreateResult(Payment payment, boolean replayed) {
    }

    /**
     * Creates a payment exactly once per idempotency key.
     * <ol>
     *   <li>Key already used with the same body → return the original payment (a safe replay).</li>
     *   <li>Key already used with a different body → 422, the client has a bug.</li>
     *   <li>Same key arriving concurrently → the Redis lock lets one through; the other gets 409.</li>
     *   <li>If the lock were ever bypassed, the unique constraint on the key still prevents a duplicate.</li>
     * </ol>
     */
    public CreateResult create(String idempotencyKey, CreatePaymentRequest req, String correlationId) {
        validateKey(idempotencyKey);
        if (!banks.exists(req.bankCode())) {
            throw ApiException.badRequest("Unsupported bankCode '" + req.bankCode() + "'. Supported: " + banks.codes());
        }
        String hash = hash(req);

        var existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        if (!idempotencyLock.tryAcquire(idempotencyKey, lockTtl)) {
            throw ApiException.requestInProgress();
        }
        try {
            Payment payment = Payment.receive(idempotencyKey, hash, correlationId, req.customerId(), req.customerPan(),
                    req.amount(), req.currency(), req.beneficiaryName(), req.accountNumber(), req.ifsc(),
                    req.bankCode(), req.purpose(), clock.instant());
            Payment saved = tx.execute(s -> payments.saveAndFlush(payment));
            worker.submit(saved.getId()); // after commit, so the worker can see the row
            return new CreateResult(saved, false);
        } catch (DataIntegrityViolationException e) {
            // Lost a race we didn't see coming (e.g. lock expired). The DB kept us correct.
            Payment winner = payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return replay(winner, hash);
        } finally {
            idempotencyLock.release(idempotencyKey);
        }
    }

    private CreateResult replay(Payment existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw ApiException.idempotencyMismatch();
        }
        return new CreateResult(existing, true);
    }

    public PaymentDetail get(UUID id) {
        Payment p = payments.findById(id).orElseThrow(() -> ApiException.notFound("Payment " + id + " not found"));
        return PaymentDetail.from(p, attempts.findByPaymentIdOrderByStartedAtAsc(id));
    }

    public Page<PaymentSummary> search(PaymentStatus status, String bankCode, int page, int size) {
        Specification<Payment> spec = (root, q, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (bankCode != null && !bankCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("bankCode"), bankCode));
        }
        var pageable = PageRequest.of(page, Math.min(Math.max(size, 1), 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        return payments.findAll(spec, pageable).map(PaymentSummary::from);
    }

    /** Manually replays a dead-lettered payment. It resumes at the step that failed. */
    public PaymentDetail replayDeadLetter(UUID id) {
        tx.executeWithoutResult(s -> {
            Payment p = payments.findById(id).orElseThrow(() -> ApiException.notFound("Payment " + id + " not found"));
            if (p.getStatus() != PaymentStatus.DEAD_LETTERED) {
                throw ApiException.conflict("Only DEAD_LETTERED payments can be replayed; this one is " + p.getStatus());
            }
            p.requeue(clock.instant());
            payments.save(p);
        });
        worker.submit(id);
        return get(id);
    }

    /** Demo reset. Payments still in flight are removed too, so their workers simply find nothing. */
    public void deleteAll() {
        tx.executeWithoutResult(s -> {
            attempts.deleteAllInBatch();
            payments.deleteAllInBatch();
        });
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw ApiException.badRequest("The Idempotency-Key header is required.");
        }
        if (key.length() > 100) {
            throw ApiException.badRequest("Idempotency-Key must be at most 100 characters.");
        }
    }

    /** A stable fingerprint of the request body, used to detect a key reused for a different payment. */
    static String hash(CreatePaymentRequest r) {
        String canonical = String.join("|", r.customerId(), r.customerPan(), r.amount().stripTrailingZeros().toPlainString(),
                r.currency(), r.beneficiaryName(), r.accountNumber(), r.ifsc(), r.bankCode(),
                r.purpose() == null ? "" : r.purpose());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
