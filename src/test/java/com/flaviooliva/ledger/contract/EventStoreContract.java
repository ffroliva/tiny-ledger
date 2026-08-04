package com.flaviooliva.ledger.contract;

import static org.assertj.core.api.Assertions.*;

import com.flaviooliva.ledger.ledger.application.error.*;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

public abstract class EventStoreContract {
    protected abstract EventStorePort store();

    private static final Instant T = Instant.parse("2026-08-03T12:00:00Z");
    private static final Currency GBP = Currency.getInstance("GBP");

    private AccountId newStream(EventStorePort store) {
        AccountId id = AccountId.random();
        store.append(id, 0, List.of(new AccountOpened(id, 1, T, "alice", "ACC-001", GBP)));
        return id;
    }

    private static MoneyDeposited deposit(AccountId id, long version, UUID uid) {
        Money amount = new Money(GBP, 1_000);
        return new MoneyDeposited(id, version, T, uid, amount, null, new Money(GBP, 1_000 * (version - 1)));
    }

    @Test
    void appendsAndReadsInOrder() {
        EventStorePort store = store();
        AccountId id = newStream(store);
        store.append(id, 1, List.of(deposit(id, 2, UUID.randomUUID())));
        assertThat(store.read(id)).hasSize(2).isSortedAccordingTo(Comparator.comparingLong(LedgerEvent::version));
    }

    @Test
    void rejectsStaleExpectedVersion() {
        EventStorePort store = store();
        AccountId id = newStream(store);
        assertThatThrownBy(() -> store.append(id, 0, List.of(deposit(id, 2, UUID.randomUUID()))))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void movementUidIsGloballyUnique() {
        EventStorePort store = store();
        AccountId a = newStream(store);
        AccountId b = newStream(store);
        UUID uid = UUID.randomUUID();
        store.append(a, 1, List.of(deposit(a, 2, uid)));
        assertThatThrownBy(() -> store.append(b, 1, List.of(deposit(b, 2, uid))))
                .isInstanceOf(DuplicateMovementException.class);
        assertThat(store.findByMovementUid(uid)).isPresent();
    }

    @Test
    void concurrentAppendsYieldExactlyOneWinner() throws Exception {
        EventStorePort store = store();
        AccountId id = newStream(store);
        int writers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            tasks.add(() -> {
                start.await();
                try {
                    store.append(id, 1, List.of(deposit(id, 2, UUID.randomUUID())));
                    return true;
                } catch (ConcurrencyConflictException e) {
                    return false;
                }
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<Boolean>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            long winners = futures.stream()
                    .filter(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .count();
            assertThat(winners).isEqualTo(1);
        }
        assertThat(store.read(id)).hasSize(2); // contiguous, no gaps
    }
}
