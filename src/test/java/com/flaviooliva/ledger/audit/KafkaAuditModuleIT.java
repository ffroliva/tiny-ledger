package com.flaviooliva.ledger.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flaviooliva.ledger.audit.application.port.out.AuditTrailPort;
import com.flaviooliva.ledger.ledger.application.port.in.Deposit;
import com.flaviooliva.ledger.ledger.application.port.in.OpenAccount;
import com.flaviooliva.ledger.ledger.application.port.in.OpenAccountUseCase;
import com.flaviooliva.ledger.ledger.application.port.in.RecordMovementUseCase;
import com.flaviooliva.ledger.shared.Money;
import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR 0001 end to end: a movement recorded through the use case is externalized to Kafka by the
 * Modulith publication registry and lands in the audit trail — no hand-rolled outbox involved.
 */
class KafkaAuditModuleIT extends AbstractIntegrationTest {

    @Autowired
    private OpenAccountUseCase openAccount;

    @Autowired
    private RecordMovementUseCase movements;

    @Autowired
    private AuditTrailPort trail;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void ledgerEventsReachTheAuditTrailThroughKafka() {
        var opened = openAccount.open(new OpenAccount("alice", "ACC-AUDIT", Currency.getInstance("GBP")));
        UUID accountId = opened.accountId().value();

        movements.deposit(new Deposit("alice", opened.accountId(), UUID.randomUUID(), Money.of("GBP", 2500), "salary"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<AuditTrailPort.AuditEntry> entries =
                    trail.eventStream(accountId, null, 50).entries();
            assertThat(entries).hasSize(2);
            assertThat(entries.getFirst().eventType()).isEqualTo("AccountOpened");
            assertThat(entries.getFirst().streamVersion()).isEqualTo(1);
            assertThat(entries.getFirst().recordedAt())
                    .isAfterOrEqualTo(entries.getFirst().occurredAt());
            assertThat(entries.getLast().eventType()).isEqualTo("MoneyDeposited");
            assertThat(entries.getLast().payload()).contains("2500");
        });
    }

    @Test // §7: the trail the auditor endpoint reads — newest first, filterable, cursor-paged
    void theTrailIsReadableNewestFirstOnePageAtATime() {
        var opened = openAccount.open(new OpenAccount("carol", "ACC-TRAIL", Currency.getInstance("GBP")));
        UUID accountId = opened.accountId().value();

        movements.deposit(new Deposit("carol", opened.accountId(), UUID.randomUUID(), Money.of("GBP", 700), "gift"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var first = trail.trail(new AuditTrailPort.TrailQuery(accountId, null, 1, null, null));
            assertThat(first.entries()).hasSize(1);
            assertThat(first.entries().getFirst().eventType()).isEqualTo("MoneyDeposited");
            assertThat(first.nextCursor()).isNotNull();

            var second = trail.trail(new AuditTrailPort.TrailQuery(accountId, first.nextCursor(), 1, null, null));
            assertThat(second.entries()).hasSize(1);
            assertThat(second.entries().getFirst().eventType()).isEqualTo("AccountOpened");
            assertThat(second.nextCursor()).isNull();

            // The time filter is on the ledger's own clock, not the Kafka hop.
            assertThat(trail.trail(new AuditTrailPort.TrailQuery(
                                    accountId, null, 50, Instant.now().plusSeconds(60), null))
                            .entries())
                    .isEmpty();
        });
    }

    /**
     * A record the audit consumer cannot process must end up somewhere an operator can find it. The
     * default behaviour — log, skip, commit the offset — would leave a hole in the trail that nothing
     * records.
     */
    @Test
    void aRecordTheConsumerCannotProcessIsParkedOnTheDlt() throws Exception {
        try (Consumer<String, String> dlt = probe("ledger.events.DLT");
                Producer<String, String> producer = producer()) {
            dlt.poll(Duration.ofMillis(200)); // subscribe and create the topic before anything is sent

            // No headers and a key that is not a UUID: the listener throws on every attempt.
            producer.send(new ProducerRecord<>("ledger.events", "not-a-uuid", "{}"))
                    .get();

            List<String> parked = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                dlt.poll(Duration.ofSeconds(1)).forEach(record -> parked.add(record.key()));
                assertThat(parked).contains("not-a-uuid");
            });
        }

        // …and the consumer is still consuming: the next good event reaches the trail.
        var opened = openAccount.open(new OpenAccount("dave", "ACC-DLT", Currency.getInstance("GBP")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                        trail.eventStream(opened.accountId().value(), null, 50).entries())
                .isNotEmpty());
    }

    private static Producer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class.getName()));
    }

    private static Consumer<String, String> probe(String topic) {
        Consumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlt-probe-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                // The DLT does not exist until the recoverer publishes: notice it without waiting out
                // the five-minute default metadata age.
                ConsumerConfig.METADATA_MAX_AGE_CONFIG,
                "1000",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName()));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    @Test
    void completedPublicationsAreDeletedRatherThanKept() {
        var opened = openAccount.open(new OpenAccount("bob", "ACC-PUB", Currency.getInstance("GBP")));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(trail.eventStream(opened.accountId().value(), null, 50).entries())
                    .isNotEmpty();
            // completion-mode=DELETE (ADR 0001): the queue holds in-flight work only.
            Long outstanding = jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication", Long.class);
            assertThat(outstanding).isZero();
        });
    }
}
