package com.payrelay.api.worker;

import com.payrelay.api.config.PayRelayProperties;
import com.payrelay.api.domain.PaymentStatus;
import com.payrelay.api.repository.PaymentRepository;
import com.payrelay.api.service.PaymentProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Finds payments that are due (new, retry time reached, or abandoned by a crashed worker),
 * claims each one atomically, and hands it to the processor on a worker thread.
 *
 * <p>Because claiming is a conditional UPDATE in the database, you can run several API instances
 * and each payment is still processed by exactly one of them at a time.
 */
@Component
public class PaymentWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentWorker.class);
    private static final Set<PaymentStatus> READY = EnumSet.of(PaymentStatus.RECEIVED, PaymentStatus.RETRY_SCHEDULED);

    private final PaymentRepository payments;
    private final PaymentProcessor processor;
    private final ExecutorService pool;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PaymentWorker(PaymentRepository payments, PaymentProcessor processor, ExecutorService paymentWorkerPool,
                         TransactionTemplate tx, Clock clock, PayRelayProperties props) {
        this.payments = payments;
        this.processor = processor;
        this.pool = paymentWorkerPool;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = props.worker().batchSize();
        this.lease = props.worker().lease();
    }

    @Scheduled(fixedDelayString = "${payrelay.worker.poll-interval-ms}")
    public void poll() {
        List<UUID> due = payments.findDueIds(READY, clock.instant(), PageRequest.of(0, batchSize));
        due.forEach(this::submit);
    }

    /** Called right after a payment is created, so it starts without waiting for the next poll. */
    public void submit(UUID paymentId) {
        if (!inFlight.add(paymentId)) {
            return; // already queued or running on this instance
        }
        try {
            pool.submit(() -> {
                try {
                    if (claim(paymentId)) {
                        processor.process(paymentId);
                    }
                } catch (Exception e) {
                    // The lease will expire and another poll will pick it up again.
                    log.error("payment={} worker failed", paymentId, e);
                } finally {
                    inFlight.remove(paymentId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(paymentId);
        }
    }

    boolean claim(UUID paymentId) {
        Instant now = clock.instant();
        Integer updated = tx.execute(s -> payments.claim(paymentId, READY, now, now.plus(lease)));
        return updated != null && updated == 1;
    }
}
