package com.payrelay.api.web;

import com.payrelay.api.bank.BankConfigService;
import com.payrelay.api.service.PaymentService;
import com.payrelay.api.web.dto.CreatePaymentRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates realistic traffic for demos and load testing. It also re-sends some requests with the
 * same Idempotency-Key, the way a real client does after a network blip, to show that replays
 * never create a second payment.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final List<String> NAMES = List.of("Aarav Sharma", "Diya Patel", "Kabir Mehta", "Ananya Iyer",
            "Vihaan Gupta", "Ishita Rao", "Arjun Nair", "Saanvi Reddy", "Reyansh Joshi", "Myra Kapoor");

    private final PaymentService service;
    private final BankConfigService banks;

    public DemoController(PaymentService service, BankConfigService banks) {
        this.service = service;
        this.banks = banks;
    }

    public record LoadRequest(int count, String bankCode, Double duplicateRate, Double rejectionRate) {
    }

    public record LoadResult(int created, int replayed) {
    }

    @PostMapping("/load")
    public LoadResult load(@RequestBody LoadRequest req) {
        int count = Math.min(Math.max(req.count(), 1), 500);
        double dupRate = req.duplicateRate() == null ? 0.1 : req.duplicateRate();
        double rejectRate = req.rejectionRate() == null ? 0.05 : req.rejectionRate();
        var rnd = ThreadLocalRandom.current();
        List<String> codes = banks.codes();

        int created = 0;
        int replayed = 0;
        for (int i = 0; i < count; i++) {
            String bank = req.bankCode() != null && codes.contains(req.bankCode())
                    ? req.bankCode()
                    : codes.get(rnd.nextInt(codes.size()));
            CreatePaymentRequest payment = randomPayment(bank, rnd.nextDouble() < rejectRate);
            String key = "demo-" + UUID.randomUUID();
            String cid = UUID.randomUUID().toString();

            if (!service.create(key, payment, cid).replayed()) {
                created++;
            }
            if (rnd.nextDouble() < dupRate && service.create(key, payment, cid).replayed()) {
                replayed++;
            }
        }
        return new LoadResult(created, replayed);
    }

    /** Clears all payments. Demo only: pair it with the simulator's /chaos/reset so both sides start from zero. */
    @PostMapping("/reset")
    public void reset() {
        service.deleteAll();
    }

    private static CreatePaymentRequest randomPayment(String bank, boolean forceRejection) {
        var rnd = ThreadLocalRandom.current();
        String pan = letters(5) + String.format("%04d", rnd.nextInt(10_000)) + letters(1);
        String account = String.valueOf(rnd.nextLong(100_000_000_000L, 999_999_999_999L));
        if (forceRejection) {
            // The simulator's documented "magic values" for business rejections.
            if (rnd.nextBoolean()) {
                pan = "ZZZ" + pan.substring(3);           // KYC rejects
            } else {
                account = account.substring(0, 8) + "0000"; // bank rejects: invalid account
            }
        }
        BigDecimal amount = BigDecimal.valueOf(rnd.nextLong(5_000, 800_000)).setScale(2, RoundingMode.UNNECESSARY);
        return new CreatePaymentRequest(
                "CUST" + rnd.nextInt(100_000, 999_999), pan, amount, "INR",
                NAMES.get(rnd.nextInt(NAMES.size())), account,
                bank.substring(0, 4) + "0" + String.format("%06d", rnd.nextInt(1_000_000)), bank, "Loan disbursement");
    }

    private static String letters(int n) {
        var rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('A' + rnd.nextInt(25))); // A-Y, so random PANs never start with the ZZZ magic value
        }
        return sb.toString();
    }
}
