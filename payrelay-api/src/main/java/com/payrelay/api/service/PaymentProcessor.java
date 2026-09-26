package com.payrelay.api.service;

import com.payrelay.api.client.ExternalCallResult;
import com.payrelay.api.config.PayRelayProperties;
import com.payrelay.api.domain.*;
import com.payrelay.api.repository.PaymentRepository;
import com.payrelay.api.repository.StepAttemptRepository;
import com.payrelay.api.step.StepHandler;
import com.payrelay.api.step.StepResult;
import com.payrelay.api.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a claimed payment through its remaining steps.
 *
 * <p>Key rules:
 * <ul>
 *   <li>A payment always resumes at {@code currentStep}. Completed steps are never run again.</li>
 *   <li>No database transaction is held open during an external HTTP call. Each state change is
 *       committed in its own short transaction, so a slow bank never ties up a DB connection.</li>
 *   <li>Temporary failures are retried with backoff; business rejections stop immediately;
 *       exhausted retries go to the dead-letter queue for a human to look at.</li>
 * </ul>
 */
@Service
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    private final PaymentRepository payments;
    private final StepAttemptRepository attempts;
    private final Map<PaymentStep, StepHandler> handlers = new EnumMap<>(PaymentStep.class);
    private final BackoffPolicy backoff;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int maxAttemptsPerStep;

    public PaymentProcessor(PaymentRepository payments, StepAttemptRepository attempts,
                            List<StepHandler> stepHandlers, BackoffPolicy backoff,
                            TransactionTemplate tx, Clock clock, PayRelayProperties props) {
        this.payments = payments;
        this.attempts = attempts;
        this.backoff = backoff;
        this.tx = tx;
        this.clock = clock;
        this.maxAttemptsPerStep = props.retry().maxAttemptsPerStep();
        stepHandlers.forEach(h -> handlers.put(h.step(), h));
        for (PaymentStep step : PaymentStep.values()) {
            if (!handlers.containsKey(step)) {
                throw new IllegalStateException("No handler registered for step " + step);
            }
        }
    }

    /** Processes a payment this worker has already claimed. */
    public void process(UUID paymentId) {
        Payment payment = payments.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.PROCESSING) {
            return;
        }
        MDC.put(CorrelationIdFilter.MDC_KEY, payment.getCorrelationId());
        try {
            while (payment.getStatus() == PaymentStatus.PROCESSING) {
                payment = runCurrentStep(payment);
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private Payment runCurrentStep(Payment payment) {
        PaymentStep step = payment.getCurrentStep();
        StepHandler handler = handlers.get(step);

        payment.recordAttempt(clock.instant());
        payment = save(payment);
        int attemptNumber = payment.getStepAttempts();
        Instant startedAt = clock.instant();

        StepResult result = executeSafely(handler, payment);

        Payment current = payment;
        return tx.execute(status -> {
            attempts.save(toAttempt(current, step, attemptNumber, result, startedAt));
            Instant now = clock.instant();
            switch (result.outcome()) {
                case SUCCESS -> {
                    result.applyToPayment().accept(current);
                    current.advance(now);
                    log.info("payment={} step={} succeeded on attempt {}", current.getId(), step, attemptNumber);
                }
                case REJECTED -> {
                    current.reject(step + " rejected: " + result.reason(), now);
                    log.info("payment={} step={} rejected: {}", current.getId(), step, result.reason());
                }
                case CONFIG_ERROR -> {
                    current.deadLetter("Configuration error at " + step + ": " + result.reason(), now);
                    log.warn("payment={} step={} configuration error: {}", current.getId(), step, result.reason());
                }
                case RETRYABLE_FAILURE -> {
                    if (attemptNumber >= maxAttemptsPerStep) {
                        current.deadLetter(step + " failed after " + attemptNumber + " attempts: " + result.reason(), now);
                        log.warn("payment={} step={} dead-lettered after {} attempts", current.getId(), step, attemptNumber);
                    } else {
                        Duration delay = backoff.delayAfter(attemptNumber);
                        current.scheduleRetry(now.plus(delay), result.reason(), now);
                        log.info("payment={} step={} attempt {} failed ({}), retrying in {} ms",
                                current.getId(), step, attemptNumber, result.reason(), delay.toMillis());
                    }
                }
            }
            return payments.save(current);
        });
    }

    private StepResult executeSafely(StepHandler handler, Payment payment) {
        try {
            return handler.execute(payment);
        } catch (RuntimeException e) {
            // A bug in our own code shouldn't lose the payment. Record it and retry like any transient failure.
            log.error("payment={} step={} threw unexpectedly", payment.getId(), handler.step(), e);
            var call = new ExternalCallResult(ExternalCallResult.Kind.TRANSIENT_ERROR, null, null, null,
                    Map.of(), e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
            return StepResult.retryable(call, call.error());
        }
    }

    private Payment save(Payment payment) {
        return tx.execute(status -> payments.save(payment));
    }

    private StepAttempt toAttempt(Payment p, PaymentStep step, int attemptNumber, StepResult r, Instant startedAt) {
        ExternalCallResult call = r.call();
        return new StepAttempt(p.getId(), step, attemptNumber, r.outcome(),
                call == null ? null : call.httpStatus(),
                call == null ? null : call.requestSnapshot(),
                call == null ? null : call.responseBody(),
                r.outcome() == AttemptOutcome.SUCCESS ? null : r.reason(),
                call == null ? 0 : call.durationMs(),
                p.getCorrelationId(), startedAt, r.configVersion(), r.note());
    }
}
