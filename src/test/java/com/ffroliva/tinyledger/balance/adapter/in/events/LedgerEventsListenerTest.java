package com.ffroliva.tinyledger.balance.adapter.in.events;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.*;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spec §4.3/§4.4: proves the write side's published events actually reach the {@code balance}
 * projection through {@link LedgerEventsListener} in the standalone profile.
 */
@SpringBootTest
@ActiveProfiles("standalone")
class LedgerEventsListenerTest {
    private static final Currency GBP = Currency.getInstance("GBP");

    @Autowired
    private OpenAccountUseCase openAccount;

    @Autowired
    private RecordMovementUseCase recordMovement;

    @Autowired
    private QueryBalanceUseCase queryBalance;

    @Test
    void writeSideEventsReachTheProjection() {
        AccountId account =
                openAccount.open(new OpenAccount("local", "ACC-001", GBP)).accountId();
        recordMovement.deposit(new Deposit("local", account, UUID.randomUUID(), new Money(GBP, 10_000), "rent"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(queryBalance.balance(account))
                .hasValueSatisfying(view -> assertThat(view.amount()).isEqualTo(new Money(GBP, 10_000))));
    }
}
