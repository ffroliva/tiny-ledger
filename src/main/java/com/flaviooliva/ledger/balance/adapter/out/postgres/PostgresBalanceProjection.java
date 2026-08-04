package com.flaviooliva.ledger.balance.adapter.out.postgres;

import com.flaviooliva.ledger.balance.application.port.in.*;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §4.4 read model under the {@code full} profile. Idempotent on (accountId, streamVersion).
 *
 * <p>Unlike {@link com.flaviooliva.ledger.balance.adapter.out.inmemory.InMemoryBalanceProjection}
 * it does not buffer ahead-of-stream events: the outbox relay replays a whole batch on failure, so
 * a gap surfaces as a rolled-back FK violation and is retried in order rather than reordered here.
 */
public class PostgresBalanceProjection implements BalanceProjectionPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresBalanceProjection(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void apply(LedgerEvent event) {
        switch (event) {
            case AccountOpened e -> {
                // The row may not exist yet — UPSERT handles both insert and idempotent replay
                jdbcTemplate.update(
                        "INSERT INTO balance_projections (account_id, account_name, owner, currency, balance_minor_units, stream_version, as_of, created_at) "
                                + "VALUES (?, ?, ?, ?, 0, ?, ?, ?) "
                                + "ON CONFLICT (account_id) DO UPDATE SET stream_version = GREATEST(balance_projections.stream_version, EXCLUDED.stream_version), as_of = EXCLUDED.as_of",
                        e.accountId().value(),
                        e.name(),
                        e.owner(),
                        e.currency().getCurrencyCode(),
                        e.version(),
                        Timestamp.from(e.occurredAt()),
                        Timestamp.from(e.occurredAt()));
            }
            case MoneyDeposited e -> {
                if (isAlreadyApplied(e.accountId(), e.version())) return;
                updateBalance(e.accountId(), e.balanceAfter(), e.version(), e.occurredAt());
                insertHistory(e.movementUid(), e.accountId(), MovementType.DEPOSIT, TransactionView.IN,
                        e.amount(), e.balanceAfter(), e.occurredAt(), e.reference());
            }
            case MoneyWithdrawn e -> {
                if (isAlreadyApplied(e.accountId(), e.version())) return;
                updateBalance(e.accountId(), e.balanceAfter(), e.version(), e.occurredAt());
                insertHistory(e.movementUid(), e.accountId(), MovementType.WITHDRAWAL, TransactionView.OUT,
                        e.amount(), e.balanceAfter(), e.occurredAt(), e.reference());
            }
            case MovementRejected e -> {
                if (isAlreadyApplied(e.accountId(), e.version())) return;
                jdbcTemplate.update(
                        "UPDATE balance_projections SET stream_version = ?, as_of = ? WHERE account_id = ?",
                        e.version(), Timestamp.from(e.occurredAt()), e.accountId().value());
            }
        }
    }

    private boolean isAlreadyApplied(AccountId accountId, long version) {
        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT stream_version FROM balance_projections WHERE account_id = ?",
                Long.class, accountId.value());
        return !versions.isEmpty() && versions.getFirst() >= version;
    }

    private void updateBalance(AccountId accountId, Money balanceAfter, long version, Instant asOf) {
        jdbcTemplate.update(
                "UPDATE balance_projections SET balance_minor_units = ?, stream_version = ?, as_of = ?, currency = ? WHERE account_id = ?",
                balanceAfter.minorUnits(), version, Timestamp.from(asOf),
                balanceAfter.currency().getCurrencyCode(), accountId.value());
    }

    private void insertHistory(UUID movementUid, AccountId accountId, MovementType type, String direction,
                               Money amount, Money balanceAfter, Instant time, String reference) {
        jdbcTemplate.update(
                "INSERT INTO account_history (transaction_uid, account_id, movement_type, direction, "
                        + "amount_currency, amount_minor_units, balance_after_currency, balance_after_minor_units, "
                        + "status, transaction_time, settlement_time, reference) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?, ?, ?) "
                        + "ON CONFLICT (account_id, transaction_uid) DO NOTHING",
                movementUid, accountId.value(), type.name(), direction,
                amount.currency().getCurrencyCode(), amount.minorUnits(),
                balanceAfter.currency().getCurrencyCode(), balanceAfter.minorUnits(),
                Timestamp.from(time), Timestamp.from(time), reference);
    }

    @Override
    public Optional<BalanceView> balance(AccountId accountId) {
        List<BalanceView> results = jdbcTemplate.query(
                "SELECT account_id, currency, balance_minor_units, as_of, stream_version FROM balance_projections WHERE account_id = ?",
                balanceRowMapper(), accountId.value());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public HistoryPage history(AccountId accountId, HistoryQuery query) {
        int limit = Math.max(1, query.limit());
        Cursor after = query.cursor() == null ? null : Cursor.decode(query.cursor());

        StringBuilder sql = new StringBuilder(
                "SELECT transaction_uid, account_id, movement_type, direction, "
                        + "amount_currency, amount_minor_units, balance_after_currency, balance_after_minor_units, "
                        + "status, transaction_time, settlement_time, reference "
                        + "FROM account_history WHERE account_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(accountId.value());

        if (query.minTransactionTimestamp() != null) {
            sql.append(" AND transaction_time >= ?");
            params.add(Timestamp.from(query.minTransactionTimestamp()));
        }
        if (query.maxTransactionTimestamp() != null) {
            sql.append(" AND transaction_time <= ?");
            params.add(Timestamp.from(query.maxTransactionTimestamp()));
        }
        if (after != null) {
            sql.append(" AND (transaction_time < ? OR (transaction_time = ? AND transaction_uid < ?))");
            Timestamp cursorTs = Timestamp.from(Instant.ofEpochMilli(after.epochMilli()));
            params.add(cursorTs);
            params.add(cursorTs);
            params.add(after.transactionUid());
        }

        sql.append(" ORDER BY transaction_time DESC, transaction_uid DESC LIMIT ?");
        params.add(limit + 1);

        List<TransactionView> results = jdbcTemplate.query(sql.toString(), transactionRowMapper(), params.toArray());

        boolean hasNext = results.size() > limit;
        List<TransactionView> page = hasNext ? results.subList(0, limit) : results;
        String nextCursor = hasNext ? Cursor.encode(page.getLast()) : null;
        return new HistoryPage(List.copyOf(page), nextCursor);
    }

    @Override
    public List<AccountView> accountsOwnedBy(String owner) {
        return jdbcTemplate.query(
                "SELECT account_id, account_name, owner, currency, created_at FROM balance_projections WHERE owner = ? ORDER BY created_at, account_id",
                accountRowMapper(), owner);
    }

    @Override
    public Optional<AccountView> account(AccountId accountId) {
        List<AccountView> results = jdbcTemplate.query(
                "SELECT account_id, account_name, owner, currency, created_at FROM balance_projections WHERE account_id = ?",
                accountRowMapper(), accountId.value());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    private RowMapper<BalanceView> balanceRowMapper() {
        return (rs, rowNum) -> new BalanceView(
                AccountId.of(rs.getString("account_id")),
                new Money(Currency.getInstance(rs.getString("currency")), rs.getLong("balance_minor_units")),
                rs.getTimestamp("as_of").toInstant(),
                rs.getLong("stream_version"));
    }

    private RowMapper<TransactionView> transactionRowMapper() {
        return (rs, rowNum) -> new TransactionView(
                rs.getObject("transaction_uid", UUID.class),
                AccountId.of(rs.getString("account_id")),
                MovementType.valueOf(rs.getString("movement_type")),
                rs.getString("direction"),
                new Money(Currency.getInstance(rs.getString("amount_currency")), rs.getLong("amount_minor_units")),
                new Money(Currency.getInstance(rs.getString("balance_after_currency")), rs.getLong("balance_after_minor_units")),
                rs.getString("status"),
                rs.getTimestamp("transaction_time").toInstant(),
                rs.getTimestamp("settlement_time").toInstant(),
                rs.getString("reference"));
    }

    private RowMapper<AccountView> accountRowMapper() {
        return (rs, rowNum) -> new AccountView(
                AccountId.of(rs.getString("account_id")),
                rs.getString("account_name"),
                rs.getString("owner"),
                Currency.getInstance(rs.getString("currency")),
                rs.getTimestamp("created_at").toInstant());
    }

    /** Keyset cursor matching InMemoryBalanceProjection's encoding. */
    private record Cursor(long epochMilli, UUID transactionUid) {
        static String encode(TransactionView tx) {
            String raw = tx.transactionTime().toEpochMilli() + ":" + tx.transactionUid();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int separator = raw.indexOf(':');
                return new Cursor(
                        Long.parseLong(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("invalid history cursor", e);
            }
        }
    }
}
