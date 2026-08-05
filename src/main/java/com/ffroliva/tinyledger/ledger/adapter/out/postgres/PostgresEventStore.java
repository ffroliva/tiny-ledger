package com.ffroliva.tinyledger.ledger.adapter.out.postgres;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.error.DuplicateMovementException;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.MoneyDeposited;
import com.ffroliva.tinyledger.ledger.domain.MoneyWithdrawn;
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
            return switch (eventType) {
                case "AccountOpened" -> objectMapper.readValue(payload, AccountOpened.class);
                case "MoneyDeposited" -> objectMapper.readValue(payload, MoneyDeposited.class);
                case "MoneyWithdrawn" -> objectMapper.readValue(payload, MoneyWithdrawn.class);
                case "MovementRejected" -> objectMapper.readValue(payload, MovementRejected.class);
                default -> throw new IllegalStateException("Unknown event type: " + eventType);
            };
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
    public void append(AccountId streamId, long expectedVersion, List<LedgerEvent> events) {
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

            String eventType = event.getClass().getSimpleName();
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

    @Override
    public Optional<LedgerEvent> findByMovementUid(UUID movementUid) {
        String sql = "SELECT event_type, payload FROM events WHERE client_movement_uid = ?";
        return jdbcTemplate.query(sql, eventRowMapper(), movementUid).stream().findFirst();
    }
}
