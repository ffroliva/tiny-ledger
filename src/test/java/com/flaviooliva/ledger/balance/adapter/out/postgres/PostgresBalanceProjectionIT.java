package com.flaviooliva.ledger.balance.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.flaviooliva.ledger.balance.application.port.in.*;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresBalanceProjectionIT extends AbstractIntegrationTest {

    @Autowired
    private BalanceProjectionPort projection;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Currency GBP = Currency.getInstance("GBP");

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE account_history, balance_projections CASCADE");
    }

    @Test
    void applyAccountOpenedCreatesBalanceAndAccount() {
        AccountId id = AccountId.random();
        Instant now = Instant.parse("2026-08-04T12:00:00Z");

        projection.apply(new AccountOpened(id, 1, now, "alice", "ACC-001", GBP));

        Optional<BalanceView> balance = projection.balance(id);
        assertThat(balance).isPresent();
        assertThat(balance.get().amount().minorUnits()).isEqualTo(0);
        assertThat(balance.get().streamVersion()).isEqualTo(1);

        Optional<AccountView> account = projection.account(id);
        assertThat(account).isPresent();
        assertThat(account.get().name()).isEqualTo("ACC-001");
        assertThat(account.get().owner()).isEqualTo("alice");
    }

    @Test
    void applyDepositUpdatesBalanceAndCreatesHistory() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        Instant t1 = Instant.parse("2026-08-04T12:01:00Z");
        UUID movementUid = UUID.randomUUID();

        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));
        projection.apply(new MoneyDeposited(id, 2, t1, movementUid, Money.of("GBP", 5000), "salary", Money.of("GBP", 5000)));

        BalanceView balance = projection.balance(id).orElseThrow();
        assertThat(balance.amount().minorUnits()).isEqualTo(5000);
        assertThat(balance.streamVersion()).isEqualTo(2);

        HistoryPage page = projection.history(id, new HistoryQuery(null, 10, null, null));
        assertThat(page.transactions()).hasSize(1);
        assertThat(page.transactions().getFirst().type()).isEqualTo(MovementType.DEPOSIT);
        assertThat(page.transactions().getFirst().amount().minorUnits()).isEqualTo(5000);
    }

    @Test
    void applyIsIdempotent() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");

        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP)); // replay

        assertThat(projection.balance(id)).isPresent();
    }

    @Test
    void historySupportsKeysetPagination() {
        AccountId id = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");
        projection.apply(new AccountOpened(id, 1, t0, "alice", "ACC-001", GBP));

        for (int i = 0; i < 5; i++) {
            Instant t = t0.plusSeconds(60 * (i + 1));
            projection.apply(new MoneyDeposited(id, 2 + i, t, UUID.randomUUID(),
                    Money.of("GBP", 100 * (i + 1)), "tx-" + i, Money.of("GBP", 100 * (i + 1) * (i + 1))));
        }

        // Page 1: limit 2
        HistoryPage page1 = projection.history(id, new HistoryQuery(null, 2, null, null));
        assertThat(page1.transactions()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();

        // Page 2: keyset cursor
        HistoryPage page2 = projection.history(id, new HistoryQuery(page1.nextCursor(), 2, null, null));
        assertThat(page2.transactions()).hasSize(2);
        assertThat(page2.nextCursor()).isNotNull();

        // Page 3: last page
        HistoryPage page3 = projection.history(id, new HistoryQuery(page2.nextCursor(), 2, null, null));
        assertThat(page3.transactions()).hasSize(1);
        assertThat(page3.nextCursor()).isNull();
    }

    @Test
    void accountsOwnedByReturnsOnlyMatchingOwner() {
        AccountId id1 = AccountId.random();
        AccountId id2 = AccountId.random();
        Instant t0 = Instant.parse("2026-08-04T12:00:00Z");

        projection.apply(new AccountOpened(id1, 1, t0, "alice", "ACC-001", GBP));
        projection.apply(new AccountOpened(id2, 1, t0.plusSeconds(1), "bob", "ACC-002", GBP));

        List<AccountView> aliceAccounts = projection.accountsOwnedBy("alice");
        assertThat(aliceAccounts).hasSize(1);
        assertThat(aliceAccounts.getFirst().accountId()).isEqualTo(id1);
    }
}
