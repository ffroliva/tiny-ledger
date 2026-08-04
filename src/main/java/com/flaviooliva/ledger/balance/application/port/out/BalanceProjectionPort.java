package com.flaviooliva.ledger.balance.application.port.out;

import com.flaviooliva.ledger.balance.application.port.in.AccountView;
import com.flaviooliva.ledger.balance.application.port.in.BalanceView;
import com.flaviooliva.ledger.balance.application.port.in.HistoryPage;
import com.flaviooliva.ledger.balance.application.port.in.HistoryQuery;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.shared.AccountId;
import java.util.List;
import java.util.Optional;

public interface BalanceProjectionPort {
    /**
     * Folds one event into the read model.
     *
     * <p>Ordering is the caller's job: an implementation may assume events arrive in stream order per
     * account. Delivery is at-least-once, so the call must be idempotent on (accountId, version) —
     * a redelivered event changes nothing.
     *
     * <p>{@link com.flaviooliva.ledger.balance.adapter.out.inmemory.InMemoryBalanceProjection} also
     * buffers events that arrive ahead of the stream until the gap fills. That is a convenience of
     * that implementation, not part of this contract:
     * {@link com.flaviooliva.ledger.balance.adapter.out.postgres.PostgresBalanceProjection} does not
     * buffer, because it is fed synchronously and in order inside the append transaction (ADR 0001).
     */
    void apply(LedgerEvent event);

    Optional<BalanceView> balance(AccountId accountId);

    /** Keyset paged on (transactionTime, transactionUid) DESC. */
    HistoryPage history(AccountId accountId, HistoryQuery query);

    List<AccountView> accountsOwnedBy(String owner);

    Optional<AccountView> account(AccountId accountId);
}
