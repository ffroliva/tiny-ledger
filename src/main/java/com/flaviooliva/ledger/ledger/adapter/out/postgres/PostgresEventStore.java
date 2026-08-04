package com.flaviooliva.ledger.ledger.adapter.out.postgres;

import com.flaviooliva.ledger.ledger.application.error.ConcurrencyConflictException;
import com.flaviooliva.ledger.ledger.application.error.DuplicateMovementException;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.domain.AccountOpened;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.ledger.domain.MoneyDeposited;
import com.flaviooliva.ledger.ledger.domain.MoneyWithdrawn;
import com.flaviooliva.ledger.ledger.domain.MovementRejected;
import com.flaviooliva.ledger.shared.AccountId;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
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
            try {
                return switch (eventType) {
                    case "AccountOpened" -> objectMapper.readValue(payload, AccountOpened.class);
                    case "MoneyDeposited" -> objectMapper.readValue(payload, MoneyDeposited.class);
                    case "MoneyWithdrawn" -> objectMapper.readValue(payload, MoneyWithdrawn.class);
                    case "MovementRejected" -> objectMapper.readValue(payload, MovementRejected.class);
                    default -> throw new IllegalStateException("Unknown event type: " + eventType);
                };
            } catch (JacksonException e) {
                throw new RuntimeException("Failed to deserialize event payload", e);
            }
        };
    }

    private static UUID clientMovementUidOf(LedgerEvent event) {
        if (event instanceof MoneyDeposited d) return d.movementUid();
        if (event instanceof MoneyWithdrawn w) return w.movementUid();
        if (event instanceof MovementRejected r) return r.movementUid();
        return null;
    }

    @Override
    @Transactional
    public void append(AccountId streamId, long expectedVersion, List<LedgerEvent> events) {
        Long currentVersion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_number), 0) FROM events WHERE aggregate_id = ?",
                Long.class,
                streamId.value()
        );
        long current = currentVersion != null ? currentVersion : 0;
        if (current != expectedVersion) {
            throw new ConcurrencyConflictException(streamId, expectedVersion, current);
        }

        long version = expectedVersion;
        String insertEventSql = "INSERT INTO events (aggregate_id, aggregate_type, event_type, sequence_number, payload, created_at, client_movement_uid) VALUES (?, 'Account', ?, ?, ?::jsonb, ?, ?)";
        String insertOutboxSql = "INSERT INTO event_outbox (id, event_id, aggregate_id, event_type, payload, created_at, processed) VALUES (?, ?, ?, ?, ?::jsonb, ?, false)";

        for (LedgerEvent event : events) {
            version++;
            if (event.version() != version) {
                throw new IllegalArgumentException("Event version mismatch: expected " + version + " but got " + event.version());
            }

            String eventType = event.getClass().getSimpleName();
            String payload;
            try {
                payload = objectMapper.writeValueAsString(event);
            } catch (JacksonException e) {
                throw new RuntimeException("Failed to serialize event", e);
            }

            UUID clientMovementUid = clientMovementUidOf(event);
            Timestamp createdAt = Timestamp.from(event.occurredAt());

            try {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(insertEventSql, new String[]{"id"});
                    ps.setObject(1, streamId.value());
                    ps.setString(2, eventType);
                    ps.setLong(3, event.version());
                    ps.setString(4, payload);
                    ps.setTimestamp(5, createdAt);
                    ps.setObject(6, clientMovementUid);
                    return ps;
                }, keyHolder);

                Long eventId = keyHolder.getKeyAs(Long.class);

                jdbcTemplate.update(
                        insertOutboxSql,
                        UUID.randomUUID(),
                        eventId,
                        streamId.value(),
                        eventType,
                        payload,
                        createdAt
                );
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
        List<LedgerEvent> results = jdbcTemplate.query(sql, eventRowMapper(), movementUid);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }
}
