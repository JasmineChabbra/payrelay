package com.payrelay.api.service;

import com.payrelay.api.domain.Payment;
import com.payrelay.api.domain.PaymentStatus;
import com.payrelay.api.domain.PaymentStep;
import com.payrelay.api.bank.BankConfigService;
import com.payrelay.api.repository.PaymentRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Aggregates for the operations dashboard. */
@Service
public class StatsService {

    private static final int WINDOW_MINUTES = 30;

    private final PaymentRepository payments;
    private final BankConfigService banks;
    private final Clock clock;

    public StatsService(PaymentRepository payments, BankConfigService banks, Clock clock) {
        this.payments = payments;
        this.banks = banks;
        this.clock = clock;
    }

    public record BankStats(String bankCode, long total, long completed, long failed, long deadLettered, long inFlight) {
    }

    public record MinuteBucket(Instant minute, long created, long completed, long failed) {
    }

    public record Stats(
            Map<PaymentStatus, Long> byStatus,
            long total,
            double successRate,
            long recoveredAfterFailure,
            double avgAttemptsPerCompleted,
            Long latencyP50Ms,
            Long latencyP95Ms,
            List<BankStats> banks,
            List<MinuteBucket> throughput) {
    }

    public Stats compute() {
        Map<PaymentStatus, Long> byStatus = new EnumMap<>(PaymentStatus.class);
        for (PaymentStatus s : PaymentStatus.values()) {
            byStatus.put(s, 0L);
        }
        for (Object[] row : payments.countByStatus()) {
            byStatus.put((PaymentStatus) row[0], (Long) row[1]);
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long completed = byStatus.get(PaymentStatus.COMPLETED);
        long finished = completed + byStatus.get(PaymentStatus.FAILED) + byStatus.get(PaymentStatus.DEAD_LETTERED);
        double successRate = finished == 0 ? 0 : (double) completed / finished;

        List<Payment> recentCompleted = payments.findTop1000ByStatusOrderByCompletedAtDesc(PaymentStatus.COMPLETED);
        int minAttempts = PaymentStep.values().length;
        long recovered = recentCompleted.stream().filter(p -> p.getTotalAttempts() > minAttempts).count();
        double avgAttempts = recentCompleted.stream().mapToInt(Payment::getTotalAttempts).average().orElse(0);
        long[] latencies = recentCompleted.stream()
                .filter(p -> p.getCompletedAt() != null)
                .mapToLong(p -> Duration.between(p.getCreatedAt(), p.getCompletedAt()).toMillis())
                .sorted().toArray();

        return new Stats(byStatus, total, successRate, recovered, avgAttempts,
                percentile(latencies, 50), percentile(latencies, 95), bankStats(), throughput());
    }

    private List<BankStats> bankStats() {
        Map<String, Map<PaymentStatus, Long>> counts = new LinkedHashMap<>();
        banks.codes().forEach(b -> counts.put(b, new EnumMap<>(PaymentStatus.class)));
        for (Object[] row : payments.countByBankAndStatus()) {
            counts.computeIfAbsent((String) row[0], k -> new EnumMap<>(PaymentStatus.class))
                    .put((PaymentStatus) row[1], (Long) row[2]);
        }
        List<BankStats> result = new ArrayList<>();
        counts.forEach((bank, m) -> {
            long c = m.getOrDefault(PaymentStatus.COMPLETED, 0L);
            long f = m.getOrDefault(PaymentStatus.FAILED, 0L);
            long d = m.getOrDefault(PaymentStatus.DEAD_LETTERED, 0L);
            long inFlight = m.getOrDefault(PaymentStatus.RECEIVED, 0L)
                    + m.getOrDefault(PaymentStatus.PROCESSING, 0L)
                    + m.getOrDefault(PaymentStatus.RETRY_SCHEDULED, 0L);
            result.add(new BankStats(bank, c + f + d + inFlight, c, f, d, inFlight));
        });
        return result;
    }

    private List<MinuteBucket> throughput() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Instant start = now.minus(WINDOW_MINUTES - 1, ChronoUnit.MINUTES);
        long[] created = new long[WINDOW_MINUTES];
        long[] completed = new long[WINDOW_MINUTES];
        long[] failed = new long[WINDOW_MINUTES];

        for (Payment p : payments.findByCreatedAtAfter(start)) {
            bump(created, start, p.getCreatedAt());
            if (p.getStatus() == PaymentStatus.COMPLETED) {
                bump(completed, start, p.getCompletedAt());
            } else if (p.getStatus() == PaymentStatus.FAILED || p.getStatus() == PaymentStatus.DEAD_LETTERED) {
                bump(failed, start, p.getUpdatedAt());
            }
        }
        List<MinuteBucket> buckets = new ArrayList<>(WINDOW_MINUTES);
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            buckets.add(new MinuteBucket(start.plus(i, ChronoUnit.MINUTES), created[i], completed[i], failed[i]));
        }
        return buckets;
    }

    private static void bump(long[] buckets, Instant start, Instant at) {
        if (at == null) {
            return;
        }
        long idx = Duration.between(start, at).toMinutes();
        if (idx >= 0 && idx < buckets.length) {
            buckets[(int) idx]++;
        }
    }

    static Long percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return null;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
