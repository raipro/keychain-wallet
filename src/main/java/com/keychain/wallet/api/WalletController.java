package com.keychain.wallet.api;

import com.keychain.wallet.api.dto.BalanceResponse;
import com.keychain.wallet.api.dto.CreateWalletRequest;
import com.keychain.wallet.api.dto.DeductRequest;
import com.keychain.wallet.api.dto.OperationResponse;
import com.keychain.wallet.api.dto.TopupRequest;
import com.keychain.wallet.api.dto.WalletResponse;
import com.keychain.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        WalletResponse response = WalletResponse.from(walletService.createWallet(request.customerId()));
        return ResponseEntity
                .created(URI.create("/wallets/" + response.walletId()))
                .body(response);
    }

    @GetMapping("/{walletId}/balance")
    public BalanceResponse getBalance(@PathVariable UUID walletId) {
        return BalanceResponse.from(walletService.getWallet(walletId));
    }

    @PostMapping("/{walletId}/topup")
    public OperationResponse topup(@PathVariable UUID walletId,
                                   @Valid @RequestBody TopupRequest request) {
        return OperationResponse.from(
                walletService.topup(walletId, request.amountPaise(), request.idempotencyKey()));
    }

    @PostMapping("/{walletId}/deduct")
    public OperationResponse deduct(@PathVariable UUID walletId,
                                    @Valid @RequestBody DeductRequest request) {
        return OperationResponse.from(walletService.deduct(walletId, request.orderId()));
    }
}
