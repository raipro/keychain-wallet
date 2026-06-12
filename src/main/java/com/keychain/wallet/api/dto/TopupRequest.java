package com.keychain.wallet.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TopupRequest(
        @NotNull @Positive Long amountPaise,
        @Size(max = 128) String idempotencyKey) {
}
