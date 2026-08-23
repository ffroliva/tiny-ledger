package com.ffroliva.tinyledger.ledger.application.usecase;

import com.ffroliva.tinyledger.ledger.application.error.AccountNotFoundException;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.ledger.application.error.TenantIsolationException;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryStrongBalanceUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.StrongBalance;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import com.ffroliva.tinyledger.ledger.domain.Account;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;

public class StrongBalanceService implements QueryStrongBalanceUseCase {
    private final EventStorePort store;
    private final ClockPort clock;
    private final TenantResolverPort tenantResolver;

    public StrongBalanceService(EventStorePort store, ClockPort clock, TenantResolverPort tenantResolver) {
        this.store = store;
        this.clock = clock;
        this.tenantResolver = tenantResolver;
    }

    @Override
    public StrongBalance strongBalance(String caller, AccountId accountId) {
        List<LedgerEvent> history = store.read(accountId);
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history);
        // Tenant first, and independently: this read bypasses the projection, so row-level security
        // can never reach it. Evaluating tenant before ownership is what keeps it non-widenable — a
        // future admin disjunct can only ever relax the ownership term, never this one. A null tenant
        // (a stream opened before tenancy) fails closed rather than matching everyone.
        if (!tenantResolver.currentTenant().equals(account.tenantId())) {
            throw new TenantIsolationException(accountId);
        }
        if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId);
        return new StrongBalance(accountId, account.balance(), clock.now(), account.version());
    }
}
