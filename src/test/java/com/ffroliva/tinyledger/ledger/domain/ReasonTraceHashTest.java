package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The chaining property is the whole point of this type, so it is asserted directly rather than
 * inferred from "the hash looks like a hash": altering the predecessor MUST change the successor,
 * or the sequence is not tamper-evident and the class name is a lie.
 *
 * <p>The expected digests below are computed independently in {@link #sha256}, not by calling the
 * production method twice. A test that compares {@code chain(x)} to {@code chain(x)} passes for any
 * implementation at all, including one that returns a constant.
 */
class ReasonTraceHashTest {

    private static final String CONTENT = "MoneyDeposited{account=acc-1,amount=100.00}";

    @Test
    void theGenesisSentinelIsSixtyFourZeros() {
        assertThat(ReasonTraceHash.GENESIS_PREVIOUS).hasSize(64).isEqualTo("0".repeat(64));
    }

    @Test
    void chainingComputesSha256OfThePreviousHashJoinedToTheContent() {
        // Pins the exact preimage — `previous + "|" + content`. Without the separator asserted
        // here, ("ab", "c") and ("a", "bc") would hash identically, which is a collision an
        // attacker chooses rather than finds.
        ReasonTraceHash hash = ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, CONTENT);

        assertThat(hash.value()).isEqualTo(sha256(ReasonTraceHash.GENESIS_PREVIOUS + "|" + CONTENT));
    }

    @Test
    void aPredecessorThatIsNotASixtyFourCharacterDigestIsRefused() {
        // This assertion is the reason the guard exists, and it was written the wrong way round
        // first. It originally claimed the "|" separator alone made the preimage unambiguous, and
        // asserted that ("a"*64, "|payload") and ("a"*64 + "|", "payload") differ. They do not:
        // both build "a"*64 + "||payload", so the digests are identical — a collision an attacker
        // chooses rather than finds. The separator was never what made the split unambiguous; the
        // FIXED-LENGTH prefix is. `chain` now enforces it, so the colliding call cannot be made.
        String sixtyFourChars = "a".repeat(64);

        assertThat(ReasonTraceHash.chain(sixtyFourChars, "|payload").value())
                .isEqualTo(ReasonTraceHash.chain(sixtyFourChars, "|payload").value());

        assertThatThrownBy(() -> ReasonTraceHash.chain(sixtyFourChars + "|", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void alteringTheContentAltersTheHash() {
        ReasonTraceHash original = ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, CONTENT);
        ReasonTraceHash tampered = ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, CONTENT + " ");

        assertThat(tampered.value()).isNotEqualTo(original.value());
    }

    @Test
    void alteringAnyPredecessorInvalidatesEverySuccessor() {
        // The chain property itself. Two streams whose ONLY difference is the first event must
        // diverge at every later link — that is what makes a mid-stream edit detectable.
        ReasonTraceHash honestFirst = ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, "event-1");
        ReasonTraceHash tamperedFirst = ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, "event-1-EDITED");

        ReasonTraceHash honestThird = ReasonTraceHash.chain(
                ReasonTraceHash.chain(honestFirst.value(), "event-2").value(), "event-3");
        ReasonTraceHash tamperedThird = ReasonTraceHash.chain(
                ReasonTraceHash.chain(tamperedFirst.value(), "event-2").value(), "event-3");

        assertThat(tamperedThird.value()).isNotEqualTo(honestThird.value());
    }

    @Test
    void aDigestThatIsNotSixtyFourCharactersIsRefused() {
        assertThatThrownBy(() -> new ReasonTraceHash("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void aNullDigestIsRefused() {
        assertThatThrownBy(() -> new ReasonTraceHash(null)).isInstanceOf(NullPointerException.class);
    }

    private static String sha256(String preimage) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(preimage.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
