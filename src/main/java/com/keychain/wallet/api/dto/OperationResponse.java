package com.keychain.wallet.api.dto;

import com.keychain.wallet.service.WalletService;

import java.time.Instant;
import java.util.UUID;

/** Response for a money movement (top-up or deduct). */
public record OperationResponse(
        UUID transactionId,
        UUID walletId,
        String type,
        long amountPaise,
        long balanceAfterPaise,
        boolean replayed,
        Instant createdAt) {

    public static OperationResponse from(WalletService.Outcome outcome) {
        var txn = outcome.transaction();
        return new OperationResponse(
                txn.getId(),
                txn.getWalletId(),
                txn.getType().name(),
                txn.getAmountPaise(),
                txn.getBalanceAfterPaise(),
                outcome.replayed(),
                txn.getCreatedAt());
    }
}
