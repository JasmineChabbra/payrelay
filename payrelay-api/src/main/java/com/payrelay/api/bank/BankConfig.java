package com.payrelay.api.bank;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "bank_configs")
public class BankConfig {

    @Id
    @Column(name = "bank_code")
    private String bankCode;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled;

    /** The {@link BankSpec} as JSON. */
    @Column(nullable = false)
    private String spec;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BankConfig() {
    }

    public void update(String displayName, boolean enabled, String spec, Instant now) {
        this.displayName = displayName;
        this.enabled = enabled;
        this.spec = spec;
        this.updatedAt = now;
    }

    public String getBankCode() { return bankCode; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public String getSpec() { return spec; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
