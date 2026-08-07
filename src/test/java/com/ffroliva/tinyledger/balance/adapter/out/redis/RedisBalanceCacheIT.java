package com.ffroliva.tinyledger.balance.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import java.time.Duration;
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
class RedisBalanceCacheIT extends AbstractIntegrationTest
        implements com.ffroliva.tinyledger.contract.BalanceCacheContract {

    @Autowired
    private BalanceCachePort cache;

    @Autowired
    private StringRedisTemplate redis;

    @Override
    public BalanceCachePort cache() {
        return cache;
    }

    // The round trip, the miss and the evict used to live here as three local tests. They are port
    // semantics, not Redis semantics, so they moved to BalanceCacheContract (§9.2b) — where the
    // in-memory adapter, which had none of them, now runs them too. What stays below is what is
    // genuinely this adapter's: Redis key expiry, a corrupt payload, and a real outage.

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
