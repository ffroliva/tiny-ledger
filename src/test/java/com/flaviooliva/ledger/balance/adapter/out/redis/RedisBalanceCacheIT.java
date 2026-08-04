package com.flaviooliva.ledger.balance.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.flaviooliva.ledger.balance.application.port.in.BalanceView;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.shared.AccountId;
import com.flaviooliva.ledger.shared.Money;
import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
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
    void corruptPayloadReadsAsAMissAndSaysSo(CapturedOutput output) {
        AccountId id = AccountId.random();
        redis.opsForValue().set("balance:" + id.value(), "not-json");

        assertThat(cache.get(id)).isEmpty();

        assertThat(output).contains("balance cache get: unreadable entry for " + id.value());
    }

    /**
     * The cache is not authoritative, so an outage must cost a cache miss and nothing more: a
     * propagating exception would 500 balance reads and — because the projector evicts inside the
     * append transaction — roll back money movements.
     */
    @Test
    void aRedisOutageCostsAMissRatherThanTheRequest(CapturedOutput output) {
        AccountId id = AccountId.random();
        BalanceView view = new BalanceView(id, Money.of("GBP", 5000), AS_OF, 7);
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        try {
            StringRedisTemplate template = new StringRedisTemplate(dead);
            template.afterPropertiesSet();
            BalanceCachePort unreachable = new RedisBalanceCache(template, new ObjectMapper(), Duration.ofSeconds(60));

            assertThatCode(() -> {
                        unreachable.put(id, view);
                        unreachable.evict(id);
                    })
                    .doesNotThrowAnyException();
            assertThat(unreachable.get(id)).isEmpty();
        } finally {
            dead.destroy();
        }

        assertThat(output)
                .contains("balance cache put failed", "balance cache evict failed", "balance cache get failed");
    }
}
