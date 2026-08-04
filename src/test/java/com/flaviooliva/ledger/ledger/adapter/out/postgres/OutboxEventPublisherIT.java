package com.flaviooliva.ledger.ledger.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.domain.AccountOpened;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Import(OutboxEventPublisherIT.TestEventListener.class)
class OutboxEventPublisherIT extends AbstractIntegrationTest {

    @Autowired
    private EventStorePort store;

    @Autowired
    private OutboxEventPublisher outboxPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEventListener testEventListener;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events, event_outbox RESTART IDENTITY CASCADE");
        testEventListener.clear();
    }

    @Test
    void outboxRecordsAreWrittenAtomicallyAndPublishedByOutboxPublisher() {
        AccountId id = AccountId.random();
        LedgerEvent event = new AccountOpened(id, 1, Instant.parse("2026-08-04T12:00:00Z"), "alice", "ACC-123", Currency.getInstance("GBP"));

        store.append(id, 0, List.of(event));

        // Verify outbox entry exists and is unprocessed
        Integer pendingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE processed = false", Integer.class);
        assertThat(pendingCount).isEqualTo(1);

        // Run outbox publisher relay
        outboxPublisher.publishPendingEvents();

        // Verify event was published to Spring EventListener
        assertThat(testEventListener.events()).hasSize(1);
        assertThat(testEventListener.events().getFirst()).isInstanceOf(AccountOpened.class);

        // Verify outbox entry is now marked processed
        Integer processedCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE processed = true", Integer.class);
        assertThat(processedCount).isEqualTo(1);
    }

    @TestConfiguration
    static class TestEventListener {
        private final List<LedgerEvent> received = new ArrayList<>();

        @EventListener
        public synchronized void on(LedgerEvent event) {
            received.add(event);
        }

        public synchronized List<LedgerEvent> events() {
            return List.copyOf(received);
        }

        public synchronized void clear() {
            received.clear();
        }
    }
}
