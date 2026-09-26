package com.payrelay.api.repository;

import com.payrelay.api.domain.Payment;
import com.payrelay.api.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    /** Payments that are ready to run, or whose worker lease expired (e.g. the worker crashed). */
    @Query("""
            select p.id from Payment p
            where (p.status in :ready and p.nextAttemptAt <= :now)
               or (p.status = com.payrelay.api.domain.PaymentStatus.PROCESSING and p.lockedUntil < :now)
            order by p.nextAttemptAt
            """)
    List<UUID> findDueIds(@Param("ready") Collection<PaymentStatus> ready,
                          @Param("now") Instant now,
                          Pageable pageable);

    /**
     * Atomically claims a payment for one worker. It's a single conditional UPDATE, so if two workers
     * race for the same payment, the database lets exactly one of them win (the other gets 0 rows).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Payment p
               set p.status = com.payrelay.api.domain.PaymentStatus.PROCESSING,
                   p.lockedUntil = :leaseUntil,
                   p.version = p.version + 1
             where p.id = :id
               and ((p.status in :ready and p.nextAttemptAt <= :now)
                    or (p.status = com.payrelay.api.domain.PaymentStatus.PROCESSING and p.lockedUntil < :now))
            """)
    int claim(@Param("id") UUID id,
              @Param("ready") Collection<PaymentStatus> ready,
              @Param("now") Instant now,
              @Param("leaseUntil") Instant leaseUntil);

    @Query("select p.status, count(p) from Payment p group by p.status")
    List<Object[]> countByStatus();

    @Query("select p.bankCode, p.status, count(p) from Payment p group by p.bankCode, p.status")
    List<Object[]> countByBankAndStatus();

    List<Payment> findByCreatedAtAfter(Instant since);

    List<Payment> findTop1000ByStatusOrderByCompletedAtDesc(PaymentStatus status);
}
