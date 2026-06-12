package com.keychain.wallet.service;

import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(UUID walletId, long requestedPaise) {
        super("Insufficient balance in wallet " + walletId + " for deduction of " + requestedPaise + " paise");
    }
}
