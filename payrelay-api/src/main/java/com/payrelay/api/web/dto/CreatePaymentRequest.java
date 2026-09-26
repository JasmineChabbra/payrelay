package com.payrelay.api.web.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotBlank @Size(max = 50) String customerId,
        @NotBlank @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "must be a valid PAN, e.g. ABCDE1234F") String customerPan,
        @NotNull @DecimalMin(value = "1.00") @DecimalMax(value = "10000000.00") @Digits(integer = 16, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "INR", message = "only INR is supported") String currency,
        @NotBlank @Size(max = 120) String beneficiaryName,
        @NotBlank @Pattern(regexp = "^[0-9]{9,18}$", message = "must be 9 to 18 digits") String accountNumber,
        @NotBlank @Pattern(regexp = "^[A-Z]{4}0[A-Z0-9]{6}$", message = "must be a valid IFSC, e.g. NOVA0001234") String ifsc,
        @NotBlank String bankCode,
        @Size(max = 200) String purpose) {
}
