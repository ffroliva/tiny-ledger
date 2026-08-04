package com.ffroliva.tinyledger.balance.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The two halves of {@link RedisBalanceCache}'s catch narrowing that a running Redis cannot show.
 * {@code RedisBalanceCacheIT.aRedisOutageCostsAMissRatherThanTheRequest} would pass unchanged against
 * {@code catch (RuntimeException)}, because a wider catch is a superset of a narrower one — so on its
 * own it proves nothing about where the boundary sits.
 *
 * <p>No container: both failures are injected at the template and mapper seams.
 */
@ExtendWith(OutputCaptureExtension.class)
class RedisBalanceCacheTest {

    private static final Duration TTL = Duration.ofSeconds(60);
    private static final Instant AS_OF = Instant.parse("2026-08-04T12:00:00Z");

    private final AccountId account = AccountId.random();
    private final BalanceView view = new BalanceView(account, Money.of("GBP", 5000), AS_OF, 7);

    /**
     * Only a {@link org.springframework.dao.DataAccessException} means "Redis is unreachable", and only
     * that degrades to a cache miss. Anything else is a fault we have no story for — swallowing it would
     * leave a permanently cold cache and no signal that anything is wrong.
     */
    @Test
    void aBoundaryFailureThatIsNotADataAccessExceptionReachesTheCaller() {
        BalanceCachePort cache = new RedisBalanceCache(new UntranslatedFailure(), new ObjectMapper(), TTL);

        assertThatThrownBy(() -> cache.get(account)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.put(account, view)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.evict(account)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * A view we cannot serialise is our own bug rather than an outage, so it is logged at ERROR instead
     * of hiding among the outage warnings — but still not rethrown: {@code put} runs after
     * {@code GET /balance} has already been answered, and a 500 now would fail a request that succeeded.
     */
    @Test
    void aViewThatCannotBeSerialisedIsLoggedAtErrorAndNotRethrown(CapturedOutput output) {
        BalanceCachePort cache = new RedisBalanceCache(new StringRedisTemplate(), new UnserialisableViews(), TTL);

        assertThatCode(() -> cache.put(account, view)).doesNotThrowAnyException();

        assertThat(output).contains("ERROR");
        assertThat(output).contains("balance cache put: " + account.value() + " could not be serialised");
    }

    /** A closed Lettuce client, say: a RuntimeException the template never translates. */
    private static final class UntranslatedFailure extends StringRedisTemplate {
        @Override
        public ValueOperations<String, String> opsForValue() {
            throw new IllegalStateException("connection factory is shut down");
        }

        @Override
        public Boolean delete(String key) {
            throw new IllegalStateException("connection factory is shut down");
        }
    }

    /** Stands in for a serializer bug reaching BalanceView; the template is never touched. */
    private static final class UnserialisableViews extends ObjectMapper {
        @Override
        public String writeValueAsString(Object value) {
            throw new SerialisationBug();
        }
    }

    /** {@link JacksonException}'s constructors are protected, so the double has to be a subclass. */
    private static final class SerialisationBug extends JacksonException {
        SerialisationBug() {
            super("no serializer found for BalanceView");
        }
    }
}
