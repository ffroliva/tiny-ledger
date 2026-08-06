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
    private String owner;
    private String name;
    private Currency currency;
    private long version;
    private long balanceMinorUnits;

    private Account(AccountId id) {
        this.id = id;
    }

    public static List<LedgerEvent> open(AccountId id, OpenAccount cmd, Instant now) {
        return List.of(new AccountOpened(id, 1, now, cmd.caller(), cmd.name(), cmd.currency()));
    }

    public static Account rehydrate(List<LedgerEvent> history) {
        if (history.isEmpty()) throw new IllegalArgumentException("empty stream");
        Account account = new Account(history.getFirst().accountId());
        history.forEach(account::apply);
        return account;
    }

    public List<LedgerEvent> deposit(Deposit cmd, Instant now) {
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

    public List<LedgerEvent> withdraw(Withdraw cmd, Instant now) {
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
            case AccountOpened e -> {
                owner = e.owner();
                name = e.name();
                currency = e.currency();
            }
            case MoneyDeposited e -> balanceMinorUnits = e.balanceAfter().minorUnits();
            case MoneyWithdrawn e -> balanceMinorUnits = e.balanceAfter().minorUnits();
            case MovementRejected e -> {
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
