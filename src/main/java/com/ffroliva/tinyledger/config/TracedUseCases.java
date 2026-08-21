package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Spec §6.6: <strong>domain spans are added by decoration, not by annotation.</strong> This is the
 * same shape, in the same package, as {@link TransactionalUseCases} — and for the same reason:
 * {@code ..application..} imports no Micrometer type, exactly as it imports no {@code @Transactional}.
 * {@code HexagonalRulesTest} is the authority, and it stays green because nothing here leaks inward.
 *
 * <p><strong>This is the OUTERMOST decorator</strong> — {@code traced -> transactional -> service}. A
 * span that ends before the commit reports a write as faster than it is, which is the same class of
 * defect as summing a gauge that should be maxed: a plausible number that is false, and the hardest
 * kind to notice on a chart. That ordering is why {@code FullAdapterConfig}'s transactional beans
 * give up {@code @Primary} and declare their concrete types — two {@code @Primary} candidates for one
 * interface is a context-startup failure, not a warning.
 *
 * <p><strong>Cardinality (§6.6, and there is no gate).</strong> The account id, the movement uid and
 * the interaction id go on the SPAN. This counter's tags are {@code type}, {@code outcome} and
 * {@code reason} — three enumerable sets whose product is about twenty series and does not grow with
 * traffic. {@code reason} is {@code none} rather than absent on a settled movement, so every series
 * of this meter carries the same tag keys.
 *
 * <p>{@code reason}'s values are domain literals — today {@code currency-mismatch} and
 * {@code insufficient-funds}, both from {@code Account}. Interpolating a request detail into one of
 * those strings would make this counter unbounded, and nothing would catch it.
 */
final class TracedUseCases {

    static final String MOVEMENTS = "ledger.movements";

    private TracedUseCases() {}

    static class Opening implements OpenAccountUseCase {
        private final OpenAccountUseCase delegate;
        private final Tracer tracer;

        Opening(OpenAccountUseCase delegate, Tracer tracer) {
            this.delegate = delegate;
            this.tracer = tracer;
        }

        @Override
        public OpenedAccount open(OpenAccount cmd) {
            Span span = tracer.nextSpan().name("ledger.open-account").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                OpenedAccount opened = delegate.open(cmd);
                span.tag("ledger.account_id", opened.accountId().value().toString());
                span.tag("ledger.stream_version", Long.toString(opened.version()));
                return opened;
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        }
    }

    static class Movements implements RecordMovementUseCase {
        private final RecordMovementUseCase delegate;
        private final Tracer tracer;
        private final MeterRegistry meters;

        Movements(RecordMovementUseCase delegate, Tracer tracer, MeterRegistry meters) {
            this.delegate = delegate;
            this.tracer = tracer;
            this.meters = meters;
        }

        @Override
        public MovementResult deposit(Deposit cmd) {
            return record(cmd.accountId().value().toString(), () -> delegate.deposit(cmd));
        }

        @Override
        public MovementResult withdraw(Withdraw cmd) {
            return record(cmd.accountId().value().toString(), () -> delegate.withdraw(cmd));
        }

        @Override
        public MovementResult transferAsset(com.ffroliva.tinyledger.ledger.application.port.in.AssetTransfer cmd) {
            return record(cmd.accountId().value().toString(), () -> delegate.transferAsset(cmd));
        }

        private MovementResult record(String accountId, Supplier<MovementResult> call) {
            Span span = tracer.nextSpan().name("ledger.record-movement").start();
            // Tagged before the call, not after: if the delegate throws, this is the one attribute
            // that says WHICH account the failure was about, and a span tagged only on the happy
            // path is missing exactly when it is needed.
            span.tag("ledger.account_id", accountId);
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                MovementResult result = call.get();
                span.tag("ledger.movement_type", result.type().name());
                span.tag("ledger.stream_version", Long.toString(result.version()));
                if (result.rejectionReason() != null) {
                    span.tag("ledger.rejection_reason", result.rejectionReason());
                }
                count(result.type().name(), result.outcome().name().toLowerCase(Locale.ROOT), reasonOf(result));
                return result;
            } catch (ConcurrencyConflictException e) {
                // Its own outcome rather than folded into an error rate: §6.6 asks for a
                // concurrency-conflict rate by name, and a conflict is an ordinary, expected outcome
                // of an optimistic append — not a fault. The type is unknown because the append never
                // got far enough to produce a MovementResult, and inventing one would be worse than
                // saying so.
                span.error(e);
                count("unknown", "conflict", "none");
                throw e;
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        }

        private static String reasonOf(MovementResult result) {
            return result.rejectionReason() == null ? "none" : result.rejectionReason();
        }

        private void count(String type, String outcome, String reason) {
            Counter.builder(MOVEMENTS)
                    .description("Movements recorded, by type and outcome (spec §6.6)")
                    .tag("type", type)
                    .tag("outcome", outcome)
                    .tag("reason", reason)
                    .register(meters)
                    .increment();
        }
    }
}
