package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.audit.adapter.in.events.AuditKafkaListener;
import com.ffroliva.tinyledger.audit.adapter.out.postgres.PostgresAuditTrail;
import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import com.ffroliva.tinyledger.balance.adapter.out.postgres.PostgresBalanceProjection;
import com.ffroliva.tinyledger.balance.adapter.out.redis.RedisBalanceCache;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.ledger.adapter.out.postgres.PostgresEventStore;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.port.out.IdGeneratorPort;
import com.ffroliva.tinyledger.ledger.application.usecase.OpenAccountService;
import com.ffroliva.tinyledger.ledger.application.usecase.RecordMovementService;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.notification.adapter.out.log.LogNotificationAdapter;
import com.ffroliva.tinyledger.notification.application.NotificationPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("full")
public class FullAdapterConfig {

    /** Spec §14 step 7: the one stream the audit module consumes. */
    public static final String LEDGER_EVENTS_TOPIC = "ledger.events";

    /** Where a record the audit consumer cannot process is parked instead of skipped. */
    public static final String LEDGER_EVENTS_DLT = LEDGER_EVENTS_TOPIC + ".DLT";

    // A dedicated ObjectMapper, not Spring's shared bean: isolates the persisted event JSON from web
    // ObjectMapper customizations, so a serializer/module change made for the API can't silently
    // reshape payloads already written to storage.
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
                                "occurred-at", event.occurredAt().toString(),
                                "actor", event.actor()))
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

    /**
     * Boot hands a single {@code CommonErrorHandler} bean to the listener container factory. Without
     * one, {@code DefaultErrorHandler}'s default recoverer logs a record it cannot process, commits
     * the offset and moves on — a silent, permanent hole in the compliance trail. Nine one-second
     * retries absorb a transient blip; anything still failing is parked on {@code ledger.events.DLT},
     * where it can be inspected and replayed.
     *
     * <p>Its own producer, built from the auto-configured one's settings: Modulith hands the shared
     * {@code KafkaTemplate} a {@code byte[]}, while a dead-lettered record carries the String the
     * audit consumer read.
     */
    @Bean
    public DefaultErrorHandler auditListenerErrorHandler(ProducerFactory<?, ?> producerFactory) {
        KafkaTemplate<String, String> deadLetters = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(), new StringSerializer(), new StringSerializer()));
        // The destination is named rather than left to the default, which is `<topic>-dlt` in Spring
        // Kafka 4 — the operational contract is `ledger.events.DLT`, not whatever the default becomes.
        // Partition -1 lets the producer place the record by key rather than reusing the source
        // partition index: that keeps per-account order on the DLT too, and a DLT provisioned with
        // fewer partitions than `ledger.events` still accepts it. Pinning the index would fail the
        // publish exactly when the trail depends on it.
        return new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(
                        deadLetters, (consumed, exception) -> new TopicPartition(LEDGER_EVENTS_DLT, -1)),
                new FixedBackOff(1_000L, 9));
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
