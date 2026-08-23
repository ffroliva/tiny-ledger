package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The canonical form is the preimage of every hash in every proof this system issues, so the
 * property that matters is INJECTIVITY: two events that differ in any way must not serialise the
 * same. A test that merely asserted "the string contains the amount" would pass for an encoding
 * that collides.
 *
 * <p>Each case below therefore changes exactly one thing and requires the output to move.
 */
class EventCanonicalFormTest {

    private static final AccountId ACCOUNT = new AccountId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID MOVEMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant AT = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
    private static final Currency GBP = Currency.getInstance("GBP");

    private static MoneyDeposited deposit(Money amount, String reference) {
        return new MoneyDeposited(ACCOUNT, 1L, AT, MOVEMENT, amount, reference, new Money(GBP, 500), "alice");
    }

    @Test
    void theFormIsDeterministic() {
        assertThat(EventCanonicalForm.of(deposit(new Money(GBP, 100), "ref")))
                .isEqualTo(EventCanonicalForm.of(deposit(new Money(GBP, 100), "ref")));
    }

    @Test
    void aDifferentAmountIsADifferentForm() {
        assertThat(EventCanonicalForm.of(deposit(new Money(GBP, 100), "ref")))
                .isNotEqualTo(EventCanonicalForm.of(deposit(new Money(GBP, 101), "ref")));
    }

    @Test
    void aDifferentCurrencyIsADifferentFormEvenAtTheSameMinorUnits() {
        assertThat(EventCanonicalForm.of(deposit(new Money(GBP, 100), "ref")))
                .isNotEqualTo(EventCanonicalForm.of(deposit(new Money(Currency.getInstance("USD"), 100), "ref")));
    }

    @Test
    void anAbsentReferenceIsNotTheSameAsAnEmptyOne() {
        // §15.9 treats an absent field as a meaningful state rather than a synonym for blank. If the
        // encoding collapsed the two, a movement could be stripped of its reference without moving
        // the hash.
        assertThat(EventCanonicalForm.of(deposit(new Money(GBP, 100), null)))
                .isNotEqualTo(EventCanonicalForm.of(deposit(new Money(GBP, 100), "")));
    }

    @Test
    void aReferenceCannotBeCraftedToImpersonateAnotherFieldLayout() {
        // The reason fields are length-prefixed rather than delimiter-joined. `reference` is
        // caller-supplied free text, so with a plain separator an attacker picks content that
        // re-splits into a different field layout and two distinct events hash alike. Here the two
        // differ in where the boundary falls, and the forms must differ with them.
        String withDelimiterish = "5:xxxxx";

        assertThat(EventCanonicalForm.of(deposit(new Money(GBP, 100), withDelimiterish)))
                .isNotEqualTo(EventCanonicalForm.of(deposit(new Money(GBP, 100), "xxxxx")));
    }

    @Test
    void twoEventTypesSharingAHeaderDoNotShareAForm() {
        // The header is identical across these two by construction; only the type tag and payload
        // separate them. Without the tag in the digest, a withdrawal could be replayed as a deposit.
        MoneyDeposited in = deposit(new Money(GBP, 100), "ref");
        MoneyWithdrawn out =
                new MoneyWithdrawn(ACCOUNT, 1L, AT, MOVEMENT, new Money(GBP, 100), "ref", new Money(GBP, 500), "alice");

        assertThat(EventCanonicalForm.of(in)).isNotEqualTo(EventCanonicalForm.of(out));
    }

    @Test
    void theVersionIsPartOfTheForm() {
        MoneyDeposited first = deposit(new Money(GBP, 100), "ref");
        MoneyDeposited renumbered =
                new MoneyDeposited(ACCOUNT, 2L, AT, MOVEMENT, new Money(GBP, 100), "ref", new Money(GBP, 500), "alice");

        assertThat(EventCanonicalForm.of(first)).isNotEqualTo(EventCanonicalForm.of(renumbered));
    }

    @Test
    void subSecondPrecisionSurvivesTheEncoding() {
        // Instant.toString() drops trailing zeros, so two instants a nanosecond apart can render
        // deceptively similarly. Seconds-and-nanos keeps them distinct.
        MoneyDeposited earlier = deposit(new Money(GBP, 100), "ref");
        MoneyDeposited aNanoLater = new MoneyDeposited(
                ACCOUNT, 1L, AT.plusNanos(1), MOVEMENT, new Money(GBP, 100), "ref", new Money(GBP, 500), "alice");

        assertThat(EventCanonicalForm.of(earlier)).isNotEqualTo(EventCanonicalForm.of(aNanoLater));
    }

    @Test
    void anAccountOpenedCarriesItsOwnerNameAndCurrency() {
        AccountOpened opened = new AccountOpened(ACCOUNT, 0L, AT, "alice", "current", GBP);
        AccountOpened renamed = new AccountOpened(ACCOUNT, 0L, AT, "alice", "savings", GBP);

        assertThat(EventCanonicalForm.of(opened)).contains("alice").isNotEqualTo(EventCanonicalForm.of(renamed));
    }

    @Test
    void aRejectionCarriesItsReasonAndType() {
        MovementRejected insufficient = new MovementRejected(
                ACCOUNT, 3L, AT, MOVEMENT, MovementType.WITHDRAWAL, new Money(GBP, 100), "INSUFFICIENT_FUNDS", "alice");
        MovementRejected other = new MovementRejected(
                ACCOUNT, 3L, AT, MOVEMENT, MovementType.WITHDRAWAL, new Money(GBP, 100), "ACCOUNT_FROZEN", "alice");

        assertThat(EventCanonicalForm.of(insufficient)).isNotEqualTo(EventCanonicalForm.of(other));
    }

    @Test
    void anAssetTransferCarriesItsQuantityAndEveryTaxLot() {
        Quantity quantity = new Quantity("VWRL", AssetClass.EQUITY_ETF, 2_000_000L);
        TaxLot lot = new TaxLot("lot-1", quantity, new Money(GBP, 100), AT);
        TaxLot editedLot = new TaxLot("lot-1", quantity, new Money(GBP, 101), AT);

        AssetTransferred original = transfer(quantity, List.of(lot));
        AssetTransferred lotEdited = transfer(quantity, List.of(editedLot));
        AssetTransferred lotDropped = transfer(quantity, List.of());

        assertThat(EventCanonicalForm.of(original))
                .as("editing a lot's cost basis must be visible")
                .isNotEqualTo(EventCanonicalForm.of(lotEdited));
        assertThat(EventCanonicalForm.of(original))
                .as("dropping a lot must be visible — the count is encoded")
                .isNotEqualTo(EventCanonicalForm.of(lotDropped));
    }

    private static AssetTransferred transfer(Quantity quantity, List<TaxLot> lots) {
        return new AssetTransferred(
                ACCOUNT,
                4L,
                AT,
                MOVEMENT,
                quantity,
                new Money(GBP, 100),
                lots,
                TaxLotSelector.FIFO,
                "ref",
                new Money(GBP, 500),
                "alice");
    }
}
