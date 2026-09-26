package com.payrelay.api.service;

import com.payrelay.api.domain.PaymentStatus;
import com.payrelay.api.repository.PaymentRepository;
import com.payrelay.api.web.dto.CreatePaymentRequest;
import com.payrelay.api.worker.PaymentWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "payrelay.worker.poll-interval-ms=3600000")
@ActiveProfiles("dev")
class PaymentIdempotencyTest {

    @MockitoBean
    PaymentWorker worker; // creation only; processing isn't under test here

    @Autowired PaymentService service;
    @Autowired PaymentRepository payments;

    @Test
    void sameKeyAndBodyReturnsTheOriginalPayment() {
        String key = "key-" + UUID.randomUUID();
        var first = service.create(key, request("25000.00"), "cid-1");
        var second = service.create(key, request("25000.00"), "cid-2");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.payment().getId()).isEqualTo(first.payment().getId());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String key = "key-" + UUID.randomUUID();
        service.create(key, request("25000.00"), "cid-1");

        assertThatThrownBy(() -> service.create(key, request("99999.00"), "cid-2"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void equivalentAmountsHashTheSame() {
        assertThat(PaymentService.hash(request("25000.00"))).isEqualTo(PaymentService.hash(request("25000")));
    }

    @Test
    void concurrentDuplicatesCreateExactlyOnePayment() throws Exception {
        String key = "key-" + UUID.randomUUID();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return service.create(key, request("25000.00"), "cid").payment().getId();
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT); // "in progress" is the only acceptable error
                    return null;
                }
            }));
        }
        start.countDown();

        List<UUID> ids = new ArrayList<>();
        for (Future<UUID> f : results) {
            UUID id = f.get(10, TimeUnit.SECONDS);
            if (id != null) {
                ids.add(id);
            }
        }
        pool.shutdown();

        assertThat(ids).isNotEmpty();
        assertThat(ids).allMatch(id -> id.equals(ids.getFirst())); // everyone who succeeded got the same payment
        assertThat(payments.findByIdempotencyKey(key)).isPresent();
        assertThat(payments.findAll().stream().filter(p -> p.getIdempotencyKey().equals(key))).hasSize(1);
    }

    @Test
    void unsupportedBankIsRejected() {
        var req = new CreatePaymentRequest("CUST1", "ABCDE1234F", new BigDecimal("100.00"), "INR", "A B",
                "123456789012", "NOVA0001234", "UNKNOWN", null);
        assertThatThrownBy(() -> service.create("k-" + UUID.randomUUID(), req, "cid"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void newPaymentStartsAsReceivedAtFirstStep() {
        var result = service.create("key-" + UUID.randomUUID(), request("500.00"), "cid");
        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.RECEIVED);
    }

    private static CreatePaymentRequest request(String amount) {
        return new CreatePaymentRequest("CUST1", "ABCDE1234F", new BigDecimal(amount), "INR", "Aarav Sharma",
                "123456789012", "NOVA0001234", "NOVA", "Loan disbursement");
    }
}
