package com.payrelay.simulator.bank;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The banking rules every simulated bank shares. Only the wire format differs per bank.
 * Magic values for demos: an account ending in 0000 doesn't exist, and the IFSC must start
 * with the bank's first four letters.
 */
final class BankCore {

    sealed interface Result permits Ok, Rejected {
    }

    record Ok(String reference, BigDecimal amountRupees) implements Result {
    }

    record Rejected(String reason) implements Result {
    }

    private BankCore() {
    }

    static Result transfer(String bankCode, String account, String ifsc, BigDecimal amountRupees) {
        if (amountRupees.signum() <= 0) {
            return new Rejected("Amount must be positive");
        }
        if (account.endsWith("0000")) {
            return new Rejected("Beneficiary account does not exist");
        }
        if (!ifsc.startsWith(bankCode.substring(0, Math.min(4, bankCode.length())))) {
            return new Rejected("IFSC " + ifsc + " does not belong to this bank");
        }
        return new Ok(bankCode + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), amountRupees);
    }
}
