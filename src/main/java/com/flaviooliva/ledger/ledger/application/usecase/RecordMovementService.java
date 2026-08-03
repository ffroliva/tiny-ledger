package com.flaviooliva.ledger.ledger.application.usecase;

import com.flaviooliva.ledger.ledger.application.error.*;
import com.flaviooliva.ledger.ledger.application.port.in.*;
import com.flaviooliva.ledger.ledger.application.port.out.*;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.AccountId;
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
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.deposit(cmd, clock.now()),
                MovementType.DEPOSIT,
                cmd.amount());
    }

    @Override
    public MovementResult withdraw(Withdraw cmd) {
        return record(
                cmd.caller(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.withdraw(cmd, clock.now()),
                MovementType.WITHDRAWAL,
                cmd.amount());
    }

    private MovementResult record(
            String caller,
            AccountId accountId,
            UUID movementUid,
            java.util.function.Function<Account, List<LedgerEvent>> action,
            MovementType type,
            com.flaviooliva.ledger.shared.Money amount) {
        List<LedgerEvent> history = store.read(accountId); // ①
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history); // ②
        if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId); // ③
        Optional<LedgerEvent> existing = store.findByMovementUid(movementUid); // ④ (after authz)
        if (existing.isPresent()) return replayOf(existing.get(), accountId, type, amount);
        List<LedgerEvent> events = action.apply(account); // ⑤
        try {
            store.append(accountId, account.version(), events); // ⑥
        } catch (DuplicateMovementException raced) {
            return replayOf(store.findByMovementUid(movementUid).orElseThrow(), accountId, type, amount);
        }
        events.forEach(publisher::publish); // ⑦
        return resultOf(events.getFirst(), Outcome.CREATED, Outcome.REJECTED); // ⑧
    }

    private MovementResult replayOf(
            LedgerEvent event, AccountId requested, MovementType type, com.flaviooliva.ledger.shared.Money amount) {
        boolean samePayload =
                switch (event) {
                    case MoneyDeposited d ->
                        d.accountId().equals(requested)
                                && type == MovementType.DEPOSIT
                                && d.amount().equals(amount);
                    case MoneyWithdrawn w ->
                        w.accountId().equals(requested)
                                && type == MovementType.WITHDRAWAL
                                && w.amount().equals(amount);
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
