package com.ffroliva.tinyledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.ffroliva.tinyledger.ledger.application.error.AccountNotFoundException;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.ledger.application.port.in.StrongBalance;
import com.ffroliva.tinyledger.ledger.application.usecase.StrongBalanceService;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrongBalanceServiceTest {
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final Instant now = Instant.parse("2026-08-04T12:00:00Z");
    private final StrongBalanceService service = new StrongBalanceService(store, () -> now);

    @Test
    void throwsAccountNotFoundWhenStoreIsEmpty() {
        AccountId id = AccountId.random();
        assertThatThrownBy(() -> service.strongBalance("owner1", id)).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void throwsOwnershipExceptionWhenCallerIsNotOwner() {
        AccountId id = AccountId.random();
        store.append(id, 0, List.of(new AccountOpened(id, 1, now, "owner1", "ACC-001", Currency.getInstance("GBP"))));

        assertThatThrownBy(() -> service.strongBalance("wrong-user", id)).isInstanceOf(OwnershipException.class);
    }

    @Test
    void returnsStrongBalanceForAuthorizedOwner() {
        AccountId id = AccountId.random();
        store.append(id, 0, List.of(new AccountOpened(id, 1, now, "owner1", "ACC-001", Currency.getInstance("GBP"))));

        StrongBalance balance = service.strongBalance("owner1", id);
        assertThat(balance.accountId()).isEqualTo(id);
        assertThat(balance.amount()).isEqualTo(Money.of("GBP", 0));
        assertThat(balance.asOf()).isEqualTo(now);
        assertThat(balance.streamVersion()).isEqualTo(1);
    }
}
