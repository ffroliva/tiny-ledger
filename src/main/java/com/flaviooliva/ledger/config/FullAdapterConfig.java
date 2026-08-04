package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.balance.adapter.out.postgres.PostgresBalanceProjection;
import com.flaviooliva.ledger.balance.adapter.out.redis.RedisBalanceCache;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.adapter.out.postgres.PostgresEventStore;
import com.flaviooliva.ledger.ledger.application.port.out.ClockPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.application.port.out.IdGeneratorPort;
import com.flaviooliva.ledger.notification.adapter.out.log.LogNotificationAdapter;
import com.flaviooliva.ledger.notification.application.NotificationPort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("full")
@EnableScheduling
public class FullAdapterConfig {

    @Bean
    public EventStorePort eventStore(JdbcTemplate jdbcTemplate) {
        return new PostgresEventStore(jdbcTemplate, new ObjectMapper());
    }

    @Bean
    public ClockPort clock() {
        return Instant::now;
    }

    @Bean
    public IdGeneratorPort ids() {
        return UUID::randomUUID;
    }

    @Bean
    public BalanceProjectionPort balanceProjection(JdbcTemplate jdbcTemplate) {
        return new PostgresBalanceProjection(jdbcTemplate);
    }

    @Bean
    public BalanceCachePort balanceCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new RedisBalanceCache(redis, objectMapper, Duration.ofSeconds(60));
    }

    @Bean
    public NotificationPort logNotificationAdapter() {
        return new LogNotificationAdapter();
    }
}
