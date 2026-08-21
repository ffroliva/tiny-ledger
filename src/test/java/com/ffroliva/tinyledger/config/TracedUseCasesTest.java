package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.Outcome;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit level: no Spring context, no containers. The decorator is a plain object, and the two things
 * it must get right — the span's attributes and the counter's TAGS — are both observable from a
 * {@link SimpleTracer} and a {@link SimpleMeterRegistry}.
 *
 * <p>The cardinality assertion is the point of this class rather than padding. §6.6's rule that
 * account identifiers never reach a meter has <strong>no gate</strong>; this is the nearest thing to
 * one, and it covers exactly one meter.
 */
class TracedUseCasesTest {

    private static final AccountId ACCOUNT = AccountId.of("2f1b9f7e-0000-4000-8000-000000000001");
    private static final Money TEN_POUNDS = new Money(Currency.getInstance("GBP"), 1000L);

    private SimpleTracer tracer;
    private MeterRegistry meters;

    @BeforeEach
    void setUp() {
        tracer = new SimpleTracer();
        meters = new SimpleMeterRegistry();
    }

    private RecordMovementUseCase traced(RecordMovementUseCase delegate) {
        return new TracedUseCases.Movements(delegate, tracer, meters);
    }

    private static Deposit deposit() {
        return new Deposit("alice", false, ACCOUNT, UUID.randomUUID(), TEN_POUNDS, "rent");
    }

    private static Withdraw withdraw() {
        return new Withdraw("alice", false, ACCOUNT, UUID.randomUUID(), TEN_POUNDS, "rent");
    }

    private static MovementResult result(MovementType type, Outcome outcome, String reason) {
        return new MovementResult(
                ACCOUNT, UUID.randomUUID(), type, 7L, TEN_POUNDS, TEN_POUNDS, Instant.EPOCH, outcome, reason);
    }

    @Nested
    class Spans {

        @Test
        void aSettledMovementProducesOneSpanCarryingTheDomainAttributes() {
            traced(new StubMovements(result(MovementType.WITHDRAWAL, Outcome.CREATED, null)))
                    .withdraw(withdraw());

            var span = tracer.onlySpan();
            assertThat(span.getName()).isEqualTo("ledger.record-movement");
            assertThat(span.getTags())
                    .containsEntry("ledger.account_id", ACCOUNT.value().toString())
                    .containsEntry("ledger.movement_type", "WITHDRAWAL")
                    .containsEntry("ledger.stream_version", "7");
            assertThat(span.getEndTimestamp()).isNotNull();
        }

        @Test
        void aRejectionTagsTheReasonOnTheSpanAsWellAsTheCounter() {
            traced(new StubMovements(result(MovementType.WITHDRAWAL, Outcome.REJECTED, "insufficient-funds")))
                    .withdraw(withdraw());

            assertThat(tracer.onlySpan().getTags()).containsEntry("ledger.rejection_reason", "insufficient-funds");
        }

        @Test
        void aConcurrencyConflictEndsTheSpanAndRecordsTheError() {
            var boom = new ConcurrencyConflictException(ACCOUNT, 3L, 4L);

            assertThatThrownBy(() -> traced(new ThrowingMovements(boom)).withdraw(withdraw()))
                    .as("a decorator that swallows is a decorator that lies")
                    .isSameAs(boom);
            assertThat(tracer.onlySpan().getEndTimestamp())
                    .as("a span left unended leaks and never reaches a backend")
                    .isNotNull();
            assertThat(tracer.onlySpan().getError()).isSameAs(boom);
        }
    }

    @Nested
    class Meters {

        @Test
        void aSettledDepositCountsWithReasonNone() {
            traced(new StubMovements(result(MovementType.DEPOSIT, Outcome.CREATED, null)))
                    .deposit(deposit());

            assertThat(meters.get("ledger.movements")
                            .tag("type", "DEPOSIT")
                            .tag("outcome", "created")
                            .tag("reason", "none")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void aRejectionCountsUnderItsReason() {
            traced(new StubMovements(result(MovementType.WITHDRAWAL, Outcome.REJECTED, "insufficient-funds")))
                    .withdraw(withdraw());

            assertThat(meters.get("ledger.movements")
                            .tag("outcome", "rejected")
                            .tag("reason", "insufficient-funds")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        @Test
        void aConcurrencyConflictCountsAsItsOwnOutcome() {
            assertThatThrownBy(() -> traced(new ThrowingMovements(new ConcurrencyConflictException(ACCOUNT, 3L, 4L)))
                            .withdraw(withdraw()))
                    .isInstanceOf(ConcurrencyConflictException.class);

            assertThat(meters.get("ledger.movements")
                            .tag("outcome", "conflict")
                            .tag("reason", "none")
                            .counter()
                            .count())
                    .as("§6.6 asks for a concurrency-conflict rate by name; a conflict is an expected"
                            + " outcome of an optimistic append, not a fault")
                    .isEqualTo(1.0);
        }

        @Test
        void noMeterTagCarriesAnAccountIdOrAMovementUid() {
            traced(new StubMovements(result(MovementType.WITHDRAWAL, Outcome.CREATED, null)))
                    .withdraw(withdraw());

            assertThat(meters.get("ledger.movements").counter().getId().getTags())
                    .as("§6.6: account ids and movement uids go on spans and logs, NEVER on meters")
                    .noneMatch(tag ->
                            tag.getValue().length() == 36 && tag.getValue().contains("-"));
        }
    }

    private record StubMovements(MovementResult answer) implements RecordMovementUseCase {
        @Override
        public MovementResult deposit(Deposit cmd) {
            return answer;
        }

        @Override
        public MovementResult withdraw(Withdraw cmd) {
            return answer;
        }

        @Override
        public MovementResult transferAsset(com.ffroliva.tinyledger.ledger.application.port.in.AssetTransfer cmd) {
            return answer;
        }
    }

    private record ThrowingMovements(RuntimeException boom) implements RecordMovementUseCase {
        @Override
        public MovementResult deposit(Deposit cmd) {
            throw boom;
        }

        @Override
        public MovementResult withdraw(Withdraw cmd) {
            throw boom;
        }

        @Override
        public MovementResult transferAsset(com.ffroliva.tinyledger.ledger.application.port.in.AssetTransfer cmd) {
            throw boom;
        }
    }
}
