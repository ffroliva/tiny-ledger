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
    private final TenantResolverPort tenantResolver;

    /**
     * No {@code IdGeneratorPort}, deliberately. It used to take one and never call it, which read as
     * though this service mints identities — the opposite of §6.3, where the <em>client</em> supplies
     * the {@code movementUid} and only account opening is server-uid'd (N22). A constructor parameter
     * that is never used is a claim about the design, and this one was false.
     */
    public RecordMovementService(
            EventStorePort store, EventPublisherPort publisher, ClockPort clock, TenantResolverPort tenantResolver) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.tenantResolver = tenantResolver;
    }

    @Override
    public MovementResult deposit(Deposit cmd) {
        return recordMovement(
                cmd.caller(),
                cmd.callerIsAdmin(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.deposit(cmd, clock.now()),
                MovementType.DEPOSIT,
                cmd.amount(),
                cmd.reference(),
                null);
    }

    @Override
    public MovementResult withdraw(Withdraw cmd) {
        return recordMovement(
                cmd.caller(),
                cmd.callerIsAdmin(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.withdraw(cmd, clock.now()),
                MovementType.WITHDRAWAL,
                cmd.amount(),
                cmd.reference(),
                null);
    }

    @Override
    public MovementResult transferAsset(AssetTransfer cmd) {
        return recordMovement(
                cmd.caller(),
                cmd.callerIsAdmin(),
                cmd.accountId(),
                cmd.movementUid(),
                account -> account.transferAsset(cmd, clock.now()),
                MovementType.ASSET_TRANSFER,
                cmd.costBasis(),
                cmd.reference(),
                cmd.quantity());
    }

    private MovementResult recordMovement(
            String caller,
            boolean callerIsAdmin,
            AccountId accountId,
            UUID movementUid,
            java.util.function.Function<Account, List<MovementEvent>> action,
            MovementType type,
            com.ffroliva.tinyledger.shared.Money amount,
            String reference,
            Quantity quantity) {
        List<LedgerEvent> history = store.read(accountId); // ①
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history); // ②
        // Tenant first, and independently — the same term, in the same position, as the strong read
        // (StrongBalanceService): evaluated before ownership so the admin disjunct below widens the
        // ownership term only, never the tenant boundary. A null tenant (a stream opened before
        // tenancy) fails closed rather than matching everyone.
        if (!tenantResolver.currentTenant().equals(account.tenantId())) {
            throw new TenantIsolationException(accountId);
        }
        // §6.4 D1: the role check already ran in the filter chain (SecurityConfig, ledger:writer).
        // This is the ownership term alone — ledger:admin widens it, and only it. The role term is
        // untouched: an admin without ledger:writer never reaches this line at all (N15, Task 5).
        if (!account.owner().equals(caller) && !callerIsAdmin) throw new OwnershipException(caller, accountId); // ③
        Optional<MovementEvent> existing = store.findByMovementUid(movementUid); // ④ (after authz)
        if (existing.isPresent()) return replayOf(existing.get(), accountId, type, amount, reference, quantity);
        List<MovementEvent> events = action.apply(account); // ⑤
        try {
            store.append(accountId, account.version(), events); // ⑥
        } catch (DuplicateMovementException _) {
            return replayOf(
                    store.findByMovementUid(movementUid).orElseThrow(), accountId, type, amount, reference, quantity);
        }
        events.forEach(publisher::publish); // ⑦
        return resultOf(events.getFirst(), Outcome.CREATED, Outcome.REJECTED); // ⑧
    }

    private MovementResult replayOf(
            MovementEvent event,
            AccountId requested,
            MovementType type,
            com.ffroliva.tinyledger.shared.Money amount,
            String reference,
            Quantity quantity) {
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
                    case AssetTransferred a ->
                        a.accountId().equals(requested)
                                && type == MovementType.ASSET_TRANSFER
                                && (quantity == null
                                        || a.quantity().equals(quantity)
                                        || a.quantity().negated().equals(quantity))
                                && java.util.Objects.equals(a.reference(), reference);
                    case MovementRejected r -> r.accountId().equals(requested) && r.type() == type;
                };
        // No AccountOpened arm to write: MovementEvent is sealed over exactly the four events that
        // carry a movementUid, so the compiler knows this switch is total. The uid comes straight off
        // the interface — the four-arm helper that threw for a case it could not receive is gone.
        if (!samePayload) throw new IdempotencyConflictException(event.movementUid());
        return resultOf(event, Outcome.REPLAYED, Outcome.REJECTED_REPLAYED);
    }

    private MovementResult resultOf(MovementEvent event, Outcome created, Outcome rejected) {
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
            case AssetTransferred a ->
                new MovementResult(
                        a.accountId(),
                        a.movementUid(),
                        MovementType.ASSET_TRANSFER,
                        a.version(),
                        a.costBasis(),
                        a.balanceAfter(),
                        a.occurredAt(),
                        created,
                        null,
                        a.quantity(),
                        a.taxLots());
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
        };
    }
}
