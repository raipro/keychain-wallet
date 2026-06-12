package com.keychain.wallet.api.dto;

import com.keychain.wallet.domain.WalletTransaction;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

public record TransactionPageResponse(
        UUID walletId,
        List<TransactionResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static TransactionPageResponse from(UUID walletId, Page<WalletTransaction> result) {
        return new TransactionPageResponse(
                walletId,
                result.getContent().stream().map(TransactionResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }
}
