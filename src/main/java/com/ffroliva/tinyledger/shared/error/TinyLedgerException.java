package com.ffroliva.tinyledger.shared.error;

/**
 * Supertype for every failure this system reports to a caller by a catalogued code (§6.5).
 *
 * <p>Named for the system rather than the {@code ledger} module, because
 * {@code com.ffroliva.tinyledger.ledger} exists and a {@code LedgerException} would read as that
 * module's exception when this is the supertype for {@code audit}, {@code balance} and
 * {@code notification} too.
 *
 * <p>Carries no framework type and no HTTP status of its own — only an {@link ErrorCode}. A CLI or a
 * Kafka consumer driving the same use case can catch this and read the code without inheriting a
 * notion of "404". The single translation to RFC 7807 lives in {@code platform}.
 */
public abstract class TinyLedgerException extends RuntimeException {

    private final ErrorCode code;
    private final Object[] args;

    protected TinyLedgerException(ErrorCode code, String message, Object... args) {
        super(message);
        this.code = code;
        this.args = args.clone();
    }

    public ErrorCode code() {
        return code;
    }

    public Object[] args() {
        return args.clone();
    }
}
