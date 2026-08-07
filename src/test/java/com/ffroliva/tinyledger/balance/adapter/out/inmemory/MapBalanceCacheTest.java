package com.ffroliva.tinyledger.balance.adapter.out.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.contract.BalanceCacheContract;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The first test this class has ever had. It is the balance cache for the entire {@code standalone} run
 * mode, and it reached {@code src/test} only as a collaborator inside {@code BalanceProjectorTest}, where
 * the subject under test is the projector — so none of its own semantics were asserted anywhere.
 *
 * <p>The shared port semantics come from {@link BalanceCacheContract} (§9.2b). What is here is the half
 * that cannot be shared: this adapter implements §6.2's TTL <em>itself</em>, evaluating expiry on read
 * against an injected clock, where Redis delegates it to key expiry. That hand-written expiry is the
 * riskiest code in the class and was the least covered.
 */
class MapBalanceCacheTest implements BalanceCacheContract {

    private Instant now = Instant.parse("2026-08-04T12:00:00Z");
    private final MapBalanceCache cache = new MapBalanceCache(Duration.ofSeconds(60), () -> now);

    @Override
    public BalanceCachePort cache() {
        return cache;
    }

    @Test
    void anEntryPastItsTtlReadsAsAMiss() {
        AccountId id = AccountId.random();
        cache.put(id, new BalanceView(id, Money.of("GBP", 5_000), now, 1));

        now = now.plusSeconds(61);

        assertThat(cache.get(id)).isEmpty();
    }

    /**
     * The boundary, and the twin of the test above — without it, a cache that expired everything
     * immediately would satisfy the expiry test completely. 60 s exactly is still live: the
     * implementation expires only when the clock is strictly *after* {@code expiresAt}.
     */
    @Test
    void anEntryAtExactlyItsTtlIsStillLive() {
        AccountId id = AccountId.random();
        BalanceView view = new BalanceView(id, Money.of("GBP", 5_000), now, 1);
        cache.put(id, view);

        now = now.plusSeconds(60);

        assertThat(cache.get(id)).contains(view);
    }

    /** Expiry is evaluated on read, so a stale entry must not survive as a leak once it has been seen. */
    @Test
    void anExpiredEntryIsDroppedRatherThanKept() {
        AccountId id = AccountId.random();
        cache.put(id, new BalanceView(id, Money.of("GBP", 5_000), now, 1));
        now = now.plusSeconds(61);
        assertThat(cache.get(id)).isEmpty();

        // Re-reading at a clock the entry would still be live under proves it was removed, not just
        // filtered — the implementation calls entries.remove(...) on the expiring read.
        now = now.minusSeconds(61);
        assertThat(cache.get(id)).isEmpty();
    }
}
