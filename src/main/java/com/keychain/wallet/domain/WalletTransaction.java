package com.keychain.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single ledger entry. Append-only: rows are inserted, never updated or deleted,
 * so every field is immutable after construction.
 */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    @Id
    private UUID id;

    @Column(name = "wallet_id", nullable = false, updatable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private TransactionType type;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(name = "balance_after_paise", nullable = false, updatable = false)
    private long balanceAfterPaise;

    @Column(name = "idempotency_key", length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletTransaction() {
        // for JPA
    }

    public WalletTransaction(UUID walletId,
                             TransactionType type,
                             long amountPaise,
                             long balanceAfterPaise,
                             String idempotencyKey,
                             String requestHash) {
        this.id = UUID.randomUUID();
        this.walletId = walletId;
        this.type = type;
        this.amountPaise = amountPaise;
        this.balanceAfterPaise = balanceAfterPaise;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public TransactionType getType() {
        return type;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public long getBalanceAfterPaise() {
        return balanceAfterPaise;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
