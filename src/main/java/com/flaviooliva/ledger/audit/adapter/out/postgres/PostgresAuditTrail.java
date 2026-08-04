package com.flaviooliva.ledger.audit.adapter.out.postgres;

import com.flaviooliva.ledger.audit.application.port.out.AuditTrailPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class PostgresAuditTrail implements AuditTrailPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresAuditTrail(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(AuditEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO audit_entries (account_id, event_type, stream_version, payload, occurred_at, recorded_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?) "
                        + "ON CONFLICT (account_id, stream_version) DO NOTHING",
                entry.accountId(),
                entry.eventType(),
                entry.streamVersion(),
                entry.payload(),
                Timestamp.from(entry.occurredAt()),
                Timestamp.from(Instant.now()));
    }

    @Override
    public List<AuditEntry> entriesFor(UUID accountId) {
        return jdbcTemplate.query(
                "SELECT account_id, event_type, stream_version, payload, occurred_at FROM audit_entries "
                        + "WHERE account_id = ? ORDER BY stream_version",
                entryRowMapper(),
                accountId);
    }

    private RowMapper<AuditEntry> entryRowMapper() {
        return (rs, rowNum) -> new AuditEntry(
                rs.getObject("account_id", UUID.class),
                rs.getString("event_type"),
                rs.getLong("stream_version"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("payload"));
    }
}
