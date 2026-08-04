package com.flaviooliva.ledger.balance.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.flaviooliva.ledger.balance.application.port.in.BalanceView;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisBalanceCacheIT extends AbstractIntegrationTest {

    @Autowired
    private BalanceCachePort cache;

    @Autowired
    private StringRedisTemplate redis;

    private static final Instant AS_OF = Instant.parse("2026-08-04T12:00:00Z");

    @Test
    void putThenGetRoundTripsTheView() {
        AccountId id = AccountId.random();
        BalanceView view = new BalanceView(id, Money.of("GBP", 5000), AS_OF, 7);

        cache.put(id, view);

        assertThat(cache.get(id)).contains(view);
    }

    @Test
    void getIsEmptyForAnUncachedAccount() {
        assertThat(cache.get(AccountId.random())).isEmpty();
    }

    @Test
    void evictRemovesTheEntry() {
        AccountId id = AccountId.random();
        cache.put(id, new BalanceView(id, Money.of("GBP", 1), AS_OF, 1));

        cache.evict(id);

        assertThat(cache.get(id)).isEmpty();
    }

    @Test
    void entriesCarryTheSixtySecondTtl() {
        AccountId id = AccountId.random();
        cache.put(id, new BalanceView(id, Money.of("GBP", 1), AS_OF, 1));

        Long ttlSeconds = redis.getExpire("balance:" + id.value());

        assertThat(ttlSeconds).isBetween(1L, 60L);
    }

    @Test
    void corruptPayloadReadsAsAMiss() {
        AccountId id = AccountId.random();
        redis.opsForValue().set("balance:" + id.value(), "not-json");

        Optional<BalanceView> hit = cache.get(id);

        assertThat(hit).isEmpty();
    }
}
