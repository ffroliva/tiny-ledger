package com.ffroliva.tinyledger.ledger.adapter.out.postgres;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.error.DuplicateMovementException;
import com.ffroliva.tinyledger.ledger.application.port.out.EventPage;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.LedgerEventType;
import com.ffroliva.tinyledger.ledger.domain.MoneyDeposited;
import com.ffroliva.tinyledger.ledger.domain.MoneyWithdrawn;
import com.ffroliva.tinyledger.ledger.domain.MovementEvent;
import com.ffroliva.tinyledger.ledger.domain.MovementRejected;
import com.ffroliva.tinyledger.shared.AccountId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

public class PostgresEventStore implements EventStorePort {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private RowMapper<LedgerEvent> eventRowMapper() {
        return (rs, rowNum) -> {
            String eventType = rs.getString("event_type");
            String payload = rs.getString("payload");
            return objectMapper.readValue(payload, LedgerEventType.classOf(eventType));
        };
    }

    private static UUID clientMovementUidOf(LedgerEvent event) {
        if (event instanceof MoneyDeposited d) return d.movementUid();
        if (event instanceof MoneyWithdrawn w) return w.movementUid();
        if (event instanceof MovementRejected r) return r.movementUid();
        return null;
    }

    /**
     * NESTED, not REQUIRED: the use case now runs in one transaction (ADR 0001) and recovers from
     * a losing idempotency race by reading the winning event back. A duplicate-key failure aborts
     * the whole Postgres transaction, so that read would fail too — the savepoint a nested
     * transaction takes is what lets the caller carry on after the conflict.
     */
    @Override
    @Transactional(propagation = Propagation.NESTED)
    public void append(AccountId streamId, long expectedVersion, List<? extends LedgerEvent> events) {
        long current = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_number), 0) FROM events WHERE aggregate_id = ?",
                Long.class,
                streamId.value());
        if (current != expectedVersion) {
            throw new ConcurrencyConflictException(streamId, expectedVersion, current);
        }

        long version = expectedVersion;
        String insertEventSql =
                "INSERT INTO events (aggregate_id, aggregate_type, event_type, sequence_number, payload, created_at, client_movement_uid) VALUES (?, 'Account', ?, ?, ?::jsonb, ?, ?)";

        for (LedgerEvent event : events) {
            version++;
            if (event.version() != version) {
                throw new IllegalArgumentException(
                        "Event version mismatch: expected " + version + " but got " + event.version());
            }

            String eventType = event.eventType();
            String payload = objectMapper.writeValueAsString(event);

            UUID clientMovementUid = clientMovementUidOf(event);
            Timestamp createdAt = Timestamp.from(event.occurredAt());

            try {
                jdbcTemplate.update(
                        insertEventSql,
                        streamId.value(),
                        eventType,
                        event.version(),
                        payload,
                        createdAt,
                        clientMovementUid);
            } catch (DuplicateKeyException e) {
                String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (message.contains("uk_events_client_movement_uid")) {
                    throw new DuplicateMovementException(clientMovementUid);
                }
                throw new ConcurrencyConflictException(streamId, expectedVersion, expectedVersion + 1);
            }
        }
    }

    @Override
    public List<LedgerEvent> read(AccountId streamId) {
        String sql = "SELECT event_type, payload FROM events WHERE aggregate_id = ? ORDER BY sequence_number ASC";
        return jdbcTemplate.query(sql, eventRowMapper(), streamId.value());
    }

    /**
     * Pages the whole log on {@code global_index} — the BIGSERIAL that has been on the table since
     * {@code 001-init-event-store.sql} and, until now, was written by the sequence and read by
     * nobody. See {@link EventStorePort#readAll} for why this is bounded to offline use.
     */
    @Override
    public EventPage readAll(long fromGlobalIndex, int limit) {
        String sql = "SELECT event_type, payload, global_index FROM events "
                + "WHERE global_index > ? ORDER BY global_index ASC LIMIT ?";
        List<IndexedEvent> rows = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new IndexedEvent(
                        objectMapper.readValue(
                                rs.getString("payload"), LedgerEventType.classOf(rs.getString("event_type"))),
                        rs.getLong("global_index")),
                fromGlobalIndex,
                limit);
        // An empty page must leave the cursor untouched, or a caller polling the tail rewinds.
        long next = rows.isEmpty() ? fromGlobalIndex : rows.getLast().globalIndex();
        return new EventPage(rows.stream().map(IndexedEvent::event).toList(), next);
    }

    private record IndexedEvent(LedgerEvent event, long globalIndex) {}

    @Override
    public Optional<MovementEvent> findByMovementUid(UUID movementUid) {
        String sql = "SELECT event_type, payload FROM events WHERE client_movement_uid = ?";
        return jdbcTemplate.query(sql, eventRowMapper(), movementUid).stream()
                .findFirst()
                .map(PostgresEventStore::asMovement);
    }

    /**
     * The one place this narrowing is a real check rather than a formality. {@code client_movement_uid}
     * is NULL for {@code AccountOpened} by construction ({@code append} writes it from the event), so a
     * row matching a movement UID that is not a movement means the table was written by something other
     * than this adapter — a fact about the data, not a case the type system can rule out. Failing loudly
     * beats returning {@code empty()}, which would look like "no such movement" and let the caller write
     * a duplicate.
     *
     * <p>Contrast the domain side: {@code MovementEvent} removed four switch arms that threw for cases
     * that could not occur. This one can.
     */
    private static MovementEvent asMovement(LedgerEvent event) {
        if (event instanceof MovementEvent movement) {
            return movement;
        }
        throw new IllegalStateException(
                "client_movement_uid is set on a " + event.getClass().getSimpleName());
    }
}
