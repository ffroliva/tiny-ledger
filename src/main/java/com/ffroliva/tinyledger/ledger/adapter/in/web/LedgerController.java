package com.ffroliva.tinyledger.ledger.adapter.in.web;

import com.ffroliva.tinyledger.api.generated.api.MovementsApi;
import com.ffroliva.tinyledger.api.generated.model.Account;
import com.ffroliva.tinyledger.api.generated.model.Balance;
import com.ffroliva.tinyledger.api.generated.model.MovementRequest;
import com.ffroliva.tinyledger.api.generated.model.OpenAccountRequest;
import com.ffroliva.tinyledger.api.generated.model.Transaction;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.Outcome;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryStrongBalanceUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import com.ffroliva.tinyledger.platform.CallerPrincipal;
import com.ffroliva.tinyledger.shared.AccountId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The write side's inbound adapter (spec §7).
 *
 * <p>It implements {@code MovementsApi} whole, because every movement operation is the ledger module's.
 * {@code AccountsApi} is deliberately <em>not</em> implemented: its operations span two modules
 * ({@code openAccount} here, {@code listAccounts}/{@code getAccount} in {@code balance}), and a controller
 * implementing a generated interface registers a mapping for <em>every</em> operation on it — including the
 * ones it did not override. {@code openAccount} is therefore mapped directly. {@code AuditApi} belongs to
 * the {@code audit} module's own controller, which owns both auditor operations in both run modes.
 *
 * <p>The strong read is this module's one read endpoint (§4.4): the {@code params="consistency=strong"}
 * condition makes it strictly more specific than {@code balance}'s parameterless mapping on the same path,
 * so the two coexist without ambiguity and neither module touches the other's store.
 */
@RestController
public class LedgerController implements MovementsApi {

    private final OpenAccountUseCase openAccount;
    private final RecordMovementUseCase recordMovement;
    private final QueryStrongBalanceUseCase strongBalance;
    private final CallerPrincipal callerPrincipal;

    public LedgerController(
            OpenAccountUseCase openAccount,
            RecordMovementUseCase recordMovement,
            QueryStrongBalanceUseCase strongBalance,
            CallerPrincipal callerPrincipal) {
        this.openAccount = openAccount;
        this.recordMovement = recordMovement;
        this.strongBalance = strongBalance;
        this.callerPrincipal = callerPrincipal;
    }

    @PostMapping(
            path = "/api/v1/accounts",
            consumes = "application/json",
            produces = {"application/json", "application/problem+json"})
    public ResponseEntity<Account> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        String caller = callerPrincipal.current();
        OpenedAccount opened = openAccount.open(LedgerApiMapper.toCommand(request, caller));
        return ResponseEntity.created(
                        URI.create("/api/v1/accounts/" + opened.accountId().value()))
                .body(LedgerApiMapper.toAccount(opened, request, caller));
    }

    @Override
    public ResponseEntity<Transaction> putDeposit(UUID accountUid, UUID depositUid, MovementRequest request) {
        return respond(
                recordMovement.deposit(new Deposit(
                        callerPrincipal.current(),
                        callerPrincipal.isAdmin(),
                        new AccountId(accountUid),
                        depositUid,
                        LedgerApiMapper.toMoney(request.getAmount()),
                        request.getReference())),
                request);
    }

    @Override
    public ResponseEntity<Transaction> putWithdrawal(UUID accountUid, UUID withdrawalUid, MovementRequest request) {
        return respond(
                recordMovement.withdraw(new Withdraw(
                        callerPrincipal.current(),
                        callerPrincipal.isAdmin(),
                        new AccountId(accountUid),
                        withdrawalUid,
                        LedgerApiMapper.toMoney(request.getAmount()),
                        request.getReference())),
                request);
    }

    @GetMapping(
            path = "/api/v1/accounts/{accountUid}/balance",
            params = "consistency=strong",
            produces = {"application/json", "application/problem+json"})
    public ResponseEntity<Balance> getStrongBalance(@PathVariable UUID accountUid) {
        return ResponseEntity.ok(LedgerApiMapper.toBalance(
                strongBalance.strongBalance(callerPrincipal.current(), new AccountId(accountUid))));
    }

    /**
     * Spec §6.3: {@code CREATED} is a 201, a replay is the original answer, a refusal is a 422 — and replays
     * deterministically as the same 422. No {@code default} arm: a new {@link Outcome} constant is meant to
     * be a compile error here, not a silent 200 on a result with no {@code balanceAfter}.
     */
    private static ResponseEntity<Transaction> respond(MovementResult result, MovementRequest request) {
        return switch (result.outcome()) {
            case CREATED ->
                ResponseEntity.status(HttpStatus.CREATED).body(LedgerApiMapper.toTransaction(result, request));
            case REPLAYED -> ResponseEntity.ok(LedgerApiMapper.toTransaction(result, request));
            case REJECTED, REJECTED_REPLAYED -> throw LedgerApiMapper.rejection(result);
        };
    }
}
