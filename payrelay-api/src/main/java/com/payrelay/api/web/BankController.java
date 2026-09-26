package com.payrelay.api.web;

import com.payrelay.api.bank.BankConfigService;
import com.payrelay.api.bank.BankConfigService.*;
import com.payrelay.api.bank.BankSpec;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/banks")
public class BankController {

    private final BankConfigService banks;

    public BankController(BankConfigService banks) {
        this.banks = banks;
    }

    @GetMapping
    public List<BankSummary> list() {
        return banks.list();
    }

    /** Source fields, transforms and auth types, for building the mapping editor. */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return banks.meta();
    }

    @GetMapping("/{code}")
    public BankView get(@PathVariable String code) {
        return banks.get(code);
    }

    /** Saves a new version. Send the version you loaded; a stale version gets 409. */
    @PutMapping("/{code}")
    public BankView update(@PathVariable String code, @RequestBody UpdateRequest request) {
        return banks.update(code, request);
    }

    public record PreviewRequest(BankSpec spec, UUID paymentId) {
    }

    /** Renders the request a (possibly unsaved) spec would send, for a sample or a real payment. */
    @PostMapping("/{code}/preview")
    public Preview preview(@PathVariable String code, @RequestBody PreviewRequest request) {
        return banks.preview(code, request.spec(), request.paymentId());
    }

    @GetMapping("/{code}/history")
    public List<HistoryEntry> history(@PathVariable String code) {
        return banks.history(code);
    }
}
