package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.audit.adapter.in.events.AuditKafkaListener;
import com.ffroliva.tinyledger.audit.adapter.out.postgres.PostgresAuditTrail;
import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import com.ffroliva.tinyledger.balance.adapter.out.postgres.PostgresBalanceProjection;
import com.ffroliva.tinyledger.balance.adapter.out.redis.RedisBalanceCache;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.ledger.adapter.out.postgres.PostgresEventStore;
import com.ffroliva.tinyledger.ledger.adapter.out.tenant.JwtClaimTenantResolver;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.port.out.IdGeneratorPort;
import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import com.ffroliva.tinyledger.ledger.application.usecase.OpenAccountService;
import com.ffroliva.tinyledger.ledger.application.usecase.RecordMovementService;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.notification.adapter.out.log.LogNotificationAdapter;
import com.ffroliva.tinyledger.notification.application.NotificationPort;
import com.ffroliva.tinyledger.platform.AuditLagGauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                                "event-type", event.eventType(),
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
    public AuditKafkaListener auditKafkaListener(AuditTrailPort trail, Tracer tracer) {
        return new AuditKafkaListener(trail, tracer);
    }

    /**
     * Spec §6.6 / ADR 0004. Constructed here rather than annotated as a {@code @Component} for the
     * reason {@code AGENTS.md} states as a build-enforced rule: {@code AuditLagGauge} reads an
     * outbound adapter, and only {@code config} and {@code adapter.out} may touch those —
     * {@code HexagonalRulesTest} fails the build otherwise. This class is already
     * {@code @Profile("full")}, so the {@code standalone} case, where migration 004's
     * {@code event_publication} table does not exist, needs no further guard.
     */
    @Bean
    public AuditLagGauge auditLagGauge(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        return new AuditLagGauge(jdbcTemplate, meterRegistry);
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
    public DefaultErrorHandler auditListenerErrorHandler(
            ProducerFactory<?, ?> producerFactory, MeterRegistry meterRegistry) {
        KafkaTemplate<String, String> deadLetters = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                producerFactory.getConfigurationProperties(), new StringSerializer(), new StringSerializer()));
        // The destination is named rather than left to the default, which is `<topic>-dlt` in Spring
        // Kafka 4 — the operational contract is `ledger.events.DLT`, not whatever the default becomes.
        // Partition -1 lets the producer place the record by key rather than reusing the source
        // partition index: that keeps per-account order on the DLT too, and a DLT provisioned with
        // fewer partitions than `ledger.events` still accepts it. Pinning the index would fail the
        // publish exactly when the trail depends on it.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetters, (consumed, exception) -> new TopicPartition(LEDGER_EVENTS_DLT, -1));

        // §6.6 / §14 step 9 part 2. This handler's own javadoc above says it exists to prevent "a
        // silent, permanent hole in the compliance trail" — and until now nothing counted what it
        // parked, so it produced one: records left the live path and no signal said so. §6.6 records
        // this as belonging to part 2, "where an exporter exists to carry them".
        //
        // UNTAGGED on purpose. The account id and the exception type are both unbounded, and this is a
        // meter (§6.6's cardinality rule, which no gate enforces). Which record was parked and why stays
        // answerable from the DLT itself, from the span, and from the log line the recoverer writes —
        // this counter's whole job is to make someone go and look.
        Counter deadLettered = Counter.builder("ledger.audit.dead_lettered")
                .description("Records parked on " + LEDGER_EVENTS_DLT + " by the audit consumer (spec §6.6)")
                .register(meterRegistry);

        return new DefaultErrorHandler(
                (consumed, exception) -> {
                    deadLettered.increment();
                    recoverer.accept(consumed, exception);
                },
                new FixedBackOff(1_000L, 9));
    }

    /**
     * <strong>No {@code @Primary}, and a concrete return type — both deliberate, both required.</strong>
     * Since §14 step 9 part 2, {@code UseCaseConfig}'s traced decorator is the {@code @Primary} bean
     * for this interface, and two {@code @Primary} candidates of one type is a context-startup
     * failure rather than a warning.
     *
     * <p>The concrete type is what lets that decorator select this bean through an
     * {@code ObjectProvider} in {@code full} and fall back to the plain service in {@code standalone},
     * from a single profile-independent bean method. Declaring {@code OpenAccountUseCase} here
     * instead would make the provider ambiguous — it would match the traced bean too.
     */
    @Bean
    public TransactionalUseCases.Opening transactionalOpenAccount(OpenAccountService delegate) {
        return new TransactionalUseCases.Opening(delegate);
    }

    /** See {@link #transactionalOpenAccount} — same two constraints, same reason. */
    @Bean
    public TransactionalUseCases.Movements transactionalRecordMovement(RecordMovementService delegate) {
        return new TransactionalUseCases.Movements(delegate);
    }

    /**
     * The {@code full}-profile tenant resolver, and the only one this profile can compose:
     * {@code FixedTenantResolver} is declared solely in {@code StandaloneAdapterConfig}, so a
     * config-backed tenant cannot reach a Postgres-backed deployment by configuration alone.
     *
     * <p>A missing claim name is a startup failure, not a default — absence of a claim mapping must
     * never resolve to "no tenancy".
     */
    @Bean
    TenantResolverPort tenantResolver(@Value("${" + TenantProvenanceGuard.CLAIM_PROPERTY + "}") String claimName) {
        return new JwtClaimTenantResolver(claimName);
    }
}
