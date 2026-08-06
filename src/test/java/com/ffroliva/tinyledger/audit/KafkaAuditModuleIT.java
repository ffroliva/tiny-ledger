package com.ffroliva.tinyledger.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.shared.Money;
import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.BeforeAll;
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

    private static final String DLT_TOPIC = "ledger.events.DLT";

    /** More than one, so a key-derived placement is distinguishable from the source partition index. */
    private static final int DLT_PARTITIONS = 4;

    @BeforeAll
    static void provisionTheDeadLetterTopicWithSeveralPartitions() throws Exception {
        try (Admin admin =
                Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            // Ahead of every test here, so the recoverer's producer cannot first meet the DLT as the
            // single-partition topic Kafka would auto-create and cache — that would leave a key-derived
            // placement indistinguishable from reusing the source index.
            if (!admin.listTopics().names().get().contains(DLT_TOPIC)) {
                admin.createTopics(List.of(new NewTopic(DLT_TOPIC, DLT_PARTITIONS, (short) 1)))
                        .all()
                        .get();
            }
            assertThat(partitionCount(admin, DLT_TOPIC)).isEqualTo(DLT_PARTITIONS);
        }
    }

    @Test
    void ledgerEventsReachTheAuditTrailThroughKafka() {
        var opened = openAccount.open(new OpenAccount("alice", "ACC-AUDIT", Currency.getInstance("GBP")));
        UUID accountId = opened.accountId().value();

        movements.deposit(
                new Deposit("alice", false, opened.accountId(), UUID.randomUUID(), Money.of("GBP", 2500), "salary"));

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
            // §15.11: the field this whole change exists for — proves it survives the header ->
            // PostgresAuditTrail.record -> PostgresAuditTrail.eventStream round-trip, not just the
            // listener's in-memory mapping (AuditKafkaListenerTest) or the controller's read side
            // (AuditControllerTest).
            assertThat(entries.getLast().actor()).isEqualTo("alice");
        });
    }

    @Test // §7: the trail the auditor endpoint reads — newest first, filterable, cursor-paged
    void theTrailIsReadableNewestFirstOnePageAtATime() {
        var opened = openAccount.open(new OpenAccount("carol", "ACC-TRAIL", Currency.getInstance("GBP")));
        UUID accountId = opened.accountId().value();

        movements.deposit(
                new Deposit("carol", false, opened.accountId(), UUID.randomUUID(), Money.of("GBP", 700), "gift"));

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

    /**
     * §15.11: the event payload is the record; a header that disagrees with it is a fault, not a value
     * to silently prefer. {@code AuditKafkaListenerTest} proves the throw with no Kafka involved — this
     * proves the other half, that the throw actually reaches the operational alarm: the record is
     * parked on the DLT rather than landing in the trail with a value nobody can trust.
     */
    @Test
    void aRecordWhoseActorHeaderDisagreesWithItsPayloadIsParkedOnTheDlt() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Header> headers = List.of(
                new RecordHeader("event-type", "MoneyDeposited".getBytes(StandardCharsets.UTF_8)),
                new RecordHeader("stream-version", "1".getBytes(StandardCharsets.UTF_8)),
                new RecordHeader("occurred-at", Instant.now().toString().getBytes(StandardCharsets.UTF_8)),
                new RecordHeader("actor", "trent".getBytes(StandardCharsets.UTF_8)));

        try (Consumer<String, String> dlt = probe(DLT_TOPIC);
                Producer<String, String> producer = producer()) {
            dlt.poll(Duration.ofMillis(200)); // subscribe before anything is sent

            producer.send(new ProducerRecord<>("ledger.events", null, key, "{\"actor\":\"alice\"}", headers))
                    .get();

            List<String> parked = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                dlt.poll(Duration.ofSeconds(1)).forEach(record -> parked.add(record.key()));
                assertThat(parked).contains(key);
            });
        }

        assertThat(trail.eventStream(UUID.fromString(key), null, 50).entries()).isEmpty();
    }

    /**
     * The recoverer resolves the destination as {@code TopicPartition(ledger.events.DLT, -1)}, and a
     * negative partition becomes a null partition on the ProducerRecord — the producer then places the
     * record by key. Pinning {@code record.partition()} instead would reuse the source index, which
     * fails the publish outright whenever the DLT has fewer partitions than {@code ledger.events}, i.e.
     * exactly when the compliance trail needs it.
     *
     * <p>A single-partition {@code ledger.events} cannot show that on its own — index 0 is in range
     * everywhere. So the DLT carries several partitions and the key is chosen to hash somewhere other
     * than 0: a pinned source index would land the record on 0 instead.
     */
    @Test
    void aParkedRecordIsPlacedByKeyRatherThanOnTheSourcePartition() throws Exception {
        String key = aKeyNotHashingToPartitionZero();
        int placedByKey = partitionForKey(key);

        try (Consumer<String, String> dlt = probe(DLT_TOPIC);
                Producer<String, String> producer = producer()) {
            dlt.poll(Duration.ofMillis(200)); // subscribe before anything is sent

            // Explicitly partition 0 of ledger.events, so a pinned source index would be 0.
            producer.send(new ProducerRecord<>("ledger.events", 0, key, "{}")).get();

            Map<String, Integer> placement = new HashMap<>();
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                dlt.poll(Duration.ofSeconds(1)).forEach(record -> placement.put(record.key(), record.partition()));
                assertThat(placement).containsEntry(key, placedByKey);
            });
        }
    }

    private static String aKeyNotHashingToPartitionZero() {
        return IntStream.range(0, 100)
                .mapToObj(i -> "dlt-placement-" + i)
                .filter(candidate -> partitionForKey(candidate) != 0)
                .findFirst()
                .orElseThrow();
    }

    /** What the producer's default partitioner does: murmur2 of the key, modulo the partition count. */
    private static int partitionForKey(String key) {
        return Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % DLT_PARTITIONS;
    }

    private static int partitionCount(Admin admin, String topic) throws Exception {
        return admin.describeTopics(List.of(topic))
                .allTopicNames()
                .get()
                .get(topic)
                .partitions()
                .size();
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
