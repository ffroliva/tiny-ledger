package com.ffroliva.tinyledger.ledger.application.usecase;

import com.ffroliva.tinyledger.ledger.application.error.*;
import com.ffroliva.tinyledger.ledger.application.port.in.*;
import com.ffroliva.tinyledger.ledger.application.port.out.*;
import com.ffroliva.tinyledger.ledger.domain.*;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RecordMovementService implements RecordMovementUseCase {
    private final EventStorePort store;
    private final EventPublisherPort publisher;
    private final ClockPort clock;
    private final IdGeneratorPort ids;

    public RecordMovementService(
            EventStorePort store, EventPublisherPort publisher, ClockPort clock, IdGeneratorPort ids) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public MovementResult deposit(Deposit cmd) {
        return record(
                cmd.caller(),
                cmd.callerIsAdmin(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.deposit(cmd, clock.now()),
                MovementType.DEPOSIT,
                cmd.amount(),
                cmd.reference());
    }

    @Override
    public MovementResult withdraw(Withdraw cmd) {
        return record(
                cmd.caller(),
                cmd.callerIsAdmin(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.withdraw(cmd, clock.now()),
                MovementType.WITHDRAWAL,
                cmd.amount(),
                cmd.reference());
    }

    private MovementResult record(
            String caller,
            boolean callerIsAdmin,
            AccountId accountId,
            UUID movementUid,
            java.util.function.Function<Account, List<LedgerEvent>> action,
            MovementType type,
            com.ffroliva.tinyledger.shared.Money amount,
            String reference) {
        List<LedgerEvent> history = store.read(accountId); // ①
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history); // ②
        // §6.4 D1: the role check already ran in the filter chain (SecurityConfig, ledger:writer).
        // This is the ownership term alone — ledger:admin widens it, and only it. The role term is
        // untouched: an admin without ledger:writer never reaches this line at all (N15).
        if (!account.owner().equals(caller) && !callerIsAdmin) throw new OwnershipException(caller, accountId); // ③
        Optional<LedgerEvent> existing = store.findByMovementUid(movementUid); // ④ (after authz)
        if (existing.isPresent()) return replayOf(existing.get(), accountId, type, amount, reference);
        List<LedgerEvent> events = action.apply(account); // ⑤
        try {
            store.append(accountId, account.version(), events); // ⑥
        } catch (DuplicateMovementException raced) {
            return replayOf(store.findByMovementUid(movementUid).orElseThrow(), accountId, type, amount, reference);
        }
        events.forEach(publisher::publish); // ⑦
        return resultOf(events.getFirst(), Outcome.CREATED, Outcome.REJECTED); // ⑧
    }

    private MovementResult replayOf(
            LedgerEvent event,
            AccountId requested,
            MovementType type,
            com.ffroliva.tinyledger.shared.Money amount,
            String reference) {
        boolean samePayload =
                switch (event) {
                    case MoneyDeposited d ->
                        d.accountId().equals(requested)
                                && type == MovementType.DEPOSIT
                                && d.amount().equals(amount)
                                && java.util.Objects.equals(d.reference(), reference);
                    case MoneyWithdrawn w ->
                        w.accountId().equals(requested)
                                && type == MovementType.WITHDRAWAL
                                && w.amount().equals(amount)
                                && java.util.Objects.equals(w.reference(), reference);
                    case MovementRejected r ->
                        r.accountId().equals(requested)
                                && r.type() == type
                                && r.amount().equals(amount);
                    case AccountOpened a -> false;
                };
        if (!samePayload) throw new IdempotencyConflictException(movementUidOf(event));
        return resultOf(event, Outcome.REPLAYED, Outcome.REJECTED_REPLAYED);
    }

    private MovementResult resultOf(LedgerEvent event, Outcome created, Outcome rejected) {
        return switch (event) {
            case MoneyDeposited d ->
                new MovementResult(
                        d.accountId(),
                        d.movementUid(),
                        MovementType.DEPOSIT,
                        d.version(),
                        d.amount(),
                        d.balanceAfter(),
                        d.occurredAt(),
                        created,
                        null);
            case MoneyWithdrawn w ->
                new MovementResult(
                        w.accountId(),
                        w.movementUid(),
                        MovementType.WITHDRAWAL,
                        w.version(),
                        w.amount(),
                        w.balanceAfter(),
                        w.occurredAt(),
                        created,
                        null);
            case MovementRejected r ->
                new MovementResult(
                        r.accountId(),
                        r.movementUid(),
                        r.type(),
                        r.version(),
                        r.amount(),
                        null,
                        r.occurredAt(),
                        rejected,
                        r.reason());
            case AccountOpened a -> throw new IllegalStateException("not a movement");
        };
    }

    private static UUID movementUidOf(LedgerEvent event) {
        return switch (event) {
            case MoneyDeposited d -> d.movementUid();
            case MoneyWithdrawn w -> w.movementUid();
            case MovementRejected r -> r.movementUid();
            case AccountOpened a -> throw new IllegalStateException("not a movement");
        };
    }
}
