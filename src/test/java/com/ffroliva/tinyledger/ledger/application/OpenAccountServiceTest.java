package com.ffroliva.tinyledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.ledger.application.error.AccountLimitReachedException;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.out.EventPage;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.usecase.OpenAccountService;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.MovementEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §7: the {@code Account} response owes its caller a {@code createdAt}, and it must be the recorded one. */
class OpenAccountServiceTest {

    private static final com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort TENANT =
            () -> TenantId.of("t-test");

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID ID = UUID.fromString("f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50");

    private static final int LIMIT = 3;

    private final List<LedgerEvent> published = new ArrayList<>();
    private final List<AccountId> appended = new ArrayList<>();
    private int owned;

    private OpenAccountService serviceHolding(int existingAccounts) {
        owned = existingAccounts;
        return new OpenAccountService(
                new RecordingStore(), published::add, () -> NOW, () -> ID, owner -> owned, TENANT, LIMIT);
    }

    private final OpenAccountService service = serviceHolding(0);

    @Test
    void createdAtIsTheRecordedEventTimeNotTheReadTime() {
        OpenedAccount opened = service.open(new OpenAccount("alice", "ACC-001", Currency.getInstance("GBP")));

        assertThat(published).singleElement().isInstanceOf(AccountOpened.class);
        assertThat(opened.createdAt())
                .isEqualTo(published.getFirst().occurredAt())
                .isEqualTo(NOW);
        assertThat(opened.accountId()).isEqualTo(new AccountId(ID));
        assertThat(opened.version()).isEqualTo(1);
    }

    @Test // §6.5: the bank decides how many accounts a caller may self-open; a valid token is not consent
    void openingIsRefusedOnceTheOwnerHoldsTheLimit() {
        OpenAccountService atLimit = serviceHolding(LIMIT);

        assertThatThrownBy(() -> atLimit.open(new OpenAccount("alice", "ACC-004", Currency.getInstance("GBP"))))
                .isInstanceOf(AccountLimitReachedException.class);

        // The refusal must land BEFORE the append, or the stream carries an account the caller may not have.
        assertThat(appended).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test // §1/§6.5: `standalone` authenticates nobody and runs as ONE fixed principal, so a per-owner
    // cap there is a cap on the whole system. A negative limit turns the rule off; the profile sets it.
    void aNegativeLimitTurnsTheRuleOff() {
        OpenAccountService unlimited = new OpenAccountService(
                new RecordingStore(), published::add, () -> NOW, () -> ID, owner -> 9_999, TENANT, -1);

        OpenedAccount opened = unlimited.open(new OpenAccount("local", "ACC-10000", Currency.getInstance("GBP")));

        assertThat(opened.accountId()).isEqualTo(new AccountId(ID));
    }

    @Test // the boundary itself: the limit is a maximum held, not a maximum reachable
    void openingIsAllowedOnTheLastFreeSlot() {
        OpenAccountService oneBelow = serviceHolding(LIMIT - 1);

        OpenedAccount opened = oneBelow.open(new OpenAccount("alice", "ACC-003", Currency.getInstance("GBP")));

        assertThat(opened.accountId()).isEqualTo(new AccountId(ID));
        assertThat(appended).containsExactly(new AccountId(ID));
    }

    /** Opening reads nothing and cannot conflict; only the returned value and the published event matter here. */
    private final class RecordingStore implements EventStorePort {
        @Override
        public void append(AccountId streamId, long expectedVersion, List<? extends LedgerEvent> events) {
            appended.add(streamId);
        }

        @Override
        public List<LedgerEvent> read(AccountId streamId) {
            return List.of();
        }

        /**
         * Refuses rather than returning an empty page. This fake exists to record appends; a
         * silent empty answer would let a future rebuild test pass while reading nothing.
         */
        @Override
        public EventPage readAll(long fromGlobalIndex, int limit) {
            throw new UnsupportedOperationException("RecordingStore does not implement readAll");
        }

        @Override
        public Optional<MovementEvent> findByMovementUid(UUID movementUid) {
            return Optional.empty();
        }
    }
}
