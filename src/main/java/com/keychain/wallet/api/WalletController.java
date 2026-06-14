package com.keychain.wallet.api;

import com.keychain.wallet.api.dto.BalanceResponse;
import com.keychain.wallet.api.dto.CreateWalletRequest;
import com.keychain.wallet.api.dto.DeductRequest;
import com.keychain.wallet.api.dto.OperationResponse;
import com.keychain.wallet.api.dto.TopupRequest;
import com.keychain.wallet.api.dto.TransactionPageResponse;
import com.keychain.wallet.api.dto.WalletResponse;
import com.keychain.wallet.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/{walletId}/transactions")
    public TransactionPageResponse getTransactions(@PathVariable UUID walletId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        // Paging params are clamped (page >= 0, size in [1, 100]) rather than rejected
        // with a 400 — deliberately lenient for a read endpoint, unlike the strict
        // bean validation on the money-moving requests.
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return TransactionPageResponse.from(walletId, walletService.getTransactions(walletId, pageable));
    }
}
