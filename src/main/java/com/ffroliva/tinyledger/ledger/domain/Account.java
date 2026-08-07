package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import com.ffroliva.tinyledger.ledger.domain.policy.OverdraftPolicy;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import com.ffroliva.tinyledger.shared.error.InvalidAmountException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

public final class Account {
    private final AccountId id;
    private final String owner;
    private final String name;
    private final Currency currency;
    private long version;
    private long balanceMinorUnits;

    /**
     * Takes the opening event, not a bare id, and that is the point: {@code owner}, {@code name} and
     * {@code currency} are facts of {@code AccountOpened} (§4.1), so an {@code Account} that has not seen
     * one cannot be constructed at all. They are {@code final} as a consequence.
     *
     * <p>Before this they were mutable fields assigned only by {@code apply}'s {@code AccountOpened} arm,
     * so a history that merely *started* with something else — a {@code MoneyDeposited} at version 1
     * satisfies the gap check just as well — produced an account with a <b>null owner</b>, and the very
     * next thing both use cases do is {@code account.owner().equals(caller)} to authorise. That is a
     * NullPointerException on the authorisation path, reported by Sonar as S2259 at
     * {@code RecordMovementService:67} and {@code StrongBalanceService:28}. Those are two symptoms of
     * one cause, and the cause is that the invariant lived in a comment rather than in the type.
     */
    private Account(AccountOpened opened) {
        this.id = opened.accountId();
        this.owner = opened.owner();
        this.name = opened.name();
        this.currency = opened.currency();
        this.version = opened.version();
    }

    public static List<LedgerEvent> open(AccountId id, OpenAccount cmd, Instant now) {
        return List.of(new AccountOpened(id, 1, now, cmd.caller(), cmd.name(), cmd.currency()));
    }

    public static Account rehydrate(List<LedgerEvent> history) {
        if (history.isEmpty()) throw new IllegalArgumentException("empty stream");
        // A stream begins with AccountOpened or it is not an account's stream. Stated as a check rather
        // than assumed: the version-gap rule below cannot catch this, because any event at version 1
        // satisfies it. A bug, not a catalogued error (AGENTS.md) — so IllegalStateException, which the
        // advice turns into an opaque 500 rather than a §6.5 problem the caller could act on.
        if (!(history.getFirst() instanceof AccountOpened opened)) {
            throw new IllegalStateException("a stream must begin with AccountOpened, got "
                    + history.getFirst().getClass().getSimpleName());
        }
        Account account = new Account(opened);
        history.stream().skip(1).forEach(account::apply);
        return account;
    }

    public List<MovementEvent> deposit(Deposit cmd, Instant now) {
        requirePositive(cmd.amount());
        if (!currency.equals(cmd.amount().currency())) {
            return List.of(new MovementRejected(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    MovementType.DEPOSIT,
                    cmd.amount(),
                    "currency-mismatch",
                    cmd.caller()));
        }
        Money after = balance().plus(cmd.amount());
        return List.of(new MoneyDeposited(
                id, version + 1, now, cmd.movementUid(), cmd.amount(), cmd.reference(), after, cmd.caller()));
    }

    public List<MovementEvent> withdraw(Withdraw cmd, Instant now) {
        requirePositive(cmd.amount());
        if (!currency.equals(cmd.amount().currency())) {
            return List.of(new MovementRejected(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    MovementType.WITHDRAWAL,
                    cmd.amount(),
                    "currency-mismatch",
                    cmd.caller()));
        }
        Money after = balance().minus(cmd.amount());
        if (!OverdraftPolicy.permits(after)) {
            return List.of(new MovementRejected(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    MovementType.WITHDRAWAL,
                    cmd.amount(),
                    "insufficient-funds",
                    cmd.caller()));
        }
        return List.of(new MoneyWithdrawn(
                id, version + 1, now, cmd.movementUid(), cmd.amount(), cmd.reference(), after, cmd.caller()));
    }

    private void apply(LedgerEvent event) {
        if (event.version() != version + 1) {
            throw new IllegalStateException(
                    "gap in stream: expected %d got %d".formatted(version + 1, event.version()));
        }
        switch (event) {
            // Only reachable from a stream carrying TWO opening events, which is a corrupted stream
            // rather than a state this aggregate should fold. rehydrate consumes the first one into the
            // constructor and applies the rest, so the ordinary path never arrives here.
            case AccountOpened e -> throw new IllegalStateException("a second AccountOpened at version " + e.version());
            case MoneyDeposited e -> balanceMinorUnits = e.balanceAfter().minorUnits();
            case MoneyWithdrawn e -> balanceMinorUnits = e.balanceAfter().minorUnits();
            case MovementRejected _ -> {
                /* recorded, no balance change */
            }
        }
        version = event.version();
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) throw new InvalidAmountException("amount must be positive");
    }

    public AccountId id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public Currency currency() {
        return currency;
    }

    public long version() {
        return version;
    }

    public Money balance() {
        return new Money(currency, balanceMinorUnits);
    }
}
