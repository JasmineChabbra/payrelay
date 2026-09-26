package com.payrelay.simulator;

import com.payrelay.simulator.chaos.ChaosConfig;
import com.payrelay.simulator.chaos.ChaosEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BankSimulatorTest {

    private static final String NOVA = """
            {"txn_ref":"p1","amount":25000.00,"currency":"INR","beneficiary_name":"Aarav Sharma",
             "account_no":"123456789012","ifsc_code":"NOVA0001234","remarks":"Loan"}
            """;
    private static final String ZENITH = """
            {"clientReference":"p2:transfer","payment":{"amountInPaise":2500050,"currency":"INR"},
             "beneficiary":{"name":"Aarav","account":{"number":"123456789012","ifsc":"ZENI0001234"}},"purposeCode":"LNDSB"}
            """;
    private static final String ORBIT = """
            {"txn":{"ref":"p3:transfer","amt":"25000.00","ccy":"INR"},"cdtr":{"nm":"AARAV SHARMA","acct":"123456789012","ifsc":"ORBI0001234"},"purp":"LOAN"}
            """;
    private static final String ORBIT_PATH = "/banks/orbit/api/credit";

    @Autowired MockMvc mvc;
    @Autowired ChaosEngine chaos;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(post("/chaos/reset"));
        chaos.targets().forEach(t -> chaos.configure(t, new ChaosConfig(0, 0, 0)));
    }

    // ---- idempotency and chaos (Nova) ----

    @Test
    void sameIdempotencyKeyMovesMoneyOnce() throws Exception {
        String first = nova("key-1", NOVA).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();
        String second = nova("key-1", NOVA).andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        var stats = chaos.stats().get("NOVA");
        assertThat(stats.uniqueOperations()).isEqualTo(1);
        assertThat(stats.idempotentReplays()).isEqualTo(1);
        assertThat(stats.amountMoved()).isEqualTo(25000.00);
    }

    @Test
    void lostResponseStillCompletesTheWorkOnce() throws Exception {
        chaos.configure("NOVA", new ChaosConfig(0, 1.0, 0));
        nova("key-2", NOVA).andExpect(status().isGatewayTimeout());
        nova("key-2", NOVA).andExpect(status().isGatewayTimeout());

        chaos.configure("NOVA", new ChaosConfig(0, 0, 0));
        nova("key-2", NOVA).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(chaos.stats().get("NOVA").uniqueOperations()).isEqualTo(1);
    }

    @Test
    void injectedFailureDoesNoWork() throws Exception {
        chaos.configure("NOVA", new ChaosConfig(1.0, 0, 0));
        nova("key-3", NOVA).andExpect(status().isServiceUnavailable());
        assertThat(chaos.stats().get("NOVA").uniqueOperations()).isZero();
    }

    @Test
    void novaRejectsInvalidAccountAndWrongKey() throws Exception {
        nova("key-4", NOVA.replace("123456789012", "123456780000"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mvc.perform(post("/banks/nova/v1/payouts").header("X-Nova-Key", "wrong").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(NOVA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void novaRejectsWrongPayloadShape() throws Exception {
        nova("key-5", ZENITH).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }

    // ---- OAuth2 (Zenith) ----

    @Test
    void zenithIssuesTokensAndAcceptsThem() throws Exception {
        String token = zenithToken("zenith-demo-secret");
        zenith(token, ZENITH).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROCESSED"))
                .andExpect(jsonPath("$.data.transferId").exists());
        assertThat(chaos.stats().get("ZENITH").amountMoved()).isEqualTo(25000.50);
    }

    @Test
    void zenithRejectsBadClientSecretAndRevokedTokens() throws Exception {
        mvc.perform(post("/banks/zenith/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&client_id=payrelay&client_secret=wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));

        String token = zenithToken("zenith-demo-secret");
        mvc.perform(post("/chaos/banks/zenith/revoke-tokens")).andExpect(jsonPath("$.revoked").value(1));
        zenith(token, ZENITH).andExpect(status().isUnauthorized());
    }

    @Test
    void zenithReportsRejectionInsideA200() throws Exception {
        zenith(zenithToken("zenith-demo-secret"), ZENITH.replace("123456789012", "123456780000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REJECTED"))
                .andExpect(jsonPath("$.data.reason").exists());
        // Counted as a rejection even though the HTTP status was 200, so reconciliation stays exact.
        assertThat(chaos.stats().get("ZENITH").businessRejections()).isEqualTo(1);
        assertThat(chaos.stats().get("ZENITH").amountMoved()).isZero();
    }

    // ---- HMAC (Orbit) ----

    @Test
    void orbitAcceptsAValidSignature() throws Exception {
        orbit(ORBIT, now(), sign(now(), ORBIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.code").value("00"))
                .andExpect(jsonPath("$.result.bankRef").exists());
    }

    @Test
    void orbitRejectsATamperedBody() throws Exception {
        String ts = now();
        String signature = sign(ts, ORBIT);
        orbit(ORBIT.replace("25000.00", "95000.00"), ts, signature)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result.code").value("91"));
    }

    @Test
    void orbitRejectsAnOldTimestamp() throws Exception {
        String old = String.valueOf(Instant.now().getEpochSecond() - 3600);
        orbit(ORBIT, old, sign(old, ORBIT)).andExpect(status().isUnauthorized());
    }

    @Test
    void orbitEnforcesItsNameLengthLimit() throws Exception {
        String longName = ORBIT.replace("AARAV SHARMA", "A".repeat(36));
        orbit(longName, now(), sign(now(), longName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.code").value("30"));
    }

    @Test
    void orbitReportsInvalidAccountAsResultCode14() throws Exception {
        String body = ORBIT.replace("123456789012", "123456780000");
        orbit(body, now(), sign(now(), body)).andExpect(status().isOk()).andExpect(jsonPath("$.result.code").value("14"));
    }

    // ---- API key with prefix (Kestrel) ----

    @Test
    void kestrelUsesItsOwnAuthorizationScheme() throws Exception {
        String body = """
                {"amountPaise":2500000,"beneficiaryName":"Aarav","beneficiaryAccount":"123456789012","beneficiaryIfsc":"KEST0001234"}
                """;
        mvc.perform(post("/banks/kestrel/disbursements").header("Authorization", "ApiKey kestrel-demo-key")
                        .header("X-Request-Id", "k-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/banks/kestrel/disbursements").header("Authorization", "Bearer kestrel-demo-key")
                        .header("X-Request-Id", "k-2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    // ---- KYC ----

    @Test
    void kycRejectsMagicPanAndRequiresApiKey() throws Exception {
        mvc.perform(post("/kyc/verify").header("X-Api-Key", "sim-secret-key").header("Idempotency-Key", "k5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"C1\",\"pan\":\"ZZZDE1234F\",\"name\":\"X\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(post("/kyc/verify").header("Idempotency-Key", "k6").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private ResultActions nova(String key, String body) throws Exception {
        return mvc.perform(post("/banks/nova/v1/payouts").header("X-Nova-Key", "nova-demo-key")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String zenithToken(String secret) throws Exception {
        String json = mvc.perform(post("/banks/zenith/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("grant_type=client_credentials&client_id=payrelay&client_secret=" + secret + "&scope=transfers:write"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions zenith(String token, String body) throws Exception {
        return mvc.perform(post("/banks/zenith/v2/transfers").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions orbit(String body, String timestamp, String signature) throws Exception {
        return mvc.perform(post(ORBIT_PATH).header("X-Orbit-Key-Id", "payrelay-01").header("X-Orbit-Timestamp", timestamp)
                .header("X-Orbit-Signature", signature).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private static String sign(String ts, String body) throws Exception {
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("orbit-demo-hmac-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((ts + "\nPOST\n" + ORBIT_PATH + "\n" + bodyHash).getBytes(StandardCharsets.UTF_8)));
    }
}
