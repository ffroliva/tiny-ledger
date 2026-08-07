package com.ffroliva.tinyledger.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * §9.2b for {@code BalanceCachePort}, the third and last outbound port with two implementations. With
 * {@link EventStoreContract} and {@link BalanceProjectionContract} this completes the rule: every such
 * port now has one suite that both adapters run.
 *
 * <p><strong>The gap here was worse than the projection's.</strong> {@code RedisBalanceCacheIT} covered
 * the round trip, the miss and the evict. {@code MapBalanceCache} — which is the cache for the <em>whole
 * of {@code standalone}</em> — had <b>no test of its own at all</b>; it appeared in {@code src/test} only
 * as a collaborator inside {@code BalanceProjectorTest}, where the subject is the projector. Verified
 * differentially before claiming it, per `AGENTS.md` trap 7: the same search returns 2 files for
 * {@code RedisBalanceCache} and 1 for {@code MapBalanceCache}, and that 1 is the projector's test.
 *
 * <p><strong>TTL is deliberately not here.</strong> §6.2's 60 seconds is honoured by two mechanisms that
 * cannot share an assertion: Redis expires the key itself (asserted on the key's TTL metadata) while
 * {@code MapBalanceCache} evaluates expiry on read against an injected clock. A shared test would have to
 * either wait sixty seconds or assert nothing meaningful. Each adapter keeps its own, and
 * {@code MapBalanceCacheTest} adds the one it never had.
 */
public interface BalanceCacheContract {

    Instant AS_OF = Instant.parse("2026-08-04T12:00:00Z");

    BalanceCachePort cache();

    @Test
    default void getIsEmptyForAnUncachedAccount() {
        assertThat(cache().get(AccountId.random())).isEmpty();
    }

    @Test
    default void putThenGetRoundTripsTheView() {
        AccountId id = AccountId.random();
        BalanceView view = new BalanceView(id, Money.of("GBP", 5_000), AS_OF, 7);

        cache().put(id, view);

        assertThat(cache().get(id)).contains(view);
    }

    @Test
    default void evictRemovesTheEntry() {
        AccountId id = AccountId.random();
        cache().put(id, new BalanceView(id, Money.of("GBP", 1), AS_OF, 1));

        cache().evict(id);

        assertThat(cache().get(id)).isEmpty();
    }

    /**
     * Neither adapter tested this, and it is the one the projector depends on: {@code BalanceProjector}
     * evicts rather than overwrites, but the read path repopulates, so a cache that kept the first value
     * for a key would serve a stale balance for a full TTL after a write. "Last write wins" is the
     * property that makes the eviction strategy safe.
     */
    @Test
    default void putOverwritesAPreviousValueForTheSameAccount() {
        AccountId id = AccountId.random();
        cache().put(id, new BalanceView(id, Money.of("GBP", 100), AS_OF, 1));

        BalanceView newer = new BalanceView(id, Money.of("GBP", 900), AS_OF.plusSeconds(1), 2);
        cache().put(id, newer);

        assertThat(cache().get(id)).contains(newer);
    }
}
