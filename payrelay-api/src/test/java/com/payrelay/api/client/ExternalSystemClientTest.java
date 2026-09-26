package com.payrelay.api.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSystemClientTest {

    @Test
    void classifiesStatusCodesForRetryDecisions() {
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(200))).isEqualTo(ExternalCallResult.Kind.OK);
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(201))).isEqualTo(ExternalCallResult.Kind.OK);
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(503))).isEqualTo(ExternalCallResult.Kind.TRANSIENT_ERROR);
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(504))).isEqualTo(ExternalCallResult.Kind.TRANSIENT_ERROR);
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(429))).isEqualTo(ExternalCallResult.Kind.TRANSIENT_ERROR);
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(408))).isEqualTo(ExternalCallResult.Kind.TRANSIENT_ERROR);
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(422))).isEqualTo(ExternalCallResult.Kind.CLIENT_ERROR);
        assertThat(ExternalSystemClient.classify(HttpStatusCode.valueOf(401))).isEqualTo(ExternalCallResult.Kind.CLIENT_ERROR);
    }

    @Test
    void redactsSensitiveValuesWhateverTheFieldIsCalled() {
        String snapshot = """
                POST /banks/x
                X-Nova-Key: nova-demo-key

                {"account_no":"123456789012","cdtr":{"acct":"123456789012"},"pan":"ABCDE1234F"}""";

        String out = ExternalSystemClient.redact(snapshot, List.of("123456789012", "ABCDE1234F"), List.of("nova-demo-key"));

        assertThat(out).doesNotContain("123456789012", "ABCDE1234F", "nova-demo-key");
        assertThat(out).contains("\"account_no\":\"XXXXXXXX9012\"", "\"acct\":\"XXXXXXXX9012\"", "XXXXXX234F", "X-Nova-Key: [hidden]");
    }
}
