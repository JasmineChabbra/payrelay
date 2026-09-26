package com.payrelay.api.bank.mapping;

import com.payrelay.api.domain.Payment;
import com.payrelay.api.step.BankTransferStepHandler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** The payment fields a mapping rule can read from. */
public final class MappingSources {

    public record Source(String name, String description, Function<Payment, Object> extractor) {
    }

    public static final List<Source> ALL = List.of(
            new Source("paymentId", "PayRelay payment ID", p -> p.getId().toString()),
            new Source("transferKey", "Idempotency key for the transfer; identical on every retry", BankTransferStepHandler::transferKey),
            new Source("correlationId", "Correlation ID for tracing", Payment::getCorrelationId),
            new Source("customerId", "Customer ID", Payment::getCustomerId),
            new Source("amount", "Amount in rupees, e.g. 25000.00", Payment::getAmount),
            new Source("currency", "Currency code, e.g. INR", Payment::getCurrency),
            new Source("beneficiaryName", "Beneficiary's name", Payment::getBeneficiaryName),
            new Source("accountNumber", "Beneficiary account number", Payment::getAccountNumber),
            new Source("ifsc", "Beneficiary IFSC", Payment::getIfsc),
            new Source("purpose", "Purpose text; may be empty", Payment::getPurpose),
            new Source("bankCode", "Bank code, e.g. NOVA", Payment::getBankCode),
            new Source("createdAt", "When PayRelay received the payment (ISO-8601)", p -> p.getCreatedAt().toString()));

    private static final Set<String> NAMES = ALL.stream().map(Source::name).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private MappingSources() {
    }

    public static boolean isKnown(String name) {
        return NAMES.contains(name);
    }

    public static Map<String, Object> from(Payment payment) {
        Map<String, Object> values = new LinkedHashMap<>();
        ALL.forEach(s -> values.put(s.name(), s.extractor().apply(payment)));
        return values;
    }

    /** A realistic payment used to preview a mapping before any real payment exists. */
    public static Payment sample(String bankCode) {
        String prefix = bankCode.length() >= 4 ? bankCode.substring(0, 4) : bankCode;
        return Payment.receive("sample-key", "sample", "sample-correlation-id", "CUST123456", "ABCDE1234F",
                new BigDecimal("125000.50"), "INR", "  Ananya Venkataraman Subramaniam Iyer ", "123456789012",
                prefix.toLowerCase(Locale.ROOT) + "0001234", bankCode, "Loan disbursement",
                Instant.parse("2026-01-15T10:30:00Z"));
    }
}
