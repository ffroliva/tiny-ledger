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
import java.util.Currency;
import java.util.List;
import java.util.UUID;
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
