package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryPage;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryQuery;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.Optional;

/**
 * §6.4: authorisation is a use-case concern, applied at the port boundary in the composition root —
 * the same shape as {@link TransactionalUseCases}, and for the same reason: {@code application} carries
 * no framework annotations (ArchUnit), and a controller cannot make this decision because the ownership
 * half of the rule needs the read model.
 *
 * <p><strong>Only the two projection-backed reads are decorated.</strong> {@code RecordMovementService} and
 * {@code StrongBalanceService} keep their in-service ownership check, because that check authorises against
 * the <em>rehydrated aggregate</em> — the event stream, the system of record — which a decorator running
 * before the stream is read cannot see. Moving it here would silently demote a money path's authority to the
 * read model, and it would turn an unknown account into a 403 where §6.5 requires 404, because a boundary
 * check on the write path cannot tell absent from unowned before the stream is loaded. §6.3's
 * authorise-before-idempotency is an ordering <em>inside</em> that service, which it already satisfies.
 * §6.4 of the spec is amended to describe this split.
 *
 * <p>It throws {@link OwnershipException}, never Spring's {@code AccessDeniedException}. Measured
 * 2026-08-05: a Spring denial thrown from inside a controller invocation is claimed by
 * {@code ErrorHandlingAdvice}'s catch-all and returned as an opaque 500, because {@code @ExceptionHandler}
 * resolves it before {@code ExceptionTranslationFilter} sees it — a correctly-refused request would look
 * like a server fault. {@code OwnershipException} carries {@code ErrorCode.FORBIDDEN} and answers 403.
 */
final class AuthorizedUseCases {

    private AuthorizedUseCases() {}

    /**
     * §6.5: absent and unowned are different answers. An account that does not exist is a 404, produced by
     * the delegate returning empty — not a 403. Only a real account with a different owner is refused.
     *
     * <p>This is also why it asks for one account rather than listing everything the caller owns: a
     * membership scan would both lose that distinction and put an owner-wide query in front of every single
     * balance and history read.
     */
    private static void requireOwner(QueryAccountsUseCase accounts, String caller, AccountId accountId) {
        Optional<AccountView> account = accounts.account(accountId);
        if (account.isEmpty()) return; // let the delegate answer 404
        if (!account.get().owner().equals(caller)) throw new OwnershipException(caller, accountId);
    }

    static class Balances implements QueryBalanceUseCase {
        private final QueryBalanceUseCase delegate;
        private final QueryAccountsUseCase accounts;

        Balances(QueryBalanceUseCase delegate, QueryAccountsUseCase accounts) {
            this.delegate = delegate;
            this.accounts = accounts;
        }

        @Override
        public Optional<BalanceView> balance(String caller, AccountId accountId) {
            requireOwner(accounts, caller, accountId);
            return delegate.balance(caller, accountId);
        }
    }

    static class History implements QueryHistoryUseCase {
        private final QueryHistoryUseCase delegate;
        private final QueryAccountsUseCase accounts;

        History(QueryHistoryUseCase delegate, QueryAccountsUseCase accounts) {
            this.delegate = delegate;
            this.accounts = accounts;
        }

        @Override
        public HistoryPage history(String caller, AccountId accountId, HistoryQuery query) {
            requireOwner(accounts, caller, accountId);
            return delegate.history(caller, accountId, query);
        }
    }
}
