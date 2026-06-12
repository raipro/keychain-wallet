package com.keychain.wallet.api.dto;

import com.keychain.wallet.domain.WalletTransaction;

import java.time.Instant;
import java.util.UUID;

/** One ledger entry as exposed on the transaction history endpoint. */
public record TransactionResponse(
        UUID transactionId,
        String type,
        long amountPaise,
        long balanceAfterPaise,
        String idempotencyKey,
        Instant createdAt) {

    public static TransactionResponse from(WalletTransaction txn) {
        return new TransactionResponse(
                txn.getId(),
                txn.getType().name(),
                txn.getAmountPaise(),
                txn.getBalanceAfterPaise(),
                txn.getIdempotencyKey(),
                txn.getCreatedAt());
    }
}
