package com.ffroliva.tinyledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.usecase.OpenAccountService;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.MovementEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §7: the {@code Account} response owes its caller a {@code createdAt}, and it must be the recorded one. */
class OpenAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID ID = UUID.fromString("f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50");

    private final List<LedgerEvent> published = new ArrayList<>();
    private final OpenAccountService service =
            new OpenAccountService(new NoStore(), published::add, () -> NOW, () -> ID);

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

    /** Opening reads nothing and cannot conflict; only the returned value and the published event matter here. */
    private static final class NoStore implements EventStorePort {
        @Override
        public void append(AccountId streamId, long expectedVersion, List<? extends LedgerEvent> events) {
            // Intentionally inert: this fake exists to prove OpenAccountService's behaviour when the
            // store accepts everything, so recording the append would only add state nothing asserts.
        }

        @Override
        public List<LedgerEvent> read(AccountId streamId) {
            return List.of();
        }

        @Override
        public Optional<MovementEvent> findByMovementUid(UUID movementUid) {
            return Optional.empty();
        }
    }
}
