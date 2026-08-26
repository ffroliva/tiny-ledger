package com.ffroliva.tinyledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.ffroliva.tinyledger.ledger.application.error.TenantIsolationException;
import com.ffroliva.tinyledger.ledger.application.usecase.StrongBalanceService;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.TenantId;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The strong read is the path two revisions of the tenancy design forgot, in the same paragraph that
 * named it. It bypasses the projection by construction, so row-level security can never reach it —
 * whatever isolation it has, it applies itself.
 *
 * <p>The ordering matters as much as the presence: tenant is evaluated <strong>before and
 * independently of</strong> ownership, so no future role disjunct can widen past it. A design where
 * {@code isAdmin || ownerMatches} sits in front of the tenant check is a design where one role term
 * reaches every tenant.
 */
class StrongBalanceTenantTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final TenantId TENANT_A = TenantId.of("tenant-a");
    private static final TenantId TENANT_B = TenantId.of("tenant-b");

    private final InMemoryEventStore store = new InMemoryEventStore();

    private AccountId accountOwnedBy(String owner, TenantId tenant) {
        AccountId id = AccountId.random();
        store.append(id, 0, List.of(new AccountOpened(id, 1, NOW, owner, "ACC", GBP, tenant)));
        return id;
    }

    private StrongBalanceService servingTenant(TenantId tenant) {
        return new StrongBalanceService(store, () -> NOW, () -> tenant);
    }

    @Test
    void aCallerInAnotherTenantIsRefusedEvenWhenTheOwnerStringMatches() {
        // The seed contract keeps tenant and owner independent, so one owner string may exist in two
        // tenants. That is precisely the case an owner-only check waves through.
        AccountId id = accountOwnedBy("alice", TENANT_A);

        assertThatThrownBy(() -> servingTenant(TENANT_B).strongBalance("alice", id))
                .isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void aCallerInTheAccountsTenantIsServed() {
        AccountId id = accountOwnedBy("alice", TENANT_A);

        assertThat(servingTenant(TENANT_A).strongBalance("alice", id).accountId())
                .isEqualTo(id);
    }

    @Test
    void anAccountWithNoTenantIsUnreadableRatherThanOpenToEveryone() {
        // Every account opened before tenancy existed reads back with a null tenant. Fail closed: the
        // alternative — treating absent as "matches whoever asks" — makes the whole legacy estate
        // world-readable the moment the tenant term ships.
        AccountId legacy = accountOwnedBy("alice", null);

        assertThatThrownBy(() -> servingTenant(TENANT_A).strongBalance("alice", legacy))
                .isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void tenantIsCheckedBeforeOwnership() {
        // A foreign-tenant caller who is also not the owner must be refused as a tenant violation, not
        // an ownership one. If ownership were evaluated first, the error would leak that the account
        // exists in another tenant — and any later admin disjunct on the ownership branch would widen
        // straight across the boundary.
        AccountId id = accountOwnedBy("alice", TENANT_A);

        assertThatThrownBy(() -> servingTenant(TENANT_B).strongBalance("mallory", id))
                .isInstanceOf(TenantIsolationException.class);
    }
}
