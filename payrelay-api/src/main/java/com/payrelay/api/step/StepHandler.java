package com.payrelay.api.step;

import com.payrelay.api.domain.Payment;
import com.payrelay.api.domain.PaymentStep;

/** One step in the payment pipeline. Each step talks to one external system. */
public interface StepHandler {

    PaymentStep step();

    StepResult execute(Payment payment);
}
