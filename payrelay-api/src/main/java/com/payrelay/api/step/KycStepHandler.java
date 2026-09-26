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

/** Verifies the customer's identity with the KYC registry. */
@Component
public class KycStepHandler implements StepHandler {

    private final ExternalSystemClient client;
    private final String apiKey;

    public KycStepHandler(ExternalSystemClient client, PayRelayProperties props) {
        this.client = client;
        this.apiKey = props.simulator().apiKey();
    }

    @Override
    public PaymentStep step() {
        return PaymentStep.KYC_CHECK;
    }

    @Override
    public StepResult execute(Payment payment) {
        String body = client.json(Map.of(
                "customerId", payment.getCustomerId(),
                "pan", payment.getCustomerPan(),
                "name", payment.getBeneficiaryName()));

        ExternalCallResult call = client.post(new ExternalRequest("/kyc/verify", body,
                Map.of("X-Api-Key", apiKey, "Idempotency-Key", payment.getId() + ":kyc"),
                payment.getCorrelationId(), List.of(payment.getCustomerPan()), List.of(apiKey)));

        if (call.kind() == ExternalCallResult.Kind.OK && !"VERIFIED".equals(call.str("status"))) {
            return StepResult.rejected(call, "KYC not verified: " + call.str("status"));
        }
        return StepResult.fromCall(call, p -> p.setKycReference(call.str("kycReference")));
    }
}
