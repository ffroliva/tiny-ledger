package com.ffroliva.tinyledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec §9.4's observability assertions — <strong>half one of §14 step 9's done-when.</strong>
 *
 * <p>Runs on the shared {@code full} context and forks nothing: the exporter arrives by an
 * {@code @Import} on {@code AbstractIntegrationTest} and the two properties through the
 * {@code @DynamicPropertySource} that was already there, which is exactly what ADR 0003 §1 prescribes
 * — "supplied as a property through the existing {@code @DynamicPropertySource}, never via an
 * {@code @Import}" on a subclass.
 *
 * <p><strong>Every test clears the exporter first.</strong> The context is shared by nine IT classes,
 * so a span left behind by an earlier one would let an assertion pass for the wrong reason — the same
 * hazard {@code AuditLagIT} records for the outbox gauge, where an undrained row made a rise assertion
 * vacuous.
 */
class ObservabilityIT extends AbstractIntegrationTest {

    private static final String ROOT_SPAN_ID = "0000000000000000";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private InMemorySpanExporter spans;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    @Test
    void aWithdrawalProducesTheExpectedSpanTreeWithTheDomainAttributes() throws Exception {
        UUID account = openAnAccountAs("alice");
        deposit(account, 10000);

        mvc.perform(put("/api/v1/accounts/{a}/withdrawals/{w}", account, UUID.randomUUID())
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":4000}}"))
                .andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            // Selected by ATTRIBUTE, never by "the only one since the last reset". Measured on CI: a
            // reset placed between the deposit and the withdrawal does not clear the deposit's span,
            // because the batch processor's 100ms flush had not run yet — so the assertion read the
            // deposit and failed with expected:<WITHDRAWAL> but was:<DEPOSIT>. A test whose subject
            // depends on an exporter's flush timing is a flaky test wearing a green tick.
            SpanData movement = spanWhere(
                    "ledger.record-movement",
                    s -> account.toString().equals(attribute(s, "ledger.account_id"))
                            && "WITHDRAWAL".equals(attribute(s, "ledger.movement_type")));
            assertThat(attribute(movement, "ledger.stream_version")).isNotBlank();

            SpanData projection = spanWhere(
                    "ledger.projection.apply", s -> movement.getSpanId().equals(s.getParentSpanId()));
            assertThat(projection.getTraceId())
                    .as("the projection is synchronous inside the write (§4.3), so its span NESTS in the"
                            + " same trace — a detached one would contradict ADR 0004's zero lag")
                    .isEqualTo(movement.getTraceId());
        });
    }

    @Test
    void theAuditConsumersSpanLinksBackToTheProducerAndIsNotADetachedRoot() throws Exception {
        UUID account = openAnAccountAs("alice");
        deposit(account, 1500);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            SpanData consume = spanNamed("ledger.audit.record");

            assertThat(consume.getLinks())
                    .as("§6.6: the consumer LINKS back to the producer — a child would make the request"
                            + " span appear to last until the slowest consumer finished")
                    .isNotEmpty();
            assertThat(consume.getParentSpanId())
                    .as("...and it is a new root, so it must have no parent")
                    .isEqualTo(ROOT_SPAN_ID);
            assertThat(consume.getLinks().getFirst().getSpanContext().getTraceId())
                    .as("...but it is not DETACHED: the link carries the producing trace, which is the"
                            + " whole point — traceparent survived the Kafka hop")
                    .isNotEqualTo(consume.getTraceId())
                    .isNotEqualTo("0".repeat(32));
        });
    }

    @Test
    void aRejectedMovementIncrementsTheCounterUnderItsReason() throws Exception {
        UUID account = openAnAccountAs("alice");
        double before = rejections("insufficient-funds");

        mvc.perform(put("/api/v1/accounts/{a}/withdrawals/{w}", account, UUID.randomUUID())
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":9999}}"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(rejections("insufficient-funds")).isEqualTo(before + 1.0);
    }

    /**
     * A local copy of {@code SecurityConfigIT}'s helper rather than an extraction of it. That class
     * counts every one of its own call sites against the rate-limit budget documented on
     * {@link AbstractIntegrationTest#LOWERED_WRITE_LIMIT}; renaming its {@code mvc} field across thirty
     * call sites to share ten lines is a large diff on a delicate class for a small gain.
     */
    private UUID openAnAccountAs(String owner) throws Exception {
        String body = mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OBS-%s\",\"currency\":\"GBP\"}".formatted(owner)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.accountUid"));
    }

    private void deposit(UUID account, long minorUnits) throws Exception {
        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", account, UUID.randomUUID())
                        .header("Authorization", bearer("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":%d}}".formatted(minorUnits)))
                .andExpect(status().isCreated());
    }

    private SpanData spanNamed(String name) {
        return spanWhere(name, s -> true);
    }

    /**
     * Fails naming every span that WAS seen — "not found" with no list is the least useful red there is,
     * and on a context shared by nine IT classes the list is usually the whole diagnosis.
     */
    private SpanData spanWhere(String name, Predicate<SpanData> match) {
        List<SpanData> finished = spans.getFinishedSpanItems();
        return finished.stream()
                .filter(s -> name.equals(s.getName()))
                .filter(match)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no matching span named " + name + "; saw "
                        + finished.stream().map(SpanData::getName).toList()));
    }

    private double rejections(String reason) {
        var counter = meters.find("ledger.movements")
                .tag("outcome", "rejected")
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String attribute(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}
