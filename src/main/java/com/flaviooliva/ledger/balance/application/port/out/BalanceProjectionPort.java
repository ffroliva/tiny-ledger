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
    /** Idempotent on (accountId, version); events ahead of the stream are buffered until the gap fills. */
    void apply(LedgerEvent event);

    Optional<BalanceView> balance(AccountId accountId);

    /** Keyset paged on (transactionTime, transactionUid) DESC. */
    HistoryPage history(AccountId accountId, HistoryQuery query);

    List<AccountView> accountsOwnedBy(String owner);

    Optional<AccountView> account(AccountId accountId);
}
