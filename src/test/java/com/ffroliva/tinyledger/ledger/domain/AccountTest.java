package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.ledger.application.port.in.*;
import com.ffroliva.tinyledger.shared.*;
import com.ffroliva.tinyledger.shared.error.InvalidAmountException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AccountTest {
    private static final Instant T = Instant.parse("2026-08-03T12:00:00Z");
    private static final Currency GBP = Currency.getInstance("GBP");

    private List<LedgerEvent> historyWith(long minorUnits) {
        AccountId id = AccountId.random();
        List<LedgerEvent> history = new ArrayList<>(Account.open(id, new OpenAccount("alice", "ACC-001", GBP), T));
        if (minorUnits > 0) {
            history.addAll(Account.rehydrate(history)
                    .deposit(new Deposit("alice", false, id, UUID.randomUUID(), new Money(GBP, minorUnits), null), T));
        }
        return history;
    }

    private Account openedWith(long minorUnits) {
        return Account.rehydrate(historyWith(minorUnits));
    }

    @Test
    void openEmitsAccountOpenedAtVersionOneWithOwnerAndName() {
        List<LedgerEvent> events = Account.open(AccountId.random(), new OpenAccount("alice", "ACC-001", GBP), T);
        assertThat(events).singleElement().isInstanceOf(AccountOpened.class);
        AccountOpened opened = (AccountOpened) events.getFirst();
        assertThat(opened.version()).isEqualTo(1);
        assertThat(opened.owner()).isEqualTo("alice");
        assertThat(opened.name()).isEqualTo("ACC-001");
    }

    @Test
    void depositIncrementsVersionByExactlyOneAndCarriesBalanceAfter() {
        Account account = openedWith(0);
        List<LedgerEvent> events = account.deposit(
                new Deposit("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), "rent"), T);
        MoneyDeposited deposited = (MoneyDeposited) events.getFirst();
        assertThat(deposited.version()).isEqualTo(account.version() + 1);
        assertThat(deposited.balanceAfter()).isEqualTo(new Money(GBP, 10_000));
    }

    @Test // §2.3/§2.4: the use case stamps the caller onto every event it emits, as `actor`
    void depositStampsTheCallerAsActor() {
        Account account = openedWith(0);
        List<LedgerEvent> events = account.deposit(
                new Deposit("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), "rent"), T);
        MoneyDeposited deposited = (MoneyDeposited) events.getFirst();
        assertThat(deposited.actor()).isEqualTo("alice");
    }

    @Test // AccountOpened has no on-behalf-of form (§15.8) — actor is always the owner, never a component
    void accountOpenedDerivesActorFromOwner() {
        List<LedgerEvent> events = Account.open(AccountId.random(), new OpenAccount("alice", "ACC-001", GBP), T);
        AccountOpened opened = (AccountOpened) events.getFirst();
        assertThat(opened.actor()).isEqualTo("alice");
    }

    @Test // a rejection is audit-relevant too (§2.3) — it also carries who attempted it
    void rejectionStampsTheCallerAsActor() {
        Account account = openedWith(5_000);
        List<LedgerEvent> events = account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), null), T);
        assertThat(((MovementRejected) events.getFirst()).actor()).isEqualTo("alice");
    }

    @Test
    void withdrawalBeyondBalanceEmitsMovementRejectedNotAnException() {
        List<LedgerEvent> history = historyWith(5_000);
        Account account = Account.rehydrate(history);
        List<LedgerEvent> events = account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), null), T);
        MovementRejected rejected = (MovementRejected) events.getFirst();
        assertThat(rejected.reason()).isEqualTo("insufficient-funds");
        history.addAll(events);
        assertThat(Account.rehydrate(history).balance()).isEqualTo(new Money(GBP, 5_000));
    }

    @Test
    void exactBalanceWithdrawalIsAllowed() {
        Account account = openedWith(5_000);
        List<LedgerEvent> events = account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 5_000), null), T);
        assertThat(events.getFirst()).isInstanceOf(MoneyWithdrawn.class);
    }

    @Test
    void rehydrateAppliesWithdrawalAndKeepsOpeningDetails() {
        List<LedgerEvent> history = historyWith(5_000);
        Account account = Account.rehydrate(history);
        history.addAll(account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 2_000), null), T));
        Account rehydrated = Account.rehydrate(history);
        assertThat(rehydrated.balance()).isEqualTo(new Money(GBP, 3_000));
        assertThat(rehydrated.owner()).isEqualTo("alice");
        assertThat(rehydrated.name()).isEqualTo("ACC-001");
        assertThat(rehydrated.currency()).isEqualTo(GBP);
    }

    @Test
    void currencyMismatchIsRejectedAsStateNotShape() {
        Account account = openedWith(5_000);
        List<LedgerEvent> events = account.deposit(
                new Deposit("alice", false, account.id(), UUID.randomUUID(), Money.of("EUR", 100), null), T);
        assertThat(((MovementRejected) events.getFirst()).reason()).isEqualTo("currency-mismatch");
    }

    @Test
    void withdrawalInAnotherCurrencyIsRejectedToo() {
        Account account = openedWith(5_000);
        List<LedgerEvent> events = account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), Money.of("EUR", 100), null), T);
        MovementRejected rejected = (MovementRejected) events.getFirst();
        assertThat(rejected.reason()).isEqualTo("currency-mismatch");
        assertThat(rejected.type()).isEqualTo(MovementType.WITHDRAWAL);
    }

    @Test
    void rehydrateRejectsEmptyHistoryAndVersionGaps() {
        assertThatThrownBy(() -> Account.rehydrate(List.<LedgerEvent>of()))
                .isInstanceOf(IllegalArgumentException.class);
        List<LedgerEvent> gapped = historyWith(0);
        AccountId id = gapped.getFirst().accountId();
        gapped.add(
                new MoneyDeposited(id, 3, T, UUID.randomUUID(), new Money(GBP, 100), null, new Money(GBP, 100), null));
        assertThatThrownBy(() -> Account.rehydrate(gapped)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonPositiveAmountsAreRejectedByTheAggregateToo() {
        Account account = openedWith(5_000);
        assertThatThrownBy(() -> account.deposit(
                        new Deposit("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 0), null), T))
                .isInstanceOf(InvalidAmountException.class); // defence in depth; the boundary 400s first (§4.6)
    }

    @Test
    void balanceIsOnlyEverComputedFromEvents() {
        // a freshly opened account has no movements, so its balance is zero
        assertThat(openedWith(0).balance()).isEqualTo(new Money(GBP, 0));

        // every subsequent balance is derived from the movement events alone
        List<LedgerEvent> history = historyWith(5_000);
        AccountId id = history.getFirst().accountId();
        history.addAll(Account.rehydrate(history)
                .deposit(new Deposit("alice", false, id, UUID.randomUUID(), new Money(GBP, 2_500), null), T));
        assertThat(Account.rehydrate(history).balance()).isEqualTo(new Money(GBP, 7_500));
    }
}
