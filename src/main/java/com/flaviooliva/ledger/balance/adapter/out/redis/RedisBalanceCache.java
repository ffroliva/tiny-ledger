package com.flaviooliva.ledger.balance.adapter.out.redis;

import com.flaviooliva.ledger.balance.application.port.in.BalanceView;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.shared.AccountId;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec §6.2 balance cache under the {@code full} profile. TTL is Redis's own key expiry, and the
 * projector's {@link #evict} keeps a write from being served stale before the TTL runs out.
 */
public class RedisBalanceCache implements BalanceCachePort {

    private static final String KEY_PREFIX = "balance:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisBalanceCache(StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public Optional<BalanceView> get(AccountId accountId) {
        String json = redis.opsForValue().get(key(accountId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, BalanceView.class));
        } catch (JacksonException e) {
            // A cache entry we can no longer read is a miss, not an outage — the projection answers.
            redis.delete(key(accountId));
            return Optional.empty();
        }
    }

    @Override
    public void put(AccountId accountId, BalanceView view) {
        redis.opsForValue().set(key(accountId), objectMapper.writeValueAsString(view), ttl);
    }

    @Override
    public void evict(AccountId accountId) {
        redis.delete(key(accountId));
    }

    private static String key(AccountId accountId) {
        return KEY_PREFIX + accountId.value();
    }
}
