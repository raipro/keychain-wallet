package com.keychain.wallet.service;

import com.keychain.wallet.domain.TransactionType;
import com.keychain.wallet.domain.Wallet;
import com.keychain.wallet.domain.WalletTransaction;
import com.keychain.wallet.repository.WalletRepository;
import com.keychain.wallet.repository.WalletTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;
    private final long deductAmountPaise;

    public WalletService(WalletRepository walletRepository,
                         WalletTransactionRepository transactionRepository,
                         PlatformTransactionManager transactionManager,
                         @Value("${wallet.deduct-amount-paise:10000}") long deductAmountPaise) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.deductAmountPaise = deductAmountPaise;
    }

    /** A completed money movement, flagging whether it was an idempotent replay. */
    public record Outcome(WalletTransaction transaction, boolean replayed) {
    }

    public Wallet createWallet(String customerId) {
        return walletRepository.save(Wallet.create(customerId));
    }

    public Wallet getWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    public Outcome topup(UUID walletId, long amountPaise, String idempotencyKey) {
        String requestHash = idempotencyKey == null
                ? null
                : RequestHasher.hash("TOPUP", walletId, amountPaise, idempotencyKey);
        return executeMovement(walletId, TransactionType.TOPUP, amountPaise, idempotencyKey, requestHash);
    }

    public Outcome deduct(UUID walletId, String orderId) {
        String requestHash = RequestHasher.hash("DEDUCT", walletId, deductAmountPaise, orderId);
        return executeMovement(walletId, TransactionType.DEDUCT, deductAmountPaise, orderId, requestHash);
    }

    public Page<WalletTransaction> getTransactions(UUID walletId, Pageable pageable) {
        getWallet(walletId);
        return transactionRepository.findByWalletIdOrderByCreatedAtDesc(walletId, pageable);
    }

    /**
     * Applies a money movement atomically: balance update and ledger insert commit or
     * roll back together.
     *
     * <p>Idempotency has two layers. The pre-check inside the transaction catches the
     * common retry (key already recorded → replay the original result, or 409 if the
     * payload differs). Two truly concurrent requests with the same key can both pass
     * the pre-check; then the unique constraint on (wallet_id, idempotency_key) lets
     * exactly one commit. The loser's whole transaction — including its balance
     * update — rolls back, and we return the winner's result. The catch sits outside
     * the TransactionTemplate because the failed transaction must be fully rolled
     * back before we can read the winner's committed row.
     */
    private Outcome executeMovement(UUID walletId,
                                    TransactionType type,
                                    long amountPaise,
                                    String idempotencyKey,
                                    String requestHash) {
        try {
            return transactionTemplate.execute(status -> {
                if (!walletRepository.existsById(walletId)) {
                    throw new WalletNotFoundException(walletId);
                }

                if (idempotencyKey != null) {
                    var existing = transactionRepository.findByWalletIdAndIdempotencyKey(walletId, idempotencyKey);
                    if (existing.isPresent()) {
                        return replay(existing.get(), requestHash, idempotencyKey);
                    }
                }

                Instant now = Instant.now();
                int updated = type == TransactionType.DEDUCT
                        ? walletRepository.deductBalance(walletId, amountPaise, now)
                        : walletRepository.creditBalance(walletId, amountPaise, now);
                if (updated == 0) {
                    throw new InsufficientBalanceException(walletId, amountPaise);
                }

                long balanceAfter = getWallet(walletId).getBalancePaise();
                WalletTransaction transaction = transactionRepository.save(new WalletTransaction(
                        walletId, type, amountPaise, balanceAfter, idempotencyKey, requestHash));
                return new Outcome(transaction, false);
            });
        } catch (DataIntegrityViolationException e) {
            WalletTransaction winner = transactionRepository
                    .findByWalletIdAndIdempotencyKey(walletId, idempotencyKey)
                    .orElseThrow(() -> e);
            return replay(winner, requestHash, idempotencyKey);
        }
    }

    private Outcome replay(WalletTransaction existing, String requestHash, String idempotencyKey) {
        if (!java.util.Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return new Outcome(existing, true);
    }
}
