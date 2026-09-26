package com.payrelay.api.bank;

import jakarta.persistence.*;

import java.time.Instant;

/** An immutable snapshot of a bank config at one version. */
@Entity
@Table(name = "bank_config_history")
public class BankConfigHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_code", nullable = false, updatable = false)
    private String bankCode;

    @Column(nullable = false, updatable = false)
    private long version;

    @Column(name = "display_name", nullable = false, updatable = false)
    private String displayName;

    @Column(nullable = false, updatable = false)
    private boolean enabled;

    @Column(nullable = false, updatable = false)
    private String spec;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected BankConfigHistory() {
    }

    public static BankConfigHistory of(BankConfig c) {
        BankConfigHistory h = new BankConfigHistory();
        h.bankCode = c.getBankCode();
        h.version = c.getVersion();
        h.displayName = c.getDisplayName();
        h.enabled = c.isEnabled();
        h.spec = c.getSpec();
        h.changedAt = c.getUpdatedAt();
        return h;
    }

    public String getBankCode() { return bankCode; }
    public long getVersion() { return version; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public String getSpec() { return spec; }
    public Instant getChangedAt() { return changedAt; }
}
