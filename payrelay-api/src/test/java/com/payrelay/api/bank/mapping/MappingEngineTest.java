package com.payrelay.api.bank.mapping;

import com.payrelay.api.bank.BankSpec;
import com.payrelay.api.bank.BankSpec.FieldRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MappingEngineTest {

    private final MappingEngine engine = new MappingEngine();

    private final Map<String, Object> payment = new HashMap<>(Map.of(
            "transferKey", "p1:transfer",
            "amount", new BigDecimal("125000.50"),
            "currency", "INR",
            "beneficiaryName", "  Ananya Venkataraman Subramaniam Iyer ",
            "accountNumber", "123456789012",
            "ifsc", "zeni0001234",
            "purpose", "Loan disbursement"));

    private static FieldRule rule(String target, String source, String... transforms) {
        return new FieldRule(target, source, null, List.of(transforms), null, null);
    }

    private static BankSpec spec(List<FieldRule> headers, List<FieldRule> body, Map<String, Map<String, String>> lookups) {
        return new BankSpec(new BankSpec.Endpoint("/x"), null, new BankSpec.Request(headers, body), null, lookups);
    }

    @Test
    void buildsNestedBodiesWithTransforms() {
        var mapped = engine.map(spec(
                List.of(rule("X-Request-Id", "transferKey")),
                List.of(
                        rule("payment.amountInPaise", "amount", "PAISE"),
                        rule("payment.amt", "amount", "DECIMAL_STRING"),
                        rule("beneficiary.name", "beneficiaryName", "TRIM", "UPPER", "TRUNCATE:20"),
                        rule("beneficiary.account.ifsc", "ifsc", "UPPER"),
                        rule("purposeCode", "purpose", "LOOKUP:purposes")),
                Map.of("purposes", Map.of("Loan disbursement", "LNDSB"))), payment);

        assertThat(mapped.headers()).containsEntry("X-Request-Id", "p1:transfer");
        assertThat(mapped.body()).isEqualTo(Map.of(
                "payment", Map.of("amountInPaise", 12500050L, "amt", "125000.50"),
                "beneficiary", Map.of("name", "ANANYA VENKATARAMAN ", "account", Map.of("ifsc", "ZENI0001234")),
                "purposeCode", "LNDSB"));
    }

    @Test
    void lookupFallsBackToStar() {
        payment.put("purpose", "Something new");
        var mapped = engine.map(spec(List.of(), List.of(rule("p", "purpose", "LOOKUP:t")),
                Map.of("t", Map.of("Loan disbursement", "L", "*", "OTHER"))), payment);
        assertThat(mapped.body()).containsEntry("p", "OTHER");
    }

    @Test
    void constantsAndDefaults() {
        payment.remove("purpose");
        var mapped = engine.map(spec(List.of(), List.of(
                new FieldRule("channel", null, "API", null, null, null),
                new FieldRule("remarks", "purpose", null, null, null, "Disbursement"),
                new FieldRule("optional", "purpose", null, null, false, null)), Map.of()), payment);
        assertThat(mapped.body()).isEqualTo(Map.of("channel", "API", "remarks", "Disbursement"));
    }

    @Test
    void collectsEveryProblemInOnePass() {
        payment.remove("purpose");
        assertThatThrownBy(() -> engine.map(spec(List.of(), List.of(
                rule("a", "purpose"),
                rule("b", "beneficiaryName", "PAISE"),
                rule("c", "currency", "LOOKUP:missing")), Map.of()), payment))
                .isInstanceOf(MappingException.class)
                .satisfies(e -> assertThat(((MappingException) e).problems()).hasSize(3));
    }

    @Test
    void refusesFractionsOfAPaisa() {
        payment.put("amount", new BigDecimal("10.005"));
        assertThatThrownBy(() -> engine.map(spec(List.of(), List.of(rule("a", "amount", "PAISE")), Map.of()), payment))
                .hasMessageContaining("fractions of a paisa");
    }

    @Test
    void detectsPathConflicts() {
        assertThat(MappingEngine.put(new HashMap<>(Map.of("a", "x")), "a.b", 1)).contains("conflicts");
    }

    @Test
    void readsResponsePaths() {
        Map<String, Object> json = Map.of("data", Map.of("state", "PROCESSED", "transferId", "Z1"));
        assertThat(JsonPaths.getString(json, "data.state")).isEqualTo("PROCESSED");
        assertThat(JsonPaths.getString(json, "data.missing.deeper")).isNull();
    }
}
