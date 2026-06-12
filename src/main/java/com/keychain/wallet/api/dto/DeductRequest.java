package com.keychain.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeductRequest(
        @NotBlank @Size(max = 128) String orderId) {
}
