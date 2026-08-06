package com.ffroliva.tinyledger.audit.adapter.out.postgres;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class PostgresAuditTrail implements AuditTrailPort {

    private static final String SELECT =
            "SELECT account_id, event_type, stream_version, payload, occurred_at, recorded_at, actor FROM audit_entries";

    private final JdbcTemplate jdbcTemplate;

    public PostgresAuditTrail(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(AuditEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO audit_entries (account_id, event_type, stream_version, payload, occurred_at, recorded_at, actor) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?) "
                        + "ON CONFLICT (account_id, stream_version) DO NOTHING",
                entry.accountId(),
                entry.eventType(),
                entry.streamVersion(),
                entry.payload(),
                Timestamp.from(entry.occurredAt()),
                Timestamp.from(entry.recordedAt()),
                entry.actor());
    }

    /** Keyset on the stream version, which is what "stream order" means for a single account (§7). */
    @Override
    public Page eventStream(UUID accountId, String cursor, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE account_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(accountId);
        if (cursor != null) {
            sql.append(" AND stream_version > ?");
            params.add(decodeVersion(cursor));
        }
        sql.append(" ORDER BY stream_version LIMIT ?");
        params.add(limit + 1);
        return page(
                jdbcTemplate.query(sql.toString(), entryRowMapper(), params.toArray()),
                limit,
                PostgresAuditTrail::versionCursor);
    }

    /**
     * Keyset on {@code (occurred_at, account_id, stream_version)} — unique by the trail's own uniqueness
     * constraint, so the cursor never needs the table's surrogate key.
     *
     * <p>ponytail: the scan is index-supported per account and a filtered sort across accounts; a
     * cross-account {@code (occurred_at DESC, account_id DESC, stream_version DESC)} index is the upgrade
     * if the unfiltered trail ever grows past what a compliance read can sort.
     */
    @Override
    public Page trail(TrailQuery query) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE true");
        List<Object> params = new ArrayList<>();
        if (query.accountId() != null) {
            sql.append(" AND account_id = ?");
            params.add(query.accountId());
        }
        if (query.from() != null) {
            sql.append(" AND occurred_at >= ?");
            params.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND occurred_at <= ?");
            params.add(Timestamp.from(query.to()));
        }
        if (query.cursor() != null) {
            TrailCursor after = TrailCursor.decode(query.cursor());
            sql.append(" AND (occurred_at, account_id, stream_version) < (?, ?::uuid, ?)");
            params.add(Timestamp.from(after.occurredAt()));
            params.add(after.accountId());
            params.add(after.streamVersion());
        }
        sql.append(" ORDER BY occurred_at DESC, account_id DESC, stream_version DESC LIMIT ?");
        params.add(query.limit() + 1);
        return page(
                jdbcTemplate.query(sql.toString(), entryRowMapper(), params.toArray()),
                query.limit(),
                TrailCursor::encode);
    }

    /** One row over the limit is how a page knows it is not the last one. */
    private static Page page(List<AuditEntry> rows, int limit, Function<AuditEntry, String> cursorOf) {
        if (rows.size() <= limit) return new Page(List.copyOf(rows), null);
        List<AuditEntry> entries = List.copyOf(rows.subList(0, limit));
        return new Page(entries, cursorOf.apply(entries.getLast()));
    }

    private static String versionCursor(AuditEntry entry) {
        return encode(String.valueOf(entry.streamVersion()));
    }

    private static long decodeVersion(String cursor) {
        try {
            return Long.parseLong(decode(cursor));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid event stream cursor", e);
        }
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    private RowMapper<AuditEntry> entryRowMapper() {
        return (rs, rowNum) -> new AuditEntry(
                rs.getObject("account_id", UUID.class),
                rs.getString("event_type"),
                rs.getLong("stream_version"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("payload"),
                rs.getString("actor"));
    }

    /** Opaque to the client (§7): URL-safe Base64, and never constructed by hand. */
    private record TrailCursor(Instant occurredAt, UUID accountId, long streamVersion) {

        static String encode(AuditEntry entry) {
            return PostgresAuditTrail.encode(
                    entry.occurredAt() + "|" + entry.accountId() + "|" + entry.streamVersion());
        }

        static TrailCursor decode(String cursor) {
            try {
                String[] parts = PostgresAuditTrail.decode(cursor).split("\\|");
                return new TrailCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]), Long.parseLong(parts[2]));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid audit trail cursor", e);
            }
        }
    }
}
