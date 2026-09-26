package com.payrelay.api.web;

import com.payrelay.api.domain.PaymentStatus;
import com.payrelay.api.service.PaymentService;
import com.payrelay.api.web.dto.CreatePaymentRequest;
import com.payrelay.api.web.dto.PaymentDetail;
import com.payrelay.api.web.dto.PaymentSummary;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * Accepts a payment and returns immediately (202). Processing happens in the background.
     * Sending the same Idempotency-Key again returns the original payment with 200 and
     * {@code Idempotent-Replayed: true}.
     */
    @PostMapping
    public ResponseEntity<PaymentSummary> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @Valid @RequestBody CreatePaymentRequest request) {
        var result = service.create(key, request, CorrelationIdFilter.current());
        var body = PaymentSummary.from(result.payment());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/payments/" + body.id()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(body);
    }

    @GetMapping("/{id}")
    public PaymentDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public Page<PaymentSummary> search(@RequestParam(required = false) PaymentStatus status,
                                       @RequestParam(required = false) String bankCode,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        return service.search(status, bankCode, page, size);
    }

    /** Replays a dead-lettered payment from the step where it failed. */
    @PostMapping("/{id}/replay")
    public PaymentDetail replay(@PathVariable UUID id) {
        return service.replayDeadLetter(id);
    }
}
