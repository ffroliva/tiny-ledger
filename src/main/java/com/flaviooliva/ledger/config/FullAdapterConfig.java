package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.audit.adapter.in.events.AuditKafkaListener;
import com.flaviooliva.ledger.audit.adapter.out.postgres.PostgresAuditTrail;
import com.flaviooliva.ledger.audit.application.port.out.AuditTrailPort;
import com.flaviooliva.ledger.balance.adapter.out.postgres.PostgresBalanceProjection;
import com.flaviooliva.ledger.balance.adapter.out.redis.RedisBalanceCache;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.adapter.out.postgres.PostgresEventStore;
import com.flaviooliva.ledger.ledger.application.port.in.OpenAccountUseCase;
import com.flaviooliva.ledger.ledger.application.port.in.RecordMovementUseCase;
import com.flaviooliva.ledger.ledger.application.port.out.ClockPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.application.port.out.IdGeneratorPort;
import com.flaviooliva.ledger.ledger.application.usecase.OpenAccountService;
import com.flaviooliva.ledger.ledger.application.usecase.RecordMovementService;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.notification.adapter.out.log.LogNotificationAdapter;
import com.flaviooliva.ledger.notification.application.NotificationPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("full")
public class FullAdapterConfig {

    /** Spec §14 step 7: the one stream the audit module consumes. */
    public static final String LEDGER_EVENTS_TOPIC = "ledger.events";

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

    /**
     * ADR 0001: routing is declared here rather than with {@code @Externalized} on the events —
     * the annotation is a Spring type and {@code domain} carries no framework annotations.
     * Keyed by account id so a single account's events stay ordered within one Kafka partition.
     */
    @Bean
    public EventExternalizationConfiguration ledgerEventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(LedgerEvent.class)
                .route(LedgerEvent.class, event -> RoutingTarget.forTarget(LEDGER_EVENTS_TOPIC)
                        .andKey(event.accountId().value().toString()))
                // Headers, not payload parsing: the audit module never has to know the JSON shape
                // of a ledger event, which is what keeps the module boundary real.
                .headers(
                        LedgerEvent.class,
                        event -> Map.of(
                                "event-type", event.getClass().getSimpleName(),
                                "stream-version", String.valueOf(event.version()),
                                "occurred-at", event.occurredAt().toString()))
                .build();
    }

    @Bean
    public AuditTrailPort auditTrail(JdbcTemplate jdbcTemplate) {
        return new PostgresAuditTrail(jdbcTemplate);
    }

    @Bean
    public AuditKafkaListener auditKafkaListener(AuditTrailPort trail) {
        return new AuditKafkaListener(trail);
    }

    @Bean
    @Primary
    public OpenAccountUseCase transactionalOpenAccount(OpenAccountService delegate) {
        return new TransactionalUseCases.Opening(delegate);
    }

    @Bean
    @Primary
    public RecordMovementUseCase transactionalRecordMovement(RecordMovementService delegate) {
        return new TransactionalUseCases.Movements(delegate);
    }
}
