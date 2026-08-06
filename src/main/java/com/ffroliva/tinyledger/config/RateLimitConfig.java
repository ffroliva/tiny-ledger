package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.platform.LocalRateLimiterStore;
import com.ffroliva.tinyledger.platform.RateLimitProperties;
import com.ffroliva.tinyledger.platform.RateLimiterStore;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Spec §6.1: Bucket4j backed by Redis (lettuce) in {@code full}, so limits are shared across
 * instances; a local, bounded in-memory bucket in {@code standalone}. The swap is the same
 * mechanism as every other adapter (§4.5) — one seam ({@link RateLimiterStore}), two profile-scoped
 * beans — not a second convention invented for this concern.
 *
 * <p>A dedicated {@code RedisClient}, not the {@code LettuceConnectionFactory} Spring Data Redis
 * builds for {@code StringRedisTemplate}: Bucket4j's Lettuce integration owns its connection's
 * codec directly ({@code String} keys, raw {@code byte[]} values for its own binary bucket state),
 * so reaching into Spring Data Redis's connection for that would couple two unrelated concerns to
 * one connection object for no gain — the same reasoning {@code FullAdapterConfig} gives for its
 * own dedicated {@code ObjectMapper}. It reads the same {@code spring.data.redis.host}/{@code port}
 * keys {@code StringRedisTemplate} already uses, so both point at the same server without a second
 * pair of properties, and {@code AbstractIntegrationTest} overriding those two keys for the
 * Testcontainers Redis instance covers this client too.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * Product-owner addition: {@code ledger.rate-limit.exempt-ips} is the setting most likely to be
     * added during an incident and never reverted, so a non-empty list is announced loudly — once,
     * at startup, naming every entry — rather than only discoverable by reading configuration. Does
     * not itself decide anything; {@link com.ffroliva.tinyledger.platform.RateLimitFilter#isExempt}
     * is the enforcement point, this is only the operator-visible record that it is active.
     */
    @Bean
    ApplicationRunner logRateLimitExemptions(RateLimitProperties properties) {
        return args -> {
            if (!properties.exemptIps().isEmpty()) {
                log.warn(
                        "rate limiting is NOT enforced for these IPs (ledger.rate-limit.exempt-ips): {}",
                        properties.exemptIps());
            }
        };
    }

    @Bean
    @Profile("standalone")
    RateLimiterStore localRateLimiterStore() {
        return new LocalRateLimiterStore();
    }

    /**
     * Review finding (Redis-outage follow-up): {@code RedisURI.create(host, port)} carries Lettuce's
     * default 60s command timeout, and Bucket4j is handed no {@code ClientSideConfig}, so a Redis
     * outage would block every request in {@link com.ffroliva.tinyledger.platform.RateLimitFilter}
     * for ~60s before failing open — Tomcat's worker pool saturates long before that, which is a
     * total outage with unbounded latency, strictly worse than the 500 the I2 fail-open replaced.
     *
     * <p>250ms, not Bucket4j's {@code ClientSideConfig.withRequestTimeout(...)}: that path throws
     * {@code io.github.bucket4j.TimeoutException}, which extends {@code BucketExecutionException}
     * (a {@code RuntimeException}), <strong>not</strong> {@link io.lettuce.core.RedisException} —
     * adding it without widening the catch in {@code RateLimitFilter#probe} would silently reopen
     * the exact {@code /error} path leak I2 closed. Bounding it here instead keeps every failure
     * inside the hierarchy that method already catches. 250ms is chosen because this check sits in
     * every request's path — long enough to absorb ordinary network jitter to a same-network Redis,
     * short enough that a real outage costs one bounded stall per request, not sixty. <strong>Do not
     * "fix" this by adding a Bucket4j request timeout instead</strong> — see the paragraph above.
     */
    @Bean(destroyMethod = "shutdown")
    @Profile("full")
    RedisClient rateLimitRedisClient(
            @Value("${spring.data.redis.host}") String host, @Value("${spring.data.redis.port}") int port) {
        RedisURI uri = RedisURI.Builder.redis(host, port)
                .withTimeout(Duration.ofMillis(250))
                .build();
        return RedisClient.create(uri);
    }

    @Bean(destroyMethod = "close")
    @Profile("full")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    /**
     * §6.1: "per-IP buckets live in a bounded, expiring store ... Redis TTL in full" — every bucket
     * this proxy manager creates carries a TTL sized to the time it would take that bucket to refill
     * to its own cap, so an idle key (an IP or principal that stopped calling) expires from Redis
     * instead of accumulating there forever. Applied to every key uniformly, not just IP-keyed ones,
     * for the same reason {@link LocalRateLimiterStore} does not special-case IP either: one
     * mechanism.
     */
    @Bean
    @Profile("full")
    RateLimiterStore redisRateLimiterStore(StatefulRedisConnection<String, byte[]> connection) {
        ProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10)))
                .build();
        return proxyManager::getProxy;
    }
}
