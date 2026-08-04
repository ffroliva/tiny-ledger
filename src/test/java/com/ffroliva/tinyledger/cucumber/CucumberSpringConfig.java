package com.ffroliva.tinyledger.cucumber;

import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.notification.application.Notification;
import com.ffroliva.tinyledger.notification.application.NotificationPort;
import com.ffroliva.tinyledger.shared.AccountId;
import io.cucumber.spring.CucumberContextConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The one Spring context every scenario shares: the real application on a random port.
 *
 * <p>The catalogue's timing rows need seams the production code must not know about, so each one is a
 * {@code @Primary} decorator registered <em>here</em>, in test configuration — the application wires its own
 * beans unchanged and never consults test code (spec §4.5).
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CucumberSpringConfig {

    @TestConfiguration
    static class Seams {
        /** §9.3 E1–E5: the pausable projector {@code LedgerEventsListener} is handed instead of the plain one. */
        @Bean
        @Primary
        PausableListenerGate pausableListenerGate(BalanceProjectionPort projection, BalanceCachePort cache) {
            return new PausableListenerGate(projection, cache);
        }

        @Bean
        @Primary
        RacingEventStore racingEventStore(@Qualifier("eventStore") EventStorePort delegate) {
            return new RacingEventStore(delegate);
        }

        /**
         * §9.3 P8. The production adapter writes a structured log line, which no black-box step can read;
         * this records the same {@link Notification} the rules produced so the row can assert it.
         */
        @Bean
        @Primary
        RecordedNotifications recordedNotifications() {
            return new RecordedNotifications();
        }

        /**
         * The feed sorts and pages on millisecond granularity, so two movements inside one millisecond have
         * no defined order and "newest first" (§9.3 P4) would be a coin toss. This hands every event its own
         * millisecond — real time whenever real time has already moved on, and never a sleep (§9.3 method).
         */
        @Bean
        @Primary
        ClockPort strictlyIncreasingClock() {
            AtomicReference<Instant> last = new AtomicReference<>(Instant.EPOCH);
            return () -> last.updateAndGet(previous -> {
                Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                Instant floor = previous.plusMillis(1);
                return now.isBefore(floor) ? floor : now;
            });
        }
    }

    /** The notification trail P8 asserts against, keyed the way the row names it — by movement UID. */
    public static final class RecordedNotifications implements NotificationPort {

        private final List<Notification> records = new CopyOnWriteArrayList<>();

        @Override
        public void record(Notification notification) {
            records.add(notification);
        }

        public List<Notification> forMovement(UUID movementUid) {
            return records.stream()
                    .filter(record -> record.movementUid().equals(movementUid))
                    .toList();
        }
    }

    /**
     * Spec §9.3 N3. The contract carries no {@code expectedVersion} (§6.3) — concurrency is internal — so
     * "two writers at the same version" cannot be stated in a request. It is stated here instead: while armed,
     * the store holds each reader at a barrier until every entrant has read, which makes them share one
     * expected version by construction. Without it the write path's read-to-append window is microseconds wide
     * and the collision would be a coin toss, which is a flaky test rather than a specification.
     */
    public static final class RacingEventStore implements EventStorePort {

        private final EventStorePort delegate;
        private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>();
        private final AtomicInteger entrants = new AtomicInteger();

        RacingEventStore(EventStorePort delegate) {
            this.delegate = delegate;
        }

        public void armRace(int writers) {
            barrier.set(new CyclicBarrier(writers));
            entrants.set(writers);
        }

        public void disarm() {
            barrier.set(null);
            entrants.set(0);
        }

        @Override
        public List<LedgerEvent> read(AccountId streamId) {
            List<LedgerEvent> history = delegate.read(streamId);
            CyclicBarrier armed = barrier.get();
            if (armed != null && entrants.getAndDecrement() > 0) {
                try {
                    armed.await(10, TimeUnit.SECONDS);
                } catch (Exception interrupted) {
                    throw new IllegalStateException("race barrier never tripped", interrupted);
                }
            }
            return history;
        }

        @Override
        public void append(AccountId streamId, long expectedVersion, List<LedgerEvent> events) {
            delegate.append(streamId, expectedVersion, events);
        }

        @Override
        public Optional<LedgerEvent> findByMovementUid(UUID movementUid) {
            return delegate.findByMovementUid(movementUid);
        }
    }
}
