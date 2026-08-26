package com.ffroliva.tinyledger.ledger.application.usecase;

import com.ffroliva.tinyledger.ledger.application.error.AccountLimitReachedException;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventPublisherPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.port.out.IdGeneratorPort;
import com.ffroliva.tinyledger.ledger.application.port.out.OwnedAccountsPort;
import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import com.ffroliva.tinyledger.ledger.domain.Account;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;

public class OpenAccountService implements OpenAccountUseCase {
    private final EventStorePort store;
    private final EventPublisherPort publisher;
    private final ClockPort clock;
    private final IdGeneratorPort ids;
    private final OwnedAccountsPort ownedAccounts;
    private final TenantResolverPort tenantResolver;
    private final int maxAccountsPerOwner;

    public OpenAccountService(
            EventStorePort store,
            EventPublisherPort publisher,
            ClockPort clock,
            IdGeneratorPort ids,
            OwnedAccountsPort ownedAccounts,
            TenantResolverPort tenantResolver,
            int maxAccountsPerOwner) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.ids = ids;
        this.ownedAccounts = ownedAccounts;
        this.tenantResolver = tenantResolver;
        this.maxAccountsPerOwner = maxAccountsPerOwner;
    }

    @Override
    public OpenedAccount open(OpenAccount cmd) {
        // §6.5: `ledger:writer` is permission to write, not unlimited entitlement to create. Negative
        // turns it off — `standalone` runs as one principal, where a per-OWNER cap caps everything.
        // ponytail: read-then-append, so concurrent opens can land one over. Write budget bounds it;
        // a count constraint in the (synchronous) projection is the upgrade if exactness is needed.
        if (maxAccountsPerOwner >= 0 && ownedAccounts.countOwnedBy(cmd.caller()) >= maxAccountsPerOwner) {
            throw new AccountLimitReachedException(maxAccountsPerOwner);
        }
        AccountId accountId = new AccountId(ids.next());
        List<LedgerEvent> events = Account.open(accountId, cmd, clock.now(), tenantResolver.currentTenant());
        store.append(accountId, 0, events);
        events.forEach(publisher::publish);
        return new OpenedAccount(
                accountId, events.getLast().version(), events.getLast().occurredAt());
    }
}
