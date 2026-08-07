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
        List<MovementEvent> events = account.deposit(
                new Deposit("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), "rent"), T);
        MoneyDeposited deposited = (MoneyDeposited) events.getFirst();
        assertThat(deposited.version()).isEqualTo(account.version() + 1);
        assertThat(deposited.balanceAfter()).isEqualTo(new Money(GBP, 10_000));
    }

    @Test // §2.3/§2.4: the use case stamps the caller onto every event it emits, as `actor`
    void depositStampsTheCallerAsActor() {
        Account account = openedWith(0);
        List<MovementEvent> events = account.deposit(
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
        List<MovementEvent> events = account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), null), T);
        assertThat(((MovementRejected) events.getFirst()).actor()).isEqualTo("alice");
    }

    @Test
    void withdrawalBeyondBalanceEmitsMovementRejectedNotAnException() {
        List<LedgerEvent> history = historyWith(5_000);
        Account account = Account.rehydrate(history);
        List<MovementEvent> events = account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 10_000), null), T);
        MovementRejected rejected = (MovementRejected) events.getFirst();
        assertThat(rejected.reason()).isEqualTo("insufficient-funds");
        history.addAll(events);
        assertThat(Account.rehydrate(history).balance()).isEqualTo(new Money(GBP, 5_000));
    }

    @Test
    void exactBalanceWithdrawalIsAllowed() {
        Account account = openedWith(5_000);
        List<MovementEvent> events = account.withdraw(
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
        List<MovementEvent> events = account.deposit(
                new Deposit("alice", false, account.id(), UUID.randomUUID(), Money.of("EUR", 100), null), T);
        assertThat(((MovementRejected) events.getFirst()).reason()).isEqualTo("currency-mismatch");
    }

    @Test
    void withdrawalInAnotherCurrencyIsRejectedToo() {
        Account account = openedWith(5_000);
        List<MovementEvent> events = account.withdraw(
                new Withdraw("alice", false, account.id(), UUID.randomUUID(), Money.of("EUR", 100), null), T);
        MovementRejected rejected = (MovementRejected) events.getFirst();
        assertThat(rejected.reason()).isEqualTo("currency-mismatch");
        assertThat(rejected.type()).isEqualTo(MovementType.WITHDRAWAL);
    }

    /**
     * The positive twin of the two currency-mismatch tests above, and — checked by grep across
     * {@code src/test} and {@code ledger-cli/tests} — <strong>the only place in this repository that opens an
     * account in anything but GBP</strong>. Everywhere else EUR appears only as the amount being refused, so
     * a comparison against a hardcoded {@code GBP} literal rather than {@code this.currency} passed every
     * test here. The dual-currency behaviour §7 describes was asserted entirely by its refusals.
     *
     * <p>The refused half is the discriminator: it is the mirror image of the tests above, and it only
     * passes if the check reads the account's own currency. A hardcoded-GBP implementation accepts it.
     */
    @Test
    void anAccountHoldsItsOwnCurrencyRatherThanAHardcodedOne() {
        Currency eur = Currency.getInstance("EUR");
        AccountId id = AccountId.random();
        Account account = Account.rehydrate(Account.open(id, new OpenAccount("alice", "ACC-EUR", eur), T));
        assertThat(account.currency()).isEqualTo(eur);

        List<MovementEvent> accepted =
                account.deposit(new Deposit("alice", false, id, UUID.randomUUID(), new Money(eur, 100), null), T);
        assertThat(accepted.getFirst()).isInstanceOf(MoneyDeposited.class);
        assertThat(((MoneyDeposited) accepted.getFirst()).balanceAfter()).isEqualTo(new Money(eur, 100));

        List<MovementEvent> refused =
                account.deposit(new Deposit("alice", false, id, UUID.randomUUID(), new Money(GBP, 100), null), T);
        assertThat(((MovementRejected) refused.getFirst()).reason()).isEqualTo("currency-mismatch");
    }

    /**
     * The owner is the authorisation subject: {@code RecordMovementService} and
     * {@code StrongBalanceService} both decide access with {@code account.owner().equals(caller)}, so an
     * ownerless account is a NullPointerException on the authorisation path — which Sonar reported as
     * two S2259 bugs, one per call site. Guarded at construction so neither caller can meet one.
     */
    /**
     * The stream-shape invariant, and the actual cause behind Sonar's two S2259 reports. A history whose
     * first event is not {@code AccountOpened} used to rehydrate happily — the version-gap rule cannot
     * catch it, because a {@code MoneyDeposited} at version 1 satisfies {@code version + 1} just as well
     * — leaving {@code owner} null. Both use cases then authorise with {@code owner().equals(caller)},
     * so the next step was a NullPointerException on the authorisation path.
     */
    @Test
    void aStreamThatDoesNotBeginWithAccountOpenedIsRefused() {
        AccountId id = AccountId.random();
        List<LedgerEvent> headless = List.of(new MoneyDeposited(
                id, 1, T, UUID.randomUUID(), new Money(GBP, 100), null, new Money(GBP, 100), "alice"));

        assertThatThrownBy(() -> Account.rehydrate(headless))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must begin with AccountOpened");
    }

    @Test
    void anAccountCannotBeOpenedWithoutAnOwner() {
        assertThatThrownBy(() -> new AccountOpened(AccountId.random(), 1, T, null, "ACC-001", GBP))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void rehydrateRejectsEmptyHistoryAndVersionGaps() {
        assertThatThrownBy(() -> Account.rehydrate(List.<LedgerEvent>of()))
                .isInstanceOf(IllegalArgumentException.class);
        List<LedgerEvent> gapped = historyWith(0);
        AccountId id = gapped.getFirst().accountId();
        gapped.add(
                new MoneyDeposited(id, 3, T, UUID.randomUUID(), new Money(GBP, 100), null, new Money(GBP, 100), null));
        // The message, not just the type. A `MathMutator` on the "expected %d" arithmetic survived the
        // whole suite because only the exception class was asserted — and this message is the entire
        // diagnostic an operator gets for a corrupted stream. A wrong "expected" number sends them
        // hunting for the wrong event.
        assertThatThrownBy(() -> Account.rehydrate(gapped))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("gap in stream: expected 2 got 3");
    }

    @Test
    void nonPositiveAmountsAreRejectedByTheAggregateToo() {
        Account account = openedWith(5_000);
        assertThatThrownBy(() -> account.deposit(
                        new Deposit("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 0), null), T))
                .isInstanceOf(InvalidAmountException.class); // defence in depth; the boundary 400s first (§4.6)
    }

    /**
     * Kills the mutant recorded as `performance-findings` §6.4 row 2: deleting {@code requirePositive} from
     * {@code Account.withdraw} passed the entire suite, while the identical guard on {@code deposit} was
     * killed by the test above. A one-sided gap — deposit had this case, withdrawal did not.
     */
    @Test
    void nonPositiveAmountsAreRejectedOnWithdrawalToo() {
        Account account = openedWith(5_000);
        assertThatThrownBy(() -> account.withdraw(
                        new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, 0), null), T))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> account.withdraw(
                        new Withdraw("alice", false, account.id(), UUID.randomUUID(), new Money(GBP, -1), null), T))
                .isInstanceOf(InvalidAmountException.class);
    }

    /**
     * Kills §6.4's fourth mutant: nothing asserted the version stamped on a {@code MovementRejected} when a
     * *withdrawal* is refused for currency mismatch, so a {@code MathMutator} on {@code version + 1}
     * survived. The version matters — it is the optimistic-concurrency token the append is checked against,
     * so a rejection stamped at the wrong version corrupts the next writer's expectations, not just a field.
     */
    @Test
    void aCurrencyMismatchedWithdrawalIsRejectedAtTheNextStreamVersion() {
        Account account = openedWith(5_000); // AccountOpened v1 + MoneyDeposited v2
        MovementRejected rejected = (MovementRejected) account.withdraw(
                        new Withdraw("alice", false, account.id(), UUID.randomUUID(), Money.of("EUR", 100), null), T)
                .getFirst();
        assertThat(rejected.reason()).isEqualTo("currency-mismatch");
        assertThat(rejected.version()).isEqualTo(account.version() + 1);
        assertThat(rejected.version()).isEqualTo(3);
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
