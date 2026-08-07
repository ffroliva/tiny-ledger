package com.ffroliva.tinyledger.platform;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spec §6.6 / ADR 0004: the age of the oldest event publication that has not completed — how far the
 * outbox has fallen behind the writes that filled it.
 *
 * <p><strong>Nothing gates on this.</strong> It is a gauge and an alerting input, deliberately. The
 * readiness probe does not consume it: gating on this value would take an instance out of service
 * during exactly the Kafka outage E11 requires the ledger to survive. ADR 0004 has the reasoning and
 * the rejected alternatives.
 *
 * <p><strong>Note what this is not, twice over.</strong> It is not projection lag: the balance
 * projection is a synchronous {@code @EventListener} on the publishing thread inside the write
 * transaction (§4.3), so its lag is structurally zero and no gauge could read anything but zero from
 * it. Nor is it <em>audit consumer</em> lag: {@code spring.modulith.events.completion-mode=DELETE}
 * removes the publication row the moment the Kafka <em>producer</em> is acknowledged, and
 * {@code AuditKafkaListener} runs downstream of that ack on its own consumer group — so pausing the
 * consumer leaves this reading {@code 0.0}. Two lags exist, separated by the broker; this measures the
 * producer side, which is why it is named {@code ledger.outbox.pending.age.seconds} and not
 * {@code ledger.audit.lag.seconds}, as it was until spec v3.37. The consumer side is unmeasured and
 * recorded as such.
 *
 * <p>Aggregate across replicas with {@code max}, never {@code sum} (§6.6): every replica reads the
 * same shared table and reports the same global value, so a sum reads N times the truth — a plausible
 * number that is false, rather than a visibly broken chart.
 *
 * <p>{@code full} only — {@code standalone} has no {@code event_publication} table (migration 004).
 */
public class AuditLagGauge {

    /**
     * Two clauses, each load-bearing for a different reason.
     *
     * <p>{@code COALESCE}, not an empty result: with nothing outstanding the aggregate returns one
     * NULL row, and an unhandled null registers the gauge as NaN — which graphs as a gap and alerts as
     * nothing. Zero is the truthful reading for "no publication is waiting".
     *
     * <p>{@code status <> 'FAILED'} because Modulith's mark-failed path never sets
     * {@code completion_date} and resubmission is restart-only. One poison row would therefore pin
     * {@code MIN(publication_date)} forever, fire the alert permanently and hide every later
     * excursion behind it — the failure mode where a monitor is worse than no monitor. {@code status}
     * is nullable in the v2 schema (migration 004), so the null check is not defensive padding: rows
     * written before a status is assigned would otherwise be excluded by the comparison.
     */
    private static final String OLDEST_INCOMPLETE = """
            SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(publication_date))), 0)
            FROM event_publication
            WHERE completion_date IS NULL
              AND (status IS NULL OR status <> 'FAILED')
            """;

    private final JdbcTemplate jdbc;

    public AuditLagGauge(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("ledger.outbox.pending.age.seconds", this, AuditLagGauge::lagSeconds);
    }

    /**
     * The current reading, in seconds. Public rather than package-private: {@code AuditLagIT} lives in
     * {@code ..observability} and reads it directly rather than through the registry, so a failure
     * names a number instead of a missing meter. Nothing in {@code src/main} calls this — the
     * {@code MeterRegistry} holds the reference and polls it.
     */
    public double lagSeconds() {
        Double seconds = jdbc.queryForObject(OLDEST_INCOMPLETE, Double.class);
        return seconds == null ? 0.0 : seconds;
    }
}
