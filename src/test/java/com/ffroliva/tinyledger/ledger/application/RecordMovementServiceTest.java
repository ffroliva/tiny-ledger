package com.ffroliva.tinyledger.ledger.application;

import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.ledger.application.error.*;
import com.ffroliva.tinyledger.ledger.application.port.in.*;
import com.ffroliva.tinyledger.ledger.application.port.out.*;
import com.ffroliva.tinyledger.ledger.application.usecase.*;
import com.ffroliva.tinyledger.ledger.domain.*;
import com.ffroliva.tinyledger.shared.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class RecordMovementServiceTest {
    private static final Currency GBP = Currency.getInstance("GBP");
    private final FakeStore store = new FakeStore();
    private final List<LedgerEvent> published = new ArrayList<>();
    private final RecordMovementService service = new RecordMovementService(
            store, published::add, () -> Instant.parse("2026-08-03T12:00:00Z"), UUID::randomUUID);
    private final OpenAccountService openService = new OpenAccountService(
            store, published::add, () -> Instant.parse("2026-08-03T12:00:00Z"), () -> UUID.randomUUID());

    private AccountId opened;

    @BeforeEach
    void openAccount() {
        opened = openService.open(new OpenAccount("alice", "ACC-001", GBP)).accountId();
    }

    @Test
    void firstDepositIsCreatedAndPublished() {
        MovementResult result =
                service.deposit(new Deposit("alice", opened, UUID.randomUUID(), new Money(GBP, 10_000), "rent"));
        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.balanceAfter()).isEqualTo(new Money(GBP, 10_000));
        assertThat(published).hasSize(2); // AccountOpened + MoneyDeposited
    }

    @Test
    void replaySameUidSamePayloadReturnsReplayedWithoutSecondCredit() {
        UUID uid = UUID.randomUUID();
        Deposit cmd = new Deposit("alice", opened, uid, new Money(GBP, 10_000), null);
        service.deposit(cmd);
        MovementResult replay = service.deposit(cmd);
        assertThat(replay.outcome()).isEqualTo(Outcome.REPLAYED);
        assertThat(store.read(opened)).hasSize(2); // no third event
    }

    @Test
    void sameUidDifferentAmountIsAnIdempotencyConflict() {
        UUID uid = UUID.randomUUID();
        service.deposit(new Deposit("alice", opened, uid, new Money(GBP, 10_000), null));
        assertThatThrownBy(() -> service.deposit(new Deposit("alice", opened, uid, new Money(GBP, 999), null)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void foreignCallerIsRefusedBeforeAnyIdempotencyAnswer() {
        UUID uid = UUID.randomUUID();
        service.deposit(new Deposit("alice", opened, uid, new Money(GBP, 10_000), null));
        assertThatThrownBy(() -> service.deposit(new Deposit("mallory", opened, uid, new Money(GBP, 10_000), null)))
                .isInstanceOf(OwnershipException.class); // NOT IdempotencyConflict — §4.1 ordering
    }

    @Test
    void insufficientFundsIsRecordedAndReportedAsRejected() {
        MovementResult result =
                service.withdraw(new Withdraw("alice", opened, UUID.randomUUID(), new Money(GBP, 5_000), null));
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("insufficient-funds");
        assertThat(store.read(opened)).hasSize(2); // MovementRejected IS on the stream
    }

    @Test
    void retryingARejectedUidReplaysTheRejection() {
        UUID uid = UUID.randomUUID();
        service.withdraw(new Withdraw("alice", opened, uid, new Money(GBP, 5_000), null));
        MovementResult replay = service.withdraw(new Withdraw("alice", opened, uid, new Money(GBP, 5_000), null));
        assertThat(replay.outcome()).isEqualTo(Outcome.REJECTED_REPLAYED);
    }

    @Test
    void unknownAccountIs404Shaped() {
        assertThatThrownBy(() -> service.deposit(
                        new Deposit("alice", AccountId.random(), UUID.randomUUID(), new Money(GBP, 1), null)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    /** Minimal fake honouring the port contract; the real contract suite is Task 6. */
    static class FakeStore implements EventStorePort {
        final Map<AccountId, List<LedgerEvent>> streams = new HashMap<>();

        public void append(AccountId id, long expectedVersion, List<LedgerEvent> events) {
            List<LedgerEvent> stream = streams.computeIfAbsent(id, k -> new ArrayList<>());
            long current = stream.isEmpty() ? 0 : stream.getLast().version();
            if (current != expectedVersion) throw new ConcurrencyConflictException(id, expectedVersion, current);
            for (LedgerEvent e : events) {
                movementUid(e).ifPresent(uid -> {
                    if (findByMovementUid(uid).isPresent()) throw new DuplicateMovementException(uid);
                });
            }
            stream.addAll(events);
        }

        public List<LedgerEvent> read(AccountId id) {
            return List.copyOf(streams.getOrDefault(id, List.of()));
        }

        public Optional<LedgerEvent> findByMovementUid(UUID uid) {
            return streams.values().stream()
                    .flatMap(List::stream)
                    .filter(e -> movementUid(e).map(uid::equals).orElse(false))
                    .findFirst();
        }

        private static Optional<UUID> movementUid(LedgerEvent e) {
            return switch (e) {
                case MoneyDeposited d -> Optional.of(d.movementUid());
                case MoneyWithdrawn w -> Optional.of(w.movementUid());
                case MovementRejected r -> Optional.of(r.movementUid());
                case AccountOpened a -> Optional.empty();
            };
        }
    }
}
