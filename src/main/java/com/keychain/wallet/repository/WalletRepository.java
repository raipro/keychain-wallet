package com.keychain.wallet.repository;

import com.keychain.wallet.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * Atomic conditional deduction — the heart of the balance constraint. The
     * {@code balancePaise >= :amount} predicate and the subtraction happen in a single
     * statement, so no interleaving of concurrent requests can drive the balance
     * negative. Returns 0 when the balance is insufficient.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Wallet w
               SET w.balancePaise = w.balancePaise - :amount,
                   w.version = w.version + 1,
                   w.updatedAt = :now
             WHERE w.id = :walletId
               AND w.balancePaise >= :amount
            """)
    int deductBalance(@Param("walletId") UUID walletId, @Param("amount") long amount, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Wallet w
               SET w.balancePaise = w.balancePaise + :amount,
                   w.version = w.version + 1,
                   w.updatedAt = :now
             WHERE w.id = :walletId
            """)
    int creditBalance(@Param("walletId") UUID walletId, @Param("amount") long amount, @Param("now") Instant now);
}
