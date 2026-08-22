package com.ffroliva.tinyledger.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A SHA-256 hash of an event's content chained to its predecessor, producing a tamper-evident
 * sequence. Each hash covers the event's canonical representation and the previous hash in the
 * chain, so altering any event invalidates every successor.
 *
 * <p>The genesis hash (first event in a stream) chains from the fixed zero-hash sentinel
 * {@link #GENESIS_PREVIOUS}.
 *
 * <p>Framework-free: only JDK types, per {@code HexagonalRulesTest}'s domain rule.
 */
public record ReasonTraceHash(String value) {

    /** The "previous hash" sentinel for the first event in a chain. */
    public static final String GENESIS_PREVIOUS = "0000000000000000000000000000000000000000000000000000000000000000";

    public ReasonTraceHash {
        Objects.requireNonNull(value, "value");
        if (value.length() != 64) {
            throw new IllegalArgumentException("SHA-256 hex digest must be 64 characters, got " + value.length());
        }
    }

    /**
     * Computes the chained hash: {@code SHA-256(previousHash + "|" + eventContent)}.
     *
     * @param previousHash the hex-encoded SHA-256 of the predecessor, or {@link #GENESIS_PREVIOUS}
     * @param eventContent the canonical string representation of the event
     * @return a new {@code ReasonTraceHash} chaining from the predecessor
     */
    public static ReasonTraceHash chain(String previousHash, String eventContent) {
        Objects.requireNonNull(previousHash, "previousHash");
        Objects.requireNonNull(eventContent, "eventContent");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((previousHash + "|" + eventContent).getBytes(StandardCharsets.UTF_8));
            return new ReasonTraceHash(HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JDK — this is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
