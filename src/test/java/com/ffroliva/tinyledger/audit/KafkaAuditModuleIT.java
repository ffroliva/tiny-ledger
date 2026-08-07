package com.ffroliva.tinyledger.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;

    /** §9.3 E6 asks for 50. Written through the use case, not HTTP, so no rate-limit budget is spent. */
    private static final int E6_MOVEMENTS = 50;

    /**
     * E6 — consumer outage and catch-up. Stop the audit consumer, write 50 movements, start it again:
     * all 50 arrive, and the trail matches the event stream exactly — no gaps, no duplicates.
     *
     * <p>This is the durability half of ADR 0001. {@link #ledgerEventsReachTheAuditTrailThroughKafka}
     * proves an event reaches the trail while everything is healthy; it says nothing about what happens to
     * events produced while nobody is listening. Kafka's offset semantics are supposed to make that a
     * non-event, and "supposed to" is the reason to run it.
     *
     * <p><strong>The mid-outage assertion is the control, not decoration.</strong> Without it a run where
     * the container never actually stopped would pass identically, and this test would be asserting only
     * that delivery works — which the test above already covers. Asserting the trail is still at one entry
     * while 50 movements sit in the topic is what makes the rest of the test mean something.
     *
     * <p>Red run, and it validated the control rather than the catch-up: with the {@code stop()} removed,
     * 8 tests run and exactly 1 fails — this one, on the {@code during} window, because the trail grows
     * past one entry inside it. Worth recording that the <em>first</em> version of that control asserted
     * the size once, immediately after the writes, and would very likely have passed without the outage
     * ever happening: a healthy hop takes ~100 ms, so the trail still reads 1 at that instant. The bug the
     * control exists to catch was a bug the control itself had.
     *
     * <p>The restart is in a {@code finally} for the same reason the container unpauses are elsewhere in
     * this suite: a failure here must not leave the audit consumer stopped for whatever runs next in this
     * shared context.
     */
    @Test
    void aStoppedConsumerCatchesUpWithoutGapsOrDuplicates() {
        var opened = openAccount.open(new OpenAccount("alice", "ACC-E6", Currency.getInstance("GBP")));
        UUID accountId = opened.accountId().value();

        // Settle the AccountOpened first, so the control below is about the outage rather than about a
        // hop that simply had not finished yet.
        await().atMost(Duration.ofSeconds(30))
                .until(() -> trail.eventStream(accountId, null, 100).entries().size() == 1);

        listeners.getListenerContainers().forEach(org.springframework.kafka.listener.MessageListenerContainer::stop);
        try {
            for (int i = 0; i < E6_MOVEMENTS; i++) {
                movements.deposit(new Deposit(
                        "alice", false, opened.accountId(), UUID.randomUUID(), Money.of("GBP", 100), "e6-" + i));
            }
            // `during`, not a bare assertion. Checking the size once immediately after the writes would
            // have been racy in the direction that hides bugs: delivery normally takes ~100 ms, so the
            // trail would still read 1 even with the consumer running, and the control would pass without
            // the outage ever happening. Requiring the quiet to HOLD for two seconds — an order of
            // magnitude longer than the healthy hop measured by the tests above — is what makes it
            // evidence. Awaitility, never a sleep (§9.3 method).
            await().during(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(5))
                    .until(() ->
                            trail.eventStream(accountId, null, 100).entries().size() == 1);
        } finally {
            listeners
                    .getListenerContainers()
                    .forEach(org.springframework.kafka.listener.MessageListenerContainer::start);
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            List<AuditTrailPort.AuditEntry> entries =
                    trail.eventStream(accountId, null, E6_MOVEMENTS + 10).entries();
            // Versions 1..51 exactly once each, in order. A gap, a duplicate and a reordering are three
            // different delivery bugs and this one assertion refuses all of them.
            assertThat(entries.stream()
                            .map(AuditTrailPort.AuditEntry::streamVersion)
                            .toList())
                    .as("the trail must match the event stream exactly — no gaps, no duplicates")
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, E6_MOVEMENTS + 1)
                            .boxed()
                            .toList());
        });
    }

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

    /**
     * P7 — the auditor role's positive proof, end to end over the real chain.
     *
     * <p>Written because the traceability sweep found P7 had no single test: it was split across two that
     * each proved half. {@code RoleAuthorizationIT#anAuditorReadsTheTrail} asserts dave gets a 200 but never
     * that the trail holds anything, and {@link #ledgerEventsReachTheAuditTrailThroughKafka} asserts the
     * entry lands but reads it through {@code AuditTrailPort}, not as an auditor over HTTP. A read side that
     * answered 200 with an empty page for every account would have left both of them green. Adding the P7
     * label to either would have converted an open question into a false answer.
     *
     * <p>The Awaitility poll is on the port and not on the endpoint, deliberately: it waits out the Kafka
     * hop without spending a charged HTTP request per attempt — see the poll-ceiling arithmetic on
     * {@link AbstractIntegrationTest#RAISED_IP_BACKSTOP_LIMIT}. The single GET afterwards is the assertion.
     */
    @Test
    void anAuditorReadsAlicesDepositOutOfTheTrailOverHttp() throws Exception {
        var opened = openAccount.open(new OpenAccount("alice", "ACC-P7", Currency.getInstance("GBP")));
        UUID accountId = opened.accountId().value();
        movements.deposit(
                new Deposit("alice", false, opened.accountId(), UUID.randomUUID(), Money.of("GBP", 4200), "P7"));

        await().atMost(Duration.ofSeconds(30))
                .until(() -> trail.trail(new AuditTrailPort.TrailQuery(accountId, null, 50, null, null))
                                .entries()
                                .size()
                        == 2);

        // Newest first (theTrailIsReadableNewestFirstOnePageAtATime pins that ordering), so the deposit is
        // entry 0 and the account opening is entry 1.
        mockMvc.perform(get("/api/v1/audit/entries")
                        .param("accountUid", accountId.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("dave")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditEntries[0].type").value("MoneyDeposited"))
                .andExpect(jsonPath("$.auditEntries[0].accountUid").value(accountId.toString()))
                .andExpect(jsonPath("$.auditEntries[0].actor").value("alice"));
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

    /**
     * E12 — an in-flight publication survives a broker outage and completes without intervention.
     *
     * <p><strong>This is deliberately not tagged E7, and the distinction is the point.</strong> E7 asks for
     * the application to be killed mid-publication and restarted, so that
     * {@code republish-outstanding-events-on-restart} completes the delivery. No harness here can kill and
     * restart the process inside a shared Spring context (ADR 0003), so E7 stays open.
     *
     * <p>What is reachable is the half E7 depends on, and it is the load-bearing half: that the work is
     * <em>durably on disk</em> while delivery is impossible. With {@code completion-mode=DELETE} an
     * incomplete publication is simply a row that still exists, so a surviving row is the evidence that a
     * process dying at that instant would lose nothing. Without it the restart hook would have nothing to
     * replay and E7's guarantee could not hold however the restart behaved.
     *
     * <p>The recovery assertion is honest about what it does <em>not</em> isolate: once the broker returns,
     * the producer's own in-flight send can complete the publication on its own, so this proves "completes
     * without manual intervention" and does not attribute that to the restart hook specifically.
     */
    @Test
    void anInFlightPublicationSurvivesABrokerOutageAndCompletesWithoutIntervention() {
        var opened = openAccount.open(new OpenAccount("bob", "ACC-E12", Currency.getInstance("GBP")));
        UUID accountId = opened.accountId().value();
        // Drain first, so "a row exists" below is this test's row and not someone else's leftover.
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(outstandingPublications()).isZero());

        String containerId = KAFKA.getContainerId();
        try {
            DockerClientFactory.instance()
                    .client()
                    .pauseContainerCmd(containerId)
                    .exec();
            movements.deposit(
                    new Deposit("bob", false, opened.accountId(), UUID.randomUUID(), Money.of("GBP", 500), "e12"));

            // Held, not sampled once — the same correction E6's control needed. A row that merely has not
            // been cleaned up yet is indistinguishable from a durable one at a single instant.
            await().during(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(20))
                    .until(() -> outstandingPublications() >= 1);
        } finally {
            DockerClientFactory.instance()
                    .client()
                    .unpauseContainerCmd(containerId)
                    .exec();
        }

        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            assertThat(outstandingPublications()).isZero();
            assertThat(trail.eventStream(accountId, null, 50).entries()).hasSize(2);
        });
    }

    private Long outstandingPublications() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication", Long.class);
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
