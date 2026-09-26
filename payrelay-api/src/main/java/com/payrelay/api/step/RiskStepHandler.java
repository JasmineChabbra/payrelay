package com.payrelay.api.step;

import com.payrelay.api.client.ExternalCallResult;
import com.payrelay.api.client.ExternalRequest;
import com.payrelay.api.client.ExternalSystemClient;
import com.payrelay.api.config.PayRelayProperties;
import com.payrelay.api.domain.Payment;
import com.payrelay.api.domain.PaymentStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Asks the risk engine whether this disbursement should go ahead. */
@Component
public class RiskStepHandler implements StepHandler {

    private final ExternalSystemClient client;
    private final String apiKey;

    public RiskStepHandler(ExternalSystemClient client, PayRelayProperties props) {
        this.client = client;
        this.apiKey = props.simulator().apiKey();
    }

    @Override
    public PaymentStep step() {
        return PaymentStep.RISK_CHECK;
    }

    @Override
    public StepResult execute(Payment payment) {
        String body = client.json(Map.of(
                "customerId", payment.getCustomerId(),
                "amount", payment.getAmount(),
                "bankCode", payment.getBankCode(),
                "kycReference", String.valueOf(payment.getKycReference())));

        ExternalCallResult call = client.post(new ExternalRequest("/risk/score", body,
                Map.of("X-Api-Key", apiKey, "Idempotency-Key", payment.getId() + ":risk"),
                payment.getCorrelationId(), List.of(), List.of(apiKey)));

        if (call.kind() == ExternalCallResult.Kind.OK && "DECLINE".equals(call.str("decision"))) {
            return StepResult.rejected(call, "Declined by risk engine (score " + call.integer("score") + ")");
        }
        return StepResult.fromCall(call, p -> p.setRiskScore(call.integer("score")));
    }
}
