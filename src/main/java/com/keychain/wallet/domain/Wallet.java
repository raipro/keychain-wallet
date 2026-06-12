package com.keychain.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, length = 64, updatable = false)
    private String customerId;

    @Column(name = "balance_paise", nullable = false)
    private long balancePaise;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
        // for JPA
    }

    private Wallet(UUID id, String customerId, String currency) {
        this.id = id;
        this.customerId = customerId;
        this.currency = currency;
        this.balancePaise = 0L;
    }

    public static Wallet create(String customerId) {
        return new Wallet(UUID.randomUUID(), customerId, "INR");
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public long getBalancePaise() {
        return balancePaise;
    }

    public String getCurrency() {
        return currency;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
