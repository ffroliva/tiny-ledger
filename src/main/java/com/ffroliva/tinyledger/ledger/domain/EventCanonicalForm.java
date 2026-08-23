package com.ffroliva.tinyledger.ledger.domain;

import java.time.Instant;
import java.util.List;

/**
 * The byte-for-byte string an event is hashed as, for {@link ReasonTraceHash} chaining and the
 * {@link MerkleTree} built over it.
 *
 * <p><strong>This is a permanent contract, not an implementation detail.</strong> Once a stream has
 * been hashed, changing what this method produces changes every historical hash and invalidates
 * every proof ever issued. Treat an edit here the way {@link LedgerEvent#eventType()} treats a
 * rename: the output is data, and a new shape is a new field appended, never a reordering.
 *
 * <p><strong>Why not {@code toString()}.</strong> Records generate one for free and it looks like
 * exactly this. It is the wrong choice precisely because it is free: reordering two record
 * components, renaming one, or adding a field silently rewrites the canonical form of every event
 * already written — a refactor with no test failure and no compile error that retroactively breaks
 * every proof. This class makes that edit visible in a diff.
 *
 * <p><strong>Why the switch has no {@code default}.</strong> {@link LedgerEvent} is sealed, so an
 * exhaustive switch means a new event type fails to COMPILE until it declares how it is hashed.
 * That is the same reasoning {@code eventType()} records for itself: a lookup table would have made
 * an unregistered event a test failure, and a default arm would make it a silent one — an event
 * hashed by its common header alone, with its entire payload outside the digest and therefore
 * freely editable without detection.
 *
 * <p><strong>Why fields are length-prefixed.</strong> Concatenating fields with a delimiter is not
 * injective when a field can contain the delimiter, and {@code reference} and {@code reason} are
 * caller-supplied free text. With a plain separator, a deposit referenced {@code "a;100"} and one
 * referenced {@code "a"} with amount {@code 100} could serialise identically — a collision the
 * attacker chooses rather than finds. {@code length:value} needs no escaping and is unambiguous for
 * any content, including content that looks like a length prefix.
 *
 * <p>Framework-free: only JDK types and this module's own domain types.
 */
public final class EventCanonicalForm {

    /**
     * The version of the canonical form {@link #of} produces.
     *
     * <p>v1 carries no discriminator <em>inside</em> its bytes, and cannot be given one: prefixing
     * the preimage would change every hash already computed and make every proof already issued
     * unreproducible. The version therefore travels <strong>outside</strong> the preimage, on the
     * proof artifact, which is what makes a v2 addable rather than breaking.
     *
     * <p>A v2 that includes the event envelope's tenant is anticipated by the ledger tenancy design.
     * When it arrives it is a <em>new</em> method beside this one, never an edit to it, and both are
     * retained permanently so historical proofs stay verifiable.
     */
    public static final int V1 = 1;

    private EventCanonicalForm() {}

    /** The canonical string for one event. Stable across JVMs, locales and releases. */
    public static String of(LedgerEvent event) {
        StringBuilder out = new StringBuilder();

        // The header every event carries, taken from the interface rather than from each record, so
        // these five can never disagree between arms.
        field(out, event.eventType());
        field(out, event.accountId().value().toString());
        field(out, Long.toString(event.version()));
        field(out, instant(event.occurredAt()));
        field(out, event.actor());

        switch (event) {
            case AccountOpened e -> {
                field(out, e.owner());
                field(out, e.name());
                field(out, e.currency().getCurrencyCode());
            }
            case MoneyDeposited e -> {
                field(out, e.movementUid().toString());
                field(out, money(e.amount()));
                field(out, e.reference());
                field(out, money(e.balanceAfter()));
            }
            case MoneyWithdrawn e -> {
                field(out, e.movementUid().toString());
                field(out, money(e.amount()));
                field(out, e.reference());
                field(out, money(e.balanceAfter()));
            }
            case AssetTransferred e -> {
                field(out, e.movementUid().toString());
                field(out, quantity(e.quantity()));
                field(out, money(e.costBasis()));
                lots(out, e.taxLots());
                field(out, e.selector() == null ? null : e.selector().name());
                field(out, e.reference());
                field(out, money(e.balanceAfter()));
            }
            case MovementRejected e -> {
                field(out, e.movementUid().toString());
                field(out, e.type() == null ? null : e.type().name());
                field(out, money(e.amount()));
                field(out, e.reason());
            }
        }
        return out.toString();
    }

    /**
     * {@code length:value}, with {@code -1:} for absent. The negative length is what keeps a null
     * field distinct from an empty one — without it, a movement with no reference and one with
     * {@code ""} would hash identically, and §15.9 already records that an absent field is a
     * meaningful state here rather than a synonym for blank.
     */
    private static void field(StringBuilder out, String value) {
        if (value == null) {
            out.append("-1:");
            return;
        }
        out.append(value.length()).append(':').append(value);
    }

    /**
     * Seconds and nanos separately, never {@code Instant.toString()}: the ISO-8601 rendering omits
     * trailing zeros, so {@code …:00.100Z} and {@code …:00.1Z} are the same instant with two
     * different strings, and which one a digest saw would depend on how the value was built.
     */
    private static String instant(Instant value) {
        return value.getEpochSecond() + "." + value.getNano();
    }

    private static String money(com.ffroliva.tinyledger.shared.Money value) {
        return value == null ? null : value.currency().getCurrencyCode() + " " + value.minorUnits();
    }

    private static String quantity(Quantity value) {
        return value == null ? null : value.symbol() + " " + value.assetClass().name() + " " + value.microUnits();
    }

    /** Count first, then each lot's four components — so a truncated or padded list cannot match. */
    private static void lots(StringBuilder out, List<TaxLot> value) {
        if (value == null) {
            out.append("-1:");
            return;
        }
        field(out, Integer.toString(value.size()));
        for (TaxLot lot : value) {
            field(out, lot.lotId());
            field(out, quantity(lot.remaining()));
            field(out, money(lot.costBasis()));
            field(out, instant(lot.acquiredAt()));
        }
    }
}
