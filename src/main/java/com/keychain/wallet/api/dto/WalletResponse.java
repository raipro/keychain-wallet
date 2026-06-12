package com.keychain.wallet.api.dto;

import com.keychain.wallet.domain.Wallet;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID walletId,
        String customerId,
        long balancePaise,
        String currency,
        Instant createdAt) {

    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getCustomerId(),
                wallet.getBalancePaise(),
                wallet.getCurrency(),
                wallet.getCreatedAt());
    }
}
