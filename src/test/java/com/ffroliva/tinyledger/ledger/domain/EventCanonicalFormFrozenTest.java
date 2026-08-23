package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Golden vectors for {@link EventCanonicalForm}, which is the preimage every Merkle proof is built
 * from and therefore a permanent contract: changing what it produces invalidates every proof ever
 * issued.
 *
 * <p><strong>Why literals rather than a snapshot.</strong> A characterisation test that captures
 * whatever the code currently emits agrees with the code by construction, including when the code is
 * wrong. These expectations are built by hand from the documented encoding — {@code length:value},
 * {@code -1:} for absent, fields concatenated with no separator — so they fail if the *format*
 * changes, not merely if the output does.
 *
 * <p>This is the v1 codec. It is frozen here so that adding tenant to the hashed preimage has to
 * happen as an explicit v2, rather than as a silent edit that re-roots every existing proof.
 */
class EventCanonicalFormFrozenTest {

    private static final AccountId ID = new AccountId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final UUID MOVEMENT = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    /** Chosen so the encoded instant is exactly "1700000000.123456789" — 20 characters, no date maths. */
    private static final Instant WHEN = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);

    private static final String HEADER_ID = "36:00000000-0000-0000-0000-000000000001";
    private static final String HEADER_WHEN = "20:1700000000.123456789";
    private static final Currency GBP = Currency.getInstance("GBP");

    @Test
    void accountOpenedIsFrozen() {
        AccountOpened event = new AccountOpened(ID, 1, WHEN, "alice", "ACC-001", GBP);

        assertThat(EventCanonicalForm.of(event))
                .isEqualTo("13:AccountOpened" + HEADER_ID + "1:1" + HEADER_WHEN + "5:alice" // actor, derived from owner
                        + "5:alice" // owner
                        + "7:ACC-001"
                        + "3:GBP");
    }

    @Test
    void moneyDepositedIsFrozen() {
        MoneyDeposited event =
                new MoneyDeposited(ID, 2, WHEN, MOVEMENT, new Money(GBP, 100), "rent", new Money(GBP, 500), "bob");

        assertThat(EventCanonicalForm.of(event))
                .isEqualTo("14:MoneyDeposited" + HEADER_ID + "1:2" + HEADER_WHEN + "3:bob"
                        + "36:00000000-0000-0000-0000-0000000000ff"
                        + "7:GBP 100"
                        + "4:rent"
                        + "7:GBP 500");
    }

    @Test
    void moneyWithdrawnIsFrozen() {
        // reference is null here on purpose: it freezes the -1 sentinel in a full-event context,
        // not just in the property test below.
        MoneyWithdrawn event =
                new MoneyWithdrawn(ID, 3, WHEN, MOVEMENT, new Money(GBP, 40), null, new Money(GBP, 460), "bob");

        assertThat(EventCanonicalForm.of(event))
                .isEqualTo("14:MoneyWithdrawn" + HEADER_ID + "1:3" + HEADER_WHEN + "3:bob"
                        + "36:00000000-0000-0000-0000-0000000000ff"
                        + "6:GBP 40"
                        + "-1:"
                        + "7:GBP 460");
    }

    @Test
    void assetTransferredIsFrozen() {
        // The most complex arm: nested lot encoding (count first, then each lot's four components),
        // the Quantity triple, and the selector's enum name. The likeliest arm to be refactored, and
        // therefore the one most in need of a hand-built vector.
        TaxLot lot = new TaxLot(
                "lot-1", new Quantity("VOO", AssetClass.EQUITY_ETF, 1_500_000), new Money(GBP, 60_000), WHEN);
        AssetTransferred event = new AssetTransferred(
                ID,
                4,
                WHEN,
                MOVEMENT,
                new Quantity("VOO", AssetClass.EQUITY_ETF, 2_500_000),
                new Money(GBP, 100_000),
                java.util.List.of(lot),
                TaxLotSelector.HIFO,
                "rebalance",
                new Money(GBP, 500),
                "carol");

        assertThat(EventCanonicalForm.of(event))
                .isEqualTo("16:AssetTransferred" + HEADER_ID + "1:4" + HEADER_WHEN + "5:carol"
                        + "36:00000000-0000-0000-0000-0000000000ff"
                        + "22:VOO EQUITY_ETF 2500000"
                        + "10:GBP 100000"
                        + "1:1" // lot count, then the lot's four components
                        + "5:lot-1"
                        + "22:VOO EQUITY_ETF 1500000"
                        + "9:GBP 60000"
                        + HEADER_WHEN
                        + "4:HIFO"
                        + "9:rebalance"
                        + "7:GBP 500");
    }

    @Test
    void movementRejectedIsFrozen() {
        MovementRejected event = new MovementRejected(
                ID, 5, WHEN, MOVEMENT, MovementType.WITHDRAWAL, new Money(GBP, 999_999), "insufficient funds", "dave");

        assertThat(EventCanonicalForm.of(event))
                .isEqualTo("16:MovementRejected" + HEADER_ID + "1:5" + HEADER_WHEN + "4:dave"
                        + "36:00000000-0000-0000-0000-0000000000ff"
                        + "10:WITHDRAWAL"
                        + "10:GBP 999999"
                        + "18:insufficient funds");
    }

    @Test
    void anAbsentFieldIsDistinctFromAnEmptyOne() {
        // The property the -1 sentinel exists for. Without it a movement with no reference and one
        // with "" would hash identically, and an absent field is a meaningful state here.
        MoneyDeposited absent =
                new MoneyDeposited(ID, 2, WHEN, MOVEMENT, new Money(GBP, 100), null, new Money(GBP, 500), "bob");
        MoneyDeposited empty =
                new MoneyDeposited(ID, 2, WHEN, MOVEMENT, new Money(GBP, 100), "", new Money(GBP, 500), "bob");

        assertThat(EventCanonicalForm.of(absent)).contains("-1:").isNotEqualTo(EventCanonicalForm.of(empty));
        assertThat(EventCanonicalForm.of(empty)).contains("0:");
    }

    @Test
    void theVersionIsAnExplicitConstantRatherThanAnAssumption() {
        // v1 carries no discriminator inside its bytes — it cannot, without changing every existing
        // hash. The version therefore has to travel OUTSIDE the preimage, on the artifact, which is
        // what makes a future v2 addable rather than breaking.
        assertThat(EventCanonicalForm.V1).isEqualTo(1);
    }
}
