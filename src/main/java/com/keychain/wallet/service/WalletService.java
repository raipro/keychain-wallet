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
import java.util.Objects;
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

                // The conditional UPDATE ran as raw SQL with clearAutomatically=true, so
                // the persistence context is empty and this is a fresh SELECT of the
                // authoritative committed balance. One extra round-trip per movement,
                // accepted in exchange for not tracking the pre-balance ourselves.
                // `now` is reused for the ledger row so the wallet's updated_at and the
                // ledger entry's created_at are the same instant.
                long balanceAfter = getWallet(walletId).getBalancePaise();
                WalletTransaction transaction = transactionRepository.save(new WalletTransaction(
                        walletId, type, amountPaise, balanceAfter, idempotencyKey, requestHash, now));
                return new Outcome(transaction, false);
            });
        } catch (DataIntegrityViolationException e) {
            // The only unique constraint that a concurrent request can hit here is
            // uq_wallet_idempotency, so finding the winner's row means this was an
            // idempotency race and we replay it. If a future schema adds another
            // constraint (or the FK fires), the lookup misses and we rethrow the raw
            // exception — add explicit handling here before introducing such a
            // constraint, or it will surface as a 500.
            WalletTransaction winner = transactionRepository
                    .findByWalletIdAndIdempotencyKey(walletId, idempotencyKey)
                    .orElseThrow(() -> e);
            return replay(winner, requestHash, idempotencyKey);
        }
    }

    // NOTE: the idempotency key space is shared across transaction types via the
    // (wallet_id, idempotency_key) constraint. A top-up and a deduct on the same
    // wallet using the same key string would collide. Because the request hash
    // encodes the type, the collision surfaces as a loud 409, never a wrong replay.
    // Callers are assumed to use distinct key namespaces (order ids vs top-up ids).
    private Outcome replay(WalletTransaction existing, String requestHash, String idempotencyKey) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return new Outcome(existing, true);
    }
}
