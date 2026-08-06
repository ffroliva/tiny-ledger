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
import org.springframework.beans.factory.annotation.Value;
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

    @Bean
    @Profile("standalone")
    RateLimiterStore localRateLimiterStore() {
        return new LocalRateLimiterStore();
    }

    @Bean(destroyMethod = "shutdown")
    @Profile("full")
    RedisClient rateLimitRedisClient(
            @Value("${spring.data.redis.host}") String host, @Value("${spring.data.redis.port}") int port) {
        return RedisClient.create(RedisURI.create(host, port));
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
