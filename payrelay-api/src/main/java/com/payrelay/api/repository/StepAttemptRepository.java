package com.payrelay.api.repository;

import com.payrelay.api.domain.StepAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StepAttemptRepository extends JpaRepository<StepAttempt, UUID> {

    List<StepAttempt> findByPaymentIdOrderByStartedAtAsc(UUID paymentId);
}
