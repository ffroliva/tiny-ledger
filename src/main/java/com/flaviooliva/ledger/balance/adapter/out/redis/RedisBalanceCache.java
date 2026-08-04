package com.flaviooliva.ledger.balance.adapter.out.redis;

import com.flaviooliva.ledger.balance.application.port.in.BalanceView;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.shared.AccountId;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec §6.2 balance cache under the {@code full} profile. TTL is Redis's own key expiry, and the
 * projector's {@link #evict} keeps a write from being served stale before the TTL runs out.
 *
 * <p>Non-authoritative, therefore never fatal: every operation swallows the {@link DataAccessException}
 * the template translates a Redis failure into, and logs it. A read degrades to a miss the projection
 * answers, a write to no cache entry. Anything else still propagates — a bug here is not an outage.
 */
public class RedisBalanceCache implements BalanceCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisBalanceCache.class);
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
        try {
            String json = redis.opsForValue().get(key(accountId));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, BalanceView.class));
        } catch (JacksonException e) {
            // A cache entry we can no longer read is a miss, not an outage — the projection answers.
            log.warn(
                    "balance cache get: unreadable entry for {}, dropping it and reading through",
                    accountId.value(),
                    e);
            evict(accountId);
            return Optional.empty();
        } catch (DataAccessException e) {
            log.warn("balance cache get failed for {}, reading through to the projection", accountId.value(), e);
            return Optional.empty();
        }
    }

    @Override
    public void put(AccountId accountId, BalanceView view) {
        try {
            redis.opsForValue().set(key(accountId), objectMapper.writeValueAsString(view), ttl);
        } catch (DataAccessException e) {
            log.warn("balance cache put failed for {}, the next read goes to the projection", accountId.value(), e);
        }
    }

    @Override
    public void evict(AccountId accountId) {
        try {
            redis.delete(key(accountId));
        } catch (DataAccessException e) {
            // The projector evicts inside the append transaction: rethrowing here would roll back a
            // movement because a cache is down. The stale entry expires with its TTL instead.
            log.warn("balance cache evict failed for {}, the entry now expires with its TTL", accountId.value(), e);
        }
    }

    private static String key(AccountId accountId) {
        return KEY_PREFIX + accountId.value();
    }
}
