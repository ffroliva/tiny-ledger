package com.ffroliva.tinyledger.balance.adapter.out.inmemory;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Spec §6.2: TTL-bounded balance cache, expiry evaluated on read. The clock arrives as a
 * {@code Supplier<Instant>} rather than {@code ledger}'s {@code ClockPort} — {@code balance} may
 * only reach into {@code ledger::events}, so the composition root bridges {@code clock::now}.
 */
public class MapBalanceCache implements BalanceCachePort {
    private record Entry(BalanceView view, Instant expiresAt) {}

    private final Map<AccountId, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Supplier<Instant> clock;

    public MapBalanceCache(Duration ttl, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<BalanceView> get(AccountId accountId) {
        Entry entry = entries.get(accountId);
        if (entry == null) return Optional.empty();
        if (clock.get().isAfter(entry.expiresAt())) {
            entries.remove(accountId, entry);
            return Optional.empty();
        }
        return Optional.of(entry.view());
    }

    @Override
    public void put(AccountId accountId, BalanceView view) {
        entries.put(accountId, new Entry(view, clock.get().plus(ttl)));
    }

    @Override
    public void evict(AccountId accountId) {
        entries.remove(accountId);
    }
}
