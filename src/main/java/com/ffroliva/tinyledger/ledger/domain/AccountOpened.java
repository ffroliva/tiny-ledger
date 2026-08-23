package com.ffroliva.tinyledger.ledger.domain;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.TenantId;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

public record AccountOpened(
        AccountId accountId,
        long version,
        Instant occurredAt,
        String owner,
        String name,
        Currency currency,
        TenantId tenantId)
        implements LedgerEvent {

    /** Stored in {@code events.event_type}. Data, not a name — see {@link LedgerEvent#eventType()}. */
    public static final String TYPE = "AccountOpened";

    @Override
    public String eventType() {
        return TYPE;
    }

    /**
     * {@code owner} is the account's identity, not decoration: {@code RecordMovementService} and
     * {@code StrongBalanceService} both authorise by calling {@code account.owner().equals(caller)}, so a
     * null here is a NullPointerException on the authorisation path — reported by Sonar as two separate
     * S2259 bugs, one per call site, which is the symptom rather than the cause.
     *
     * <p>Guarded here rather than at those two call sites for the reason the ladder gives: one check at
     * the point of construction covers every present and future reader, and a null-safe comparison at
     * each caller would have been two guards that both still permit an ownerless account to exist. Same
     * idiom as {@code Money}'s currency.
     */
    /**
     * {@code tenantId} is deliberately <strong>absent</strong> from this guard, and that is a
     * decision rather than an omission. Every account opened before tenancy existed reads back with
     * no tenant, so requiring one here would make every historical stream unrehydratable — the
     * fail-closed disposition for those accounts belongs on the authorisation path, where it can
     * refuse a read, not in a constructor, where it can only refuse to exist.
     */
    public AccountOpened {
        Objects.requireNonNull(owner, "owner");
    }

    // §15.8: an account has no owner to act on behalf of until it exists — actor is always the owner,
    // never a separate fact, so this is a derivation, not a fourth stamped field.
    @Override
    public String actor() {
        return owner;
    }
}
