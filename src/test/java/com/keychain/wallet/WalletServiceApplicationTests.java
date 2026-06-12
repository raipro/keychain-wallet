package com.keychain.wallet;

import com.keychain.wallet.repository.WalletRepository;
import com.keychain.wallet.repository.WalletTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot smoke test: context starts, schema.sql applies, and Hibernate's
 * ddl-auto=validate confirms the entities match the hand-written schema.
 */
@SpringBootTest
class WalletServiceApplicationTests {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private WalletTransactionRepository walletTransactionRepository;

    @Test
    void contextLoadsAndSchemaValidates() {
        assertThat(walletRepository.count()).isZero();
        assertThat(walletTransactionRepository.count()).isZero();
    }
}
