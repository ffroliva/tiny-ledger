package com.ffroliva.tinyledger.balance.adapter.in.web;

import com.ffroliva.tinyledger.api.generated.api.BalanceApi;
import com.ffroliva.tinyledger.api.generated.model.Account;
import com.ffroliva.tinyledger.api.generated.model.AccountList;
import com.ffroliva.tinyledger.api.generated.model.Balance;
import com.ffroliva.tinyledger.api.generated.model.Money;
import com.ffroliva.tinyledger.api.generated.model.PageLinks;
import com.ffroliva.tinyledger.api.generated.model.Transaction;
import com.ffroliva.tinyledger.api.generated.model.TransactionList;
import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryPage;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryQuery;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.TransactionView;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.platform.CallerPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The read side's inbound adapter (spec §4.4, §7).
 *
 * <p>It implements {@code BalanceApi} — one operation, wholly this module's — so the eventual balance read
 * keeps the contract's unqualified mapping and the {@code ledger} module's strong read layers a
 * {@code params="consistency=strong"} mapping over the same path. {@code AccountsApi} spans two modules and
 * {@code TransactionsApi}'s generated {@code listTransactions} is missing the {@code accountUid} path
 * variable altogether, so those three operations are mapped directly, with the contract's {@code limit}
 * bounds replicated as bean-validation constraints (§6.5 makes an out-of-range parameter a 400).
 */
@RestController
public class BalanceController implements BalanceApi {

    private static final String JSON = "application/json";
    private static final String PROBLEM_JSON = "application/problem+json";

    private final QueryBalanceUseCase queryBalance;
    private final QueryHistoryUseCase queryHistory;
    private final QueryAccountsUseCase queryAccounts;
    private final CallerPrincipal callerPrincipal;

    public BalanceController(
            QueryBalanceUseCase queryBalance,
            QueryHistoryUseCase queryHistory,
            QueryAccountsUseCase queryAccounts,
            CallerPrincipal callerPrincipal) {
        this.queryBalance = queryBalance;
        this.queryHistory = queryHistory;
        this.queryAccounts = queryAccounts;
        this.callerPrincipal = callerPrincipal;
    }

    @Override // the parameterless mapping; ?consistency=strong is routed to the ledger module instead
    public ResponseEntity<Balance> getBalance(UUID accountUid, String consistency) {
        BalanceView view = queryBalance
                .balance(callerPrincipal.current(), new com.ffroliva.tinyledger.shared.AccountId(accountUid))
                .orElseThrow(BalanceController::accountNotFound);
        return ResponseEntity.ok(
                new Balance(view.accountId().value(), money(view.amount()), at(view.asOf()), view.streamVersion()));
    }

    @GetMapping(
            path = "/api/v1/accounts",
            produces = {JSON, PROBLEM_JSON})
    public ResponseEntity<AccountList> listAccounts() {
        return ResponseEntity.ok(new AccountList(queryAccounts.accountsOwnedBy(callerPrincipal.current()).stream()
                .map(BalanceController::account)
                .toList()));
    }

    @GetMapping(
            path = "/api/v1/accounts/{accountUid}",
            produces = {JSON, PROBLEM_JSON})
    public ResponseEntity<Account> getAccount(@PathVariable UUID accountUid) {
        return ResponseEntity.ok(queryAccounts.accountsOwnedBy(callerPrincipal.current()).stream()
                .filter(view -> view.accountId().value().equals(accountUid))
                .findFirst()
                .map(BalanceController::account)
                .orElseThrow(BalanceController::accountNotFound));
    }

    @GetMapping(
            path = "/api/v1/accounts/{accountUid}/transactions",
            produces = {JSON, PROBLEM_JSON})
    public ResponseEntity<TransactionList> listTransactions(
            @PathVariable UUID accountUid,
            @RequestParam(value = "cursor", required = false) String cursor,
            @Min(1) @Max(200) @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit,
            @RequestParam(value = "minTransactionTimestamp", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime minTransactionTimestamp,
            @RequestParam(value = "maxTransactionTimestamp", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime maxTransactionTimestamp) {
        HistoryPage page = queryHistory.history(
                callerPrincipal.current(),
                new com.ffroliva.tinyledger.shared.AccountId(accountUid),
                new HistoryQuery(cursor, limit, instant(minTransactionTimestamp), instant(maxTransactionTimestamp)));
        TransactionList body = new TransactionList(
                page.transactions().stream().map(BalanceController::transaction).toList());
        if (page.nextCursor() != null) {
            // §7: the next page is the same query one cursor further on — carrying only the cursor would
            // reset the caller's limit to the default and drop the filters, paging a different result
            // set. Timestamps echo as UTC instants, a form that has no '+' to decode back as a space.
            body.links(new PageLinks()
                    .next(UriComponentsBuilder.fromPath("/api/v1/accounts/" + accountUid + "/transactions")
                            .queryParamIfPresent(
                                    "minTransactionTimestamp", Optional.ofNullable(instant(minTransactionTimestamp)))
                            .queryParamIfPresent(
                                    "maxTransactionTimestamp", Optional.ofNullable(instant(maxTransactionTimestamp)))
                            .queryParam("limit", limit)
                            .queryParam("cursor", page.nextCursor())
                            .build()
                            .toUriString()));
        }
        return ResponseEntity.ok(body);
    }

    private static Account account(AccountView view) {
        return new Account(
                view.accountId().value(),
                view.name(),
                view.currency().getCurrencyCode(),
                at(view.createdAt()),
                view.owner());
    }

    private static Transaction transaction(TransactionView view) {
        return new Transaction(
                        view.transactionUid(),
                        view.accountId().value(),
                        Transaction.TypeEnum.fromValue(view.type().name()),
                        Transaction.DirectionEnum.fromValue(direction(view.type())),
                        money(view.amount()),
                        money(view.balanceAfter()),
                        Transaction.StatusEnum.fromValue(view.status()),
                        at(view.transactionTime()),
                        at(view.settlementTime()))
                .reference(view.reference());
    }

    private static String direction(MovementType type) {
        return type == MovementType.DEPOSIT ? TransactionView.IN : TransactionView.OUT;
    }

    private static Money money(com.ffroliva.tinyledger.shared.Money money) {
        return new Money(money.currency().getCurrencyCode(), money.minorUnits());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * §6.5's 404. The {@code ledger} module's {@code AccountNotFoundException} is not reachable from here —
     * {@code balance} may only see {@code shared} and {@code ledger::events} — so the problem detail is built
     * directly and handed to the advice as an {@link ErrorResponseException}.
     */
    private static ErrorResponseException accountNotFound() {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        body.setType(URI.create("/errors/account-not-found"));
        body.setTitle("Account not found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, body, null);
    }
}
