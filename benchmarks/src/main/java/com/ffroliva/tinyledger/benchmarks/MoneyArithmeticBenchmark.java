package com.ffroliva.tinyledger.benchmarks;

import com.ffroliva.tinyledger.shared.Money;
import java.util.Currency;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * §9.7's first JMH target: {@code Money} arithmetic.
 *
 * <p>Worth measuring rather than assumed cheap. {@code Money} is a record over a {@link Currency} and a
 * {@code long}, and every {@code plus}/{@code minus} allocates a new one — so this is really a question
 * about allocation and escape analysis, not about addition. It sits on the hot path of every movement
 * and of every replay, which is the other benchmark in this suite.
 *
 * <p>{@code requireSameCurrency} is included deliberately: it is the guard the arithmetic actually runs
 * in production, and benchmarking {@code plus} without it would measure a method that does not exist.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MoneyArithmeticBenchmark {

    private Money a;
    private Money b;

    @Setup
    public void setup() {
        a = Money.of("GBP", 1_000_000L);
        b = Money.of("GBP", 250L);
    }

    @Benchmark
    public Money plus() {
        return a.plus(b);
    }

    @Benchmark
    public Money minus() {
        return a.minus(b);
    }

    /**
     * A chain, because a single {@code plus} is trivially scalar-replaced by C2 and would report a
     * time that no real caller sees. A running balance is accumulated across a movement chain, which
     * is what the read path and {@code Account.rehydrate} both do.
     */
    @Benchmark
    public void runningBalanceOfTenMovements(Blackhole bh) {
        Money running = a;
        for (int i = 0; i < 10; i++) {
            running = running.plus(b);
        }
        bh.consume(running);
    }

    /**
     * {@code Currency.getInstance} is a map lookup with an internal lock in some JDKs, and
     * {@code Money.of} calls it on every construction from the API layer. If this is materially
     * slower than {@code plus}, the cost of a movement is parsing its currency code, not its
     * arithmetic — a conclusion worth having before optimising the wrong half.
     */
    @Benchmark
    public Money constructionFromCurrencyCode() {
        return Money.of("GBP", 1_234L);
    }
}
