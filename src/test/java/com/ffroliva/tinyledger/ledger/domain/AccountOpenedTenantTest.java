package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.TenantId;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 0 of the ledger tenancy design: {@code AccountOpened} binds the tenant.
 *
 * <p>Three properties, and the third is the one that is cheap to break silently. The tenancy design
 * requires the tenant dimension to be real while its <em>value</em> is still stubbed, which means
 * this record gains a component before anything can populate it — so the component is nullable, and
 * every payload already in {@code events} must still read.
 */
class AccountOpenedTenantTest {

    private static final Instant WHEN = Instant.parse("2026-08-01T00:00:00Z");

    private static AccountOpened opened(TenantId tenantId) {
        return new AccountOpened(
                AccountId.random(), 1, WHEN, "alice", "current", Currency.getInstance("GBP"), tenantId);
    }

    @Test
    void tenantIsAbsentFromTheNullGuardBecauseLegacyStreamsHaveNone() {
        // owner stays guarded — it is the authorisation path. tenant cannot be, or every historical
        // account becomes unconstructible the moment this component exists.
        assertThatNoException().isThrownBy(() -> opened(null));
        assertThatThrownBy(() -> new AccountOpened(
                        AccountId.random(), 1, WHEN, null, "current", Currency.getInstance("GBP"), TenantId.of("t-1")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aPayloadWithNoTenantKeyDeserialisesWithTenantAbsent() {
        // The design's safety rested on inference from the MoneyDeposited actor test; this proves it
        // for AccountOpened specifically, which is the record that actually gains the component.
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode legacy = (ObjectNode) mapper.readTree(mapper.writeValueAsString(opened(TenantId.of("t-1"))));
        legacy.remove("tenantId");

        AccountOpened deserialised = mapper.treeToValue(legacy, AccountOpened.class);

        assertThat(deserialised.tenantId()).isNull();
        assertThat(deserialised.owner()).isEqualTo("alice");
    }

    @Test
    void addingTenantDoesNotChangeTheCanonicalFormThatMerkleProofsAreBuiltOn() {
        // EventCanonicalForm is a frozen contract: changing what it produces invalidates every proof
        // ever issued. It lists fields explicitly rather than using toString precisely so that adding
        // a component cannot shift it — this pins that, because the tenancy design's own rule is that
        // tenant enters the hash only under an explicit v2 codec, never by accident under v1.
        AccountOpened withTenant = opened(TenantId.of("t-1"));
        AccountOpened withoutTenant = new AccountOpened(
                withTenant.accountId(), 1, WHEN, "alice", "current", Currency.getInstance("GBP"), null);

        assertThat(EventCanonicalForm.of(withTenant)).isEqualTo(EventCanonicalForm.of(withoutTenant));
    }
}
