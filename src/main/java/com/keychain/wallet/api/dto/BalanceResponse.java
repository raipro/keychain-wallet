package com.keychain.wallet.api.dto;

import com.keychain.wallet.domain.Wallet;

import java.util.UUID;

public record BalanceResponse(
        UUID walletId,
        long balancePaise,
        String currency) {

    public static BalanceResponse from(Wallet wallet) {
        return new BalanceResponse(wallet.getId(), wallet.getBalancePaise(), wallet.getCurrency());
    }
}
