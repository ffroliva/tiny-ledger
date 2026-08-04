package com.flaviooliva.ledger.balance.adapter.out.inmemory;

import com.flaviooliva.ledger.balance.application.port.in.*;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Spec §4.4: the standalone read model. Idempotent on (accountId, version) and tolerant of
 * out-of-order delivery — an event ahead of the stream is buffered until the gap fills (E4/E5).
 */
public class InMemoryBalanceProjection implements BalanceProjectionPort {

    /**
     * §9.2b: Postgres compares {@code uuid} bytewise and unsigned, and it is the reference for both
     * run modes. {@link UUID#compareTo} reads the two halves as signed longs, so it puts {@code 80…}
     * <em>below</em> {@code 7f…} — the opposite answer whenever the uid is the only tie-break left.
     */
    static final Comparator<UUID> POSTGRES_UUID_ORDER = Comparator.comparing(
                    UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private static final Comparator<TransactionView> NEWEST_FIRST = Comparator.comparingLong(
                    (TransactionView tx) -> tx.transactionTime().toEpochMilli())
            .thenComparing(TransactionView::transactionUid, POSTGRES_UUID_ORDER)
            .reversed();

    private final Map<AccountId, Long> appliedVersion = new ConcurrentHashMap<>();
    private final Map<AccountId, TreeMap<Long, LedgerEvent>> buffered = new ConcurrentHashMap<>();
    private final Map<AccountId, BalanceView> balances = new ConcurrentHashMap<>();
    private final Map<AccountId, List<TransactionView>> feed = new ConcurrentHashMap<>();
    private final Map<AccountId, AccountView> accounts = new ConcurrentHashMap<>();

    // ponytail: one lock for the whole projection — per-account locks only if write throughput matters.
    @Override
    public synchronized void apply(LedgerEvent event) {
        AccountId id = event.accountId();
        long applied = appliedVersion.getOrDefault(id, 0L);
        if (event.version() <= applied) return; // E4: redelivery of something already folded in

        TreeMap<Long, LedgerEvent> pending = buffered.computeIfAbsent(id, k -> new TreeMap<>());
        pending.put(event.version(), event); // E5: hold ahead-of-stream events, keyed so re-delivery collapses

        LedgerEvent next;
        while ((next = pending.remove(applied + 1)) != null) {
            project(next);
            applied++;
        }
        appliedVersion.put(id, applied);
    }

    private void project(LedgerEvent event) {
        switch (event) {
            case AccountOpened e -> {
                accounts.put(
                        e.accountId(),
                        new AccountView(e.accountId(), e.name(), e.owner(), e.currency(), e.occurredAt()));
                balances.put(
                        e.accountId(),
                        new BalanceView(e.accountId(), new Money(e.currency(), 0), e.occurredAt(), e.version()));
            }
            case MoneyDeposited e ->
                settle(
                        new TransactionView(
                                e.movementUid(),
                                e.accountId(),
                                MovementType.DEPOSIT,
                                TransactionView.IN,
                                e.amount(),
                                e.balanceAfter(),
                                TransactionView.SETTLED,
                                e.occurredAt(),
                                e.occurredAt(),
                                e.reference()),
                        e.version());
            case MoneyWithdrawn e ->
                settle(
                        new TransactionView(
                                e.movementUid(),
                                e.accountId(),
                                MovementType.WITHDRAWAL,
                                TransactionView.OUT,
                                e.amount(),
                                e.balanceAfter(),
                                TransactionView.SETTLED,
                                e.occurredAt(),
                                e.occurredAt(),
                                e.reference()),
                        e.version());
            // moves no money and never reaches the feed — the raw stream is the auditor's view.
            // The staleness markers still advance so a reader can tell the projection consumed it.
            case MovementRejected e ->
                balances.computeIfPresent(
                        e.accountId(), (id, prev) -> new BalanceView(id, prev.amount(), e.occurredAt(), e.version()));
        }
    }

    private void settle(TransactionView tx, long version) {
        balances.put(tx.accountId(), new BalanceView(tx.accountId(), tx.balanceAfter(), tx.transactionTime(), version));
        feed.computeIfAbsent(tx.accountId(), k -> new CopyOnWriteArrayList<>()).add(tx);
    }

    @Override
    public Optional<BalanceView> balance(AccountId accountId) {
        return Optional.ofNullable(balances.get(accountId));
    }

    @Override
    public HistoryPage history(AccountId accountId, HistoryQuery query) {
        int limit = Math.max(1, query.limit());
        Cursor after = query.cursor() == null ? null : Cursor.decode(query.cursor());
        List<TransactionView> matching = feed.getOrDefault(accountId, List.of()).stream()
                .filter(tx -> within(tx, query))
                .filter(tx -> after == null || after.precedes(tx))
                .sorted(NEWEST_FIRST)
                .toList();

        List<TransactionView> page = matching.subList(0, Math.min(limit, matching.size()));
        return new HistoryPage(List.copyOf(page), matching.size() > limit ? Cursor.encode(page.getLast()) : null);
    }

    @Override
    public List<AccountView> accountsOwnedBy(String owner) {
        return accounts.values().stream()
                .filter(a -> a.owner().equals(owner))
                // Same tie-break as Postgres's "ORDER BY created_at, account_id" (§9.2b).
                .sorted(Comparator.comparing(AccountView::createdAt)
                        .thenComparing(a -> a.accountId().value(), POSTGRES_UUID_ORDER))
                .toList();
    }

    @Override
    public Optional<AccountView> account(AccountId accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    private static boolean within(TransactionView tx, HistoryQuery query) {
        // Millisecond granularity throughout (§9.2b), bounds included: Postgres stores transaction_time
        // truncated to millis and truncates these same bounds, so comparing finer precision here would
        // answer differently in the two run modes for one bound — a row inside the boundary millisecond
        // would be filtered out in standalone and kept in full.
        long at = tx.transactionTime().toEpochMilli();
        return (query.minTransactionTimestamp() == null
                        || at >= query.minTransactionTimestamp().toEpochMilli())
                && (query.maxTransactionTimestamp() == null
                        || at <= query.maxTransactionTimestamp().toEpochMilli());
    }

    /** Keyset position. Millisecond granularity throughout, so the sort and the cursor agree. */
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

        /** True when {@code tx} sits strictly after this position in newest-first order. */
        boolean precedes(TransactionView tx) {
            long at = tx.transactionTime().toEpochMilli();
            return at < epochMilli
                    || (at == epochMilli && POSTGRES_UUID_ORDER.compare(tx.transactionUid(), transactionUid) < 0);
        }
    }
}
