package com.keychain.wallet.service;

import com.keychain.wallet.domain.TransactionType;
import com.keychain.wallet.domain.Wallet;
import com.keychain.wallet.domain.WalletTransaction;
import com.keychain.wallet.repository.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Business-rule and concurrency tests for the wallet service. The concurrency tests
 * are the heart of the suite: they exercise the races that a read-then-write
 * implementation would lose.
 */
@SpringBootTest
class WalletServiceTest {

    private static final long DEDUCT = 10_000L; // matches wallet.deduct-amount-paise

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletTransactionRepository transactionRepository;

    private UUID newWallet() {
        return walletService.createWallet("cust-" + UUID.randomUUID()).getId();
    }

    private UUID fundedWallet(long paise) {
        UUID id = newWallet();
        walletService.topup(id, paise, null);
        return id;
    }

    private long balanceOf(UUID walletId) {
        return walletService.getWallet(walletId).getBalancePaise();
    }

    // --- basic rules -------------------------------------------------------

    @Test
    void topupIncreasesBalanceAndWritesLedgerEntry() {
        UUID id = newWallet();

        var outcome = walletService.topup(id, 25_000L, null);

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.transaction().getType()).isEqualTo(TransactionType.TOPUP);
        assertThat(outcome.transaction().getBalanceAfterPaise()).isEqualTo(25_000L);
        assertThat(balanceOf(id)).isEqualTo(25_000L);
    }

    @Test
    void deductReducesBalanceByConfiguredAmount() {
        UUID id = fundedWallet(25_000L);

        var outcome = walletService.deduct(id, "ORD-1");

        assertThat(outcome.transaction().getBalanceAfterPaise()).isEqualTo(15_000L);
        assertThat(balanceOf(id)).isEqualTo(15_000L);
    }

    @Test
    void deductFailsWhenBalanceInsufficientAndLeavesNoTrace() {
        UUID id = fundedWallet(DEDUCT - 1);

        assertThatThrownBy(() -> walletService.deduct(id, "ORD-1"))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(balanceOf(id)).isEqualTo(DEDUCT - 1);
        assertThat(deductEntries(id)).isEmpty();
    }

    @Test
    void deductOnUnknownWalletFails() {
        assertThatThrownBy(() -> walletService.deduct(UUID.randomUUID(), "ORD-1"))
                .isInstanceOf(WalletNotFoundException.class);
    }

    // --- idempotency -------------------------------------------------------

    @Test
    void deductRetryWithSameOrderIdReplaysOriginalResult() {
        UUID id = fundedWallet(50_000L);

        var first = walletService.deduct(id, "ORD-1");
        var retry = walletService.deduct(id, "ORD-1");

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(retry.transaction().getBalanceAfterPaise())
                .isEqualTo(first.transaction().getBalanceAfterPaise());
        assertThat(balanceOf(id)).isEqualTo(40_000L); // deducted exactly once
    }

    @Test
    void sameOrderIdOnDifferentWalletsDeductsBoth() {
        UUID a = fundedWallet(20_000L);
        UUID b = fundedWallet(20_000L);

        walletService.deduct(a, "ORD-1");
        walletService.deduct(b, "ORD-1");

        assertThat(balanceOf(a)).isEqualTo(10_000L);
        assertThat(balanceOf(b)).isEqualTo(10_000L);
    }

    @Test
    void topupKeyReuseWithDifferentAmountIsRejected() {
        UUID id = newWallet();
        walletService.topup(id, 10_000L, "KEY-1");

        assertThatThrownBy(() -> walletService.topup(id, 20_000L, "KEY-1"))
                .isInstanceOf(IdempotencyConflictException.class);

        assertThat(balanceOf(id)).isEqualTo(10_000L);
    }

    // --- concurrency: the hard cases ---------------------------------------

    @Test
    void concurrentDistinctDeductsNeverOverdraw() throws Exception {
        // Balance covers exactly 5 deductions; 20 distinct orders race for them.
        UUID id = fundedWallet(5 * DEDUCT);
        int attempts = 20;

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        runConcurrently(attempts, i -> {
            try {
                walletService.deduct(id, "ORD-" + i);
                succeeded.incrementAndGet();
            } catch (InsufficientBalanceException e) {
                insufficient.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(5);
        assertThat(insufficient.get()).isEqualTo(15);
        assertThat(balanceOf(id)).isZero();
        assertThat(deductEntries(id)).hasSize(5);
        assertLedgerSumMatchesBalance(id);
    }

    @Test
    void concurrentRetriesOfSameOrderDeductExactlyOnce() throws Exception {
        // 10 simultaneous requests for the SAME order: at most one may move money.
        UUID id = fundedWallet(100_000L);
        int attempts = 10;

        var transactionIds = ConcurrentHashMap.<UUID>newKeySet();
        runConcurrently(attempts, i ->
                transactionIds.add(walletService.deduct(id, "ORD-RACE").transaction().getId()));

        assertThat(transactionIds).hasSize(1); // everyone saw the same transaction
        assertThat(balanceOf(id)).isEqualTo(90_000L); // deducted exactly once
        assertThat(deductEntries(id)).hasSize(1);
        assertLedgerSumMatchesBalance(id);
    }

    @Test
    void ledgerSumAlwaysMatchesStoredBalance() {
        UUID id = newWallet();
        walletService.topup(id, 35_000L, "T-1");
        walletService.deduct(id, "ORD-1");
        walletService.deduct(id, "ORD-1"); // replay, must not affect the sum
        walletService.deduct(id, "ORD-2");
        walletService.topup(id, 5_000L, null);

        assertThat(balanceOf(id)).isEqualTo(20_000L);
        assertLedgerSumMatchesBalance(id);
    }

    // --- helpers ------------------------------------------------------------

    private interface IndexedAction {
        void run(int index) throws Exception;
    }

    /** Runs the action on N threads, released simultaneously by a start latch. */
    private void runConcurrently(int threads, IndexedAction action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int index = i;
                futures.add(executor.submit((Callable<Void>) () -> {
                    start.await();
                    action.run(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(); // propagate unexpected exceptions
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private List<WalletTransaction> deductEntries(UUID walletId) {
        return allEntries(walletId).stream()
                .filter(t -> t.getType() == TransactionType.DEDUCT)
                .toList();
    }

    private List<WalletTransaction> allEntries(UUID walletId) {
        return transactionRepository
                .findByWalletIdOrderByCreatedAtDesc(walletId, PageRequest.of(0, 1000))
                .getContent();
    }

    /** The auditability invariant: signed ledger sum equals the stored balance. */
    private void assertLedgerSumMatchesBalance(UUID walletId) {
        long sum = allEntries(walletId).stream()
                .mapToLong(t -> t.getType() == TransactionType.TOPUP
                        ? t.getAmountPaise()
                        : -t.getAmountPaise())
                .sum();
        assertThat(sum).isEqualTo(balanceOf(walletId));
    }
}
