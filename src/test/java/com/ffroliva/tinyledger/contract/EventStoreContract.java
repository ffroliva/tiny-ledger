package com.ffroliva.tinyledger.contract;

import static org.assertj.core.api.Assertions.*;

import com.ffroliva.tinyledger.ledger.application.error.*;
import com.ffroliva.tinyledger.ledger.application.port.out.EventPage;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.domain.*;
import com.ffroliva.tinyledger.shared.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

public interface EventStoreContract {
    EventStorePort store();

    Instant T = Instant.parse("2026-08-03T12:00:00Z");
    Currency GBP = Currency.getInstance("GBP");

    private AccountId newStream(EventStorePort store) {
        AccountId id = AccountId.random();
        store.append(id, 0, List.of(new AccountOpened(id, 1, T, "alice", "ACC-001", GBP)));
        return id;
    }

    private static MoneyDeposited deposit(AccountId id, long version, UUID uid) {
        Money amount = new Money(GBP, 1_000);
        return new MoneyDeposited(id, version, T, uid, amount, null, new Money(GBP, 1_000 * (version - 1)), null);
    }

    @Test
    default void appendsAndReadsInOrder() {
        EventStorePort store = store();
        AccountId id = newStream(store);
        store.append(id, 1, List.of(deposit(id, 2, UUID.randomUUID())));
        assertThat(store.read(id)).hasSize(2).isSortedAccordingTo(Comparator.comparingLong(LedgerEvent::version));
    }

    @Test
    default void rejectsStaleExpectedVersion() {
        EventStorePort store = store();
        AccountId id = newStream(store);
        List<LedgerEvent> atAStaleVersion = List.of(deposit(id, 2, UUID.randomUUID()));
        assertThatThrownBy(() -> store.append(id, 0, atAStaleVersion)).isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    default void movementUidIsGloballyUnique() {
        EventStorePort store = store();
        AccountId a = newStream(store);
        AccountId b = newStream(store);
        UUID uid = UUID.randomUUID();
        store.append(a, 1, List.of(deposit(a, 2, uid)));
        List<LedgerEvent> sameUidOtherStream = List.of(deposit(b, 2, uid));
        assertThatThrownBy(() -> store.append(b, 1, sameUidOtherStream)).isInstanceOf(DuplicateMovementException.class);
        assertThat(store.findByMovementUid(uid)).isPresent();
    }

    @Test
    default void concurrentAppendsYieldExactlyOneWinner() throws Exception {
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
                } catch (ConcurrencyConflictException _) {
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

    @Test
    default void readAllCrossesEveryStreamInAppendOrder() {
        EventStorePort store = store();
        AccountId a = newStream(store);
        AccountId b = newStream(store);
        store.append(a, 1, List.of(deposit(a, 2, UUID.randomUUID())));
        store.append(b, 1, List.of(deposit(b, 2, UUID.randomUUID())));

        EventPage page = store.readAll(0, 100);

        // read(streamId) can never answer this: the whole point is crossing streams.
        assertThat(page.events()).hasSize(4);
        assertThat(page.events().stream().map(LedgerEvent::accountId).distinct().toList())
                .containsExactlyInAnyOrder(a, b);
        assertThat(page.nextCursor()).isGreaterThan(0);
    }

    @Test
    default void readAllPagedOneAtATimeDropsNoEventAndRepeatsNone() {
        EventStorePort store = store();
        AccountId a = newStream(store);
        store.append(a, 1, List.of(deposit(a, 2, UUID.randomUUID())));
        store.append(a, 2, List.of(deposit(a, 3, UUID.randomUUID())));

        List<LedgerEvent> unpaged = store.readAll(0, 100).events();

        List<LedgerEvent> paged = new ArrayList<>();
        long cursor = 0;
        for (int guard = 0; guard < 50; guard++) {
            EventPage page = store.readAll(cursor, 1);
            if (page.events().isEmpty()) break;
            paged.addAll(page.events());
            cursor = page.nextCursor();
        }

        // A repeat, a skip and a reorder are three different faults; this refuses all three.
        assertThat(paged).containsExactlyElementsOf(unpaged);
    }

    @Test
    default void readAllFromTheEndReturnsNothingAndDoesNotMoveTheCursor() {
        EventStorePort store = store();
        newStream(store);
        EventPage all = store.readAll(0, 100);

        EventPage past = store.readAll(all.nextCursor(), 100);

        assertThat(past.events()).isEmpty();
        assertThat(past.nextCursor()).isEqualTo(all.nextCursor());
    }
}
