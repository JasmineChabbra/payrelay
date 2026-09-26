package com.payrelay.simulator.chaos;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/** Counters per simulated system, so the console can prove nothing happened twice. */
public class TargetStats {

    final AtomicLong requests = new AtomicLong();
    final AtomicLong injectedFailures = new AtomicLong();
    final AtomicLong lostResponses = new AtomicLong();
    final AtomicLong uniqueOperations = new AtomicLong();
    final AtomicLong idempotentReplays = new AtomicLong();
    final AtomicLong businessRejections = new AtomicLong();
    final DoubleAdder amountMoved = new DoubleAdder();

    public record Snapshot(long requests, long injectedFailures, long lostResponses, long uniqueOperations,
                           long idempotentReplays, long businessRejections, double amountMoved) {
    }

    public Snapshot snapshot() {
        return new Snapshot(requests.get(), injectedFailures.get(), lostResponses.get(), uniqueOperations.get(),
                idempotentReplays.get(), businessRejections.get(), amountMoved.sum());
    }
}
