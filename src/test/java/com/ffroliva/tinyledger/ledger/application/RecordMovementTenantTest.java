package com.ffroliva.tinyledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.ffroliva.tinyledger.ledger.application.error.TenantIsolationException;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.Outcome;
import com.ffroliva.tinyledger.ledger.application.usecase.RecordMovementService;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import com.ffroliva.tinyledger.shared.TenantId;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The write-path twin of {@link StrongBalanceTenantTest}. The strong read got the tenant term first;
 * this pins the same term, with the same semantics, in front of {@code RecordMovementService}'s
 * ownership check — a command must never cross a tenant boundary that a query already refuses.
 *
 * <p>The ordering matters as much as the presence, and more so here than on the read: the write
 * path's ownership term carries an admin disjunct ({@code ownerMatches || callerIsAdmin}). Tenant is
 * evaluated <strong>before and independently of</strong> that term, so the admin role widens
 * ownership only — never the tenant boundary.
 */
class RecordMovementTenantTest {

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

    private RecordMovementService servingTenant(TenantId tenant) {
        return new RecordMovementService(store, event -> {}, () -> NOW, () -> tenant);
    }

    private Deposit deposit(String caller, boolean admin, AccountId id) {
        return new Deposit(caller, admin, id, UUID.randomUUID(), new Money(GBP, 100), null);
    }

    @Test
    void aCallerInAnotherTenantIsRefusedEvenWhenTheOwnerStringMatches() {
        // The seed contract keeps tenant and owner independent, so one owner string may exist in two
        // tenants. That is precisely the case an owner-only check waves through.
        AccountId id = accountOwnedBy("alice", TENANT_A);

        assertThatThrownBy(() -> servingTenant(TENANT_B).deposit(deposit("alice", false, id)))
                .isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void aCallerInTheAccountsTenantIsServed() {
        AccountId id = accountOwnedBy("alice", TENANT_A);

        assertThat(servingTenant(TENANT_A).deposit(deposit("alice", false, id)).outcome())
                .isEqualTo(Outcome.CREATED);
    }

    @Test
    void anAccountWithNoTenantIsUnwritableRatherThanOpenToEveryone() {
        // Every account opened before tenancy existed reads back with a null tenant. Fail closed,
        // exactly as the strong read does: absent must never mean "matches whoever asks".
        AccountId legacy = accountOwnedBy("alice", null);

        assertThatThrownBy(() -> servingTenant(TENANT_A).deposit(deposit("alice", false, legacy)))
                .isInstanceOf(TenantIsolationException.class);
    }

    @Test
    void anAdminInAnotherTenantIsRefusedAsATenantViolation() {
        // The property the ordering exists for: callerIsAdmin widens the ownership term and only the
        // ownership term. Evaluated after tenant, it cannot reach across the boundary.
        AccountId id = accountOwnedBy("alice", TENANT_A);

        assertThatThrownBy(() -> servingTenant(TENANT_B).deposit(deposit("root", true, id)))
                .isInstanceOf(TenantIsolationException.class);
    }
}
