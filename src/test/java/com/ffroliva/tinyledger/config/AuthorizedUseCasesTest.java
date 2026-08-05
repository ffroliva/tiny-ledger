package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryPage;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryQuery;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * §6.4 at the port boundary. These tests construct the decorators directly, so they say nothing about
 * whether the decorators are <em>wired</em> — {@code SecurityConfigIT} is the only proof of that, and its
 * javadoc says why.
 */
class AuthorizedUseCasesTest {

    private static final AccountId ACCOUNT = AccountId.random();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final Currency GBP = Currency.getInstance("GBP");
    // HistoryQuery/HistoryPage are plain records with no factory methods — see their declarations.
    private static final HistoryQuery FIRST_PAGE = new HistoryQuery(null, 10, null, null);

    private final QueryBalanceUseCase balances =
            (caller, id) -> Optional.of(new BalanceView(id, Money.of("GBP", 5000), NOW, 3));

    private final QueryHistoryUseCase histories = (caller, id, query) -> new HistoryPage(List.of(), null);

    /** {@code account} answers for ACCOUNT only, so an unknown id exercises the absent branch. */
    private QueryAccountsUseCase ownedBy(String owner) {
        return new QueryAccountsUseCase() {
            @Override
            public List<AccountView> accountsOwnedBy(String o) {
                return owner.equals(o) ? List.of(new AccountView(ACCOUNT, "acc", owner, GBP, NOW)) : List.of();
            }

            @Override
            public Optional<AccountView> account(AccountId id) {
                return ACCOUNT.equals(id)
                        ? Optional.of(new AccountView(ACCOUNT, "acc", owner, GBP, NOW))
                        : Optional.empty();
            }
        };
    }

    @Test // §6.4: the owner reads their own balance
    void theOwnerIsAllowedThrough() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(balances, ownedBy("alice"));

        assertThat(authorized.balance("alice", ACCOUNT)).isPresent();
    }

    @Test // §6.4: and a stranger is refused with the catalogued 403, not a 500
    void aCallerWhoDoesNotOwnTheAccountIsRefused() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(balances, ownedBy("alice"));

        assertThatThrownBy(() -> authorized.balance("mallory", ACCOUNT)).isInstanceOf(OwnershipException.class);
    }

    @Test // §6.5: an account that does not exist is a 404 from the delegate, NOT a 403 from here
    void anUnknownAccountIsNotRefusedAsUnowned() {
        QueryBalanceUseCase authorized = new AuthorizedUseCases.Balances(balances, ownedBy("alice"));

        assertThatCode(() -> authorized.balance("alice", AccountId.random())).doesNotThrowAnyException();
    }

    @Test // History is a separate decorator and needs its own proof — Balances passing says nothing about it
    void historyAllowsTheOwner() {
        QueryHistoryUseCase authorized = new AuthorizedUseCases.History(histories, ownedBy("alice"));

        assertThat(authorized.history("alice", ACCOUNT, FIRST_PAGE)).isNotNull();
    }

    @Test // the one that fails silently in production if the bean is missing, so it is named on its own
    void historyRefusesAStranger() {
        QueryHistoryUseCase authorized = new AuthorizedUseCases.History(histories, ownedBy("alice"));

        assertThatThrownBy(() -> authorized.history("mallory", ACCOUNT, FIRST_PAGE))
                .isInstanceOf(OwnershipException.class);
    }
}
