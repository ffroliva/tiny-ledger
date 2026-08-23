package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.ledger.application.port.in.AssetTransfer;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import com.ffroliva.tinyledger.ledger.domain.policy.OverdraftPolicy;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import com.ffroliva.tinyledger.shared.TenantId;
import com.ffroliva.tinyledger.shared.error.InvalidAmountException;
import java.time.Instant;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Account {
    private final AccountId id;
    private final String owner;
    private final String name;
    private final Currency currency;
    private long version;
    private long balanceMinorUnits;
    private final Map<String, TaxLotAggregate> assetHoldings = new HashMap<>();

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

    public static List<LedgerEvent> open(AccountId id, OpenAccount cmd, Instant now, TenantId tenantId) {
        // tenant is a parameter, not a field on the command: it is resolved from authenticated
        // context by the caller. A tenant that can name its own tenant can read another's accounts.
        return List.of(new AccountOpened(id, 1, now, cmd.caller(), cmd.name(), cmd.currency(), tenantId));
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

    public List<MovementEvent> transferAsset(AssetTransfer cmd, Instant now) {
        Quantity qty = cmd.quantity();
        if (!qty.isPositive()) {
            throw new InvalidAmountException("quantity must be positive");
        }

        if ("IN".equalsIgnoreCase(cmd.direction())) {
            Money basis = cmd.costBasis();
            if (basis == null || basis.isNegative()) {
                throw new InvalidAmountException("cost basis must be non-negative");
            }
            if (!currency.equals(basis.currency())) {
                return List.of(new MovementRejected(
                        id,
                        version + 1,
                        now,
                        cmd.movementUid(),
                        MovementType.ASSET_TRANSFER,
                        basis,
                        "currency-mismatch",
                        cmd.caller()));
            }
            String lotId = cmd.lotId() != null && !cmd.lotId().isBlank()
                    ? cmd.lotId()
                    : cmd.movementUid().toString();
            TaxLot lot = new TaxLot(lotId, qty, basis, now);
            TaxLotAggregate book = getOrCreateAggregate(qty.symbol(), qty.assetClass());
            book.acquire(lot);
            return List.of(new AssetTransferred(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    qty,
                    basis,
                    List.of(lot),
                    cmd.selector(),
                    cmd.reference(),
                    balance(),
                    cmd.caller()));
        } else if ("OUT".equalsIgnoreCase(cmd.direction())) {
            TaxLotAggregate book =
                    assetHoldings.get(qty.symbol() + ":" + qty.assetClass().name());
            if (book == null || book.quantity().microUnits() < qty.microUnits()) {
                return List.of(new MovementRejected(
                        id,
                        version + 1,
                        now,
                        cmd.movementUid(),
                        MovementType.ASSET_TRANSFER,
                        new Money(currency, 0),
                        "insufficient-holding",
                        cmd.caller()));
            }
            TaxLotSelector selector = cmd.selector() != null ? cmd.selector() : TaxLotSelector.HIFO;
            List<TaxLot> consumed = book.dispose(qty, selector);
            Money totalCostBasis = consumed.stream().map(TaxLot::costBasis).reduce(new Money(currency, 0), Money::plus);
            Quantity disposedQty = qty.negated();
            return List.of(new AssetTransferred(
                    id,
                    version + 1,
                    now,
                    cmd.movementUid(),
                    disposedQty,
                    totalCostBasis,
                    consumed,
                    selector,
                    cmd.reference(),
                    balance(),
                    cmd.caller()));
        } else {
            throw new InvalidAmountException("direction must be IN or OUT");
        }
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
            case AssetTransferred e -> {
                if (e.quantity().isPositive()) {
                    e.taxLots().forEach(lot -> getOrCreateAggregate(
                                    e.quantity().symbol(), e.quantity().assetClass())
                            .acquire(lot));
                } else if (e.quantity().isNegative()) {
                    TaxLotSelector sel = e.selector() != null ? e.selector() : TaxLotSelector.HIFO;
                    getOrCreateAggregate(e.quantity().symbol(), e.quantity().assetClass())
                            .dispose(e.quantity().negated(), sel);
                }
            }
            case MovementRejected _ -> {
                /* recorded, no balance change */
            }
        }
        version = event.version();
    }

    private TaxLotAggregate getOrCreateAggregate(String symbol, AssetClass assetClass) {
        return assetHoldings.computeIfAbsent(
                symbol + ":" + assetClass.name(), _ -> TaxLotAggregate.of(symbol, assetClass, currency));
    }

    public Quantity holding(String symbol, AssetClass assetClass) {
        TaxLotAggregate book = assetHoldings.get(symbol + ":" + assetClass.name());
        return book != null ? book.quantity() : Quantity.zero(symbol, assetClass);
    }

    public Money costBasis(String symbol, AssetClass assetClass) {
        TaxLotAggregate book = assetHoldings.get(symbol + ":" + assetClass.name());
        return book != null ? book.costBasis() : new Money(currency, 0);
    }

    public List<TaxLot> lots(String symbol, AssetClass assetClass) {
        TaxLotAggregate book = assetHoldings.get(symbol + ":" + assetClass.name());
        return book != null ? book.lots() : List.of();
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
