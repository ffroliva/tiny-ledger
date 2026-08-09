package com.ffroliva.tinyledger.ledger.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.ledger.application.error.AccountLimitReachedException;
import com.ffroliva.tinyledger.ledger.application.error.AccountNotFoundException;
import com.ffroliva.tinyledger.ledger.application.error.ConcurrencyConflictException;
import com.ffroliva.tinyledger.ledger.application.error.IdempotencyConflictException;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.Outcome;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryStrongBalanceUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.StrongBalance;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.platform.CallerPrincipal;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.CurrencyMismatchException;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Spec §6.3 (the response table) and §6.5 (the error catalogue) as HTTP facts. */
@WebMvcTest(LedgerController.class)
class LedgerControllerTest {

    private static final UUID ACCOUNT = UUID.fromString("f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50");
    private static final UUID MOVEMENT = UUID.fromString("8b0c1d2e-3f40-4152-8637-4a5b6c7d8e9f");
    private static final Instant NOW = Instant.parse("2026-08-03T17:12:09Z");
    private static final String BODY = """
            {"amount":{"currency":"GBP","minorUnits":10000},"reference":"rent"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OpenAccountUseCase openAccount;

    @MockitoBean
    private RecordMovementUseCase recordMovement;

    @MockitoBean
    private QueryStrongBalanceUseCase strongBalance;

    // A platform @Component is not part of a @WebMvcTest slice, so constructor-injecting it into the
    // controller fails the context at startup with NoSuchBeanDefinitionException unless it is mocked.
    @MockitoBean
    private CallerPrincipal callerPrincipal;

    /**
     * §6.4/P1-3: "captain-nemo" is a value nothing in the system can produce — {@code standalone} always
     * resolves "local", which is what this stub used to return. With that literal, a controller that stopped
     * calling {@code callerPrincipal.current()} and passed "local" directly kept the whole suite green, on the
     * money path, in the one file Task 5 never reached. The sentinel is what makes every call site fail: the
     * exact-argument stub in {@code strongReadIsServedByTheLedgerModule}, the echoed {@code $.owner} in
     * {@code openAccountIsCreatedWithLocation}, and the two captors in {@code theResolvedCallerIsOnEveryMovement}.
     */
    @BeforeEach
    void caller() {
        given(callerPrincipal.current()).willReturn("captain-nemo");
    }

    @Test // §6.3: first write
    void firstDepositIsCreated() throws Exception {
        given(recordMovement.deposit(any())).willReturn(recorded(Outcome.CREATED));

        deposit()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionUid").value(MOVEMENT.toString()))
                .andExpect(jsonPath("$.accountUid").value(ACCOUNT.toString()))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.direction").value("IN"))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.amount.currency").value("GBP"))
                .andExpect(jsonPath("$.amount.minorUnits").value(10000))
                .andExpect(jsonPath("$.balanceAfter.minorUnits").value(25000))
                .andExpect(jsonPath("$.reference").value("rent"));
    }

    /**
     * §6.4/P1-3: the caller on a movement must be the resolved principal, not a literal — Task 6 authorises on
     * this value, and a wrong one is a caller writing to somebody else's account. Both money-path call sites
     * are captured, not just the deposit: {@code putWithdrawal} is the adjacent line with the identical
     * defect, and every other withdrawal test in this class stubs with {@code any()}.
     */
    @Test
    void theResolvedCallerIsOnEveryMovement() throws Exception {
        given(recordMovement.deposit(any())).willReturn(recorded(Outcome.CREATED));
        given(recordMovement.withdraw(any())).willReturn(recorded(Outcome.CREATED));
        ArgumentCaptor<Deposit> deposited = ArgumentCaptor.forClass(Deposit.class);
        ArgumentCaptor<Withdraw> withdrawn = ArgumentCaptor.forClass(Withdraw.class);

        deposit().andExpect(status().isCreated());
        mvc.perform(put("/api/v1/accounts/{a}/withdrawals/{w}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated());

        verify(recordMovement).deposit(deposited.capture());
        verify(recordMovement).withdraw(withdrawn.capture());
        assertThat(deposited.getValue().caller()).isEqualTo("captain-nemo");
        assertThat(withdrawn.getValue().caller()).isEqualTo("captain-nemo");
    }

    @Test // §6.3: same UID, same payload — replayed, never re-applied
    void replayedDepositIsOk() throws Exception {
        given(recordMovement.deposit(any())).willReturn(recorded(Outcome.REPLAYED));

        deposit()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter.minorUnits").value(25000));
    }

    @Test // §6.3: same UID, different payload
    void reusedUidWithDifferentPayloadIsConflict() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new IdempotencyConflictException(MOVEMENT));

        deposit()
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/errors/idempotency-conflict"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test // §6.5: wrong owner
    void wrongOwnerIsForbidden() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new OwnershipException("mallory", new AccountId(ACCOUNT)));

        deposit().andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value("/errors/forbidden"));
    }

    @Test // §6.5: unknown account
    void unknownAccountIsNotFound() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new AccountNotFoundException(new AccountId(ACCOUNT)));

        deposit().andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("/errors/account-not-found"));
    }

    @Test // §6.5: concurrent modification
    void concurrencyConflictIsVersionConflict() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new ConcurrencyConflictException(new AccountId(ACCOUNT), 2, 3));

        deposit().andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("/errors/version-conflict"));
    }

    @Test // §6.5: currency mismatch with the account
    void currencyMismatchIsUnprocessable() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new CurrencyMismatchException("GBP", "EUR"));

        deposit()
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("/errors/currency-mismatch"));
    }

    @Test // §6.5: malformed shape is rejected at the boundary, before the use case runs
    void negativeMinorUnitsIsBadRequestBeforeAnyServiceCall() throws Exception {
        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":{"currency":"GBP","minorUnits":-5}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(recordMovement);
    }

    @Test // §6.5: an unreadable body never reaches the use case either
    void unreadableBodyIsBadRequest() throws Exception {
        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(recordMovement);
    }

    @Test // §6.3/§6.5: a refused withdrawal is a 422 carrying the reason as its type
    void refusedWithdrawalIsUnprocessable() throws Exception {
        given(recordMovement.withdraw(any())).willReturn(rejected(Outcome.REJECTED, "insufficient-funds"));

        mvc.perform(put("/api/v1/accounts/{a}/withdrawals/{w}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("/errors/insufficient-funds"));
    }

    @Test // §6.3: rejections replay deterministically — the original 422, not a 200
    void replayedRejectionIsTheOriginalUnprocessable() throws Exception {
        given(recordMovement.withdraw(any())).willReturn(rejected(Outcome.REJECTED_REPLAYED, "insufficient-funds"));

        mvc.perform(put("/api/v1/accounts/{a}/withdrawals/{w}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("/errors/insufficient-funds"));
    }

    @Test // §7: opening is a POST with a server-generated uid and a Location header
    void openAccountIsCreatedWithLocation() throws Exception {
        given(openAccount.open(any())).willReturn(new OpenedAccount(new AccountId(ACCOUNT), 1, NOW));

        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ACC-001","currency":"GBP"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + ACCOUNT))
                .andExpect(jsonPath("$.accountUid").value(ACCOUNT.toString()))
                .andExpect(jsonPath("$.name").value("ACC-001"))
                .andExpect(jsonPath("$.currency").value("GBP"))
                // §6.4: echoed from the resolved caller, so a hardcoded literal in openAccount fails here
                .andExpect(jsonPath("$.owner").value("captain-nemo"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test // §6.5: the contract says openAccount can answer 409; this is the only thing that makes it true
    void openingBeyondTheAccountLimitIsConflict() throws Exception {
        given(openAccount.open(any())).willThrow(new AccountLimitReachedException(10));

        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ACC-011","currency":"GBP"}"""))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/errors/account-limit-reached"));
    }

    @Test // §6.5: a malformed open request is a 400 before the use case runs
    void badCurrencyCodeIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ACC-001","currency":"pounds"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(openAccount);
    }

    @Test // §6.5: pattern-valid but not an ISO 4217 code — the boundary's last line, Currency.getInstance
    void unknownCurrencyCodeIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ACC-001","currency":"ZZZ"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(openAccount);
    }

    @Test // CR12: an IllegalArgumentException that is not about an amount must not claim to be
    void anUnrelatedIllegalArgumentIsNotReportedAsAnInvalidAmount() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new IllegalArgumentException("Invalid UUID string: nope"));

        // The brief expected `jsonPath("$.type").value(not(...))`, but this Spring version suppresses the
        // default `about:blank` type entirely, so a 500 body has no `$.type` node and jsonPath cannot match
        // an absent path. Asserting on the body — the repo's own idiom in unexpectedFailureLeaksNothing —
        // states the same thing and holds whether or not `type` is serialised.
        deposit()
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("/errors/invalid-amount"))));
    }

    @Test // the movement path's own unknown-code guard — Money.of, the sibling of toCommand's
    void anUnknownCurrencyCodeOnAMovementIsBadRequest() throws Exception {
        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":{"currency":"ZZZ","minorUnits":100}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(recordMovement);
    }

    /**
     * §6.5/§6.6: the correlating id. This used to hand-stuff the MDC to stand in for a tracer that did not
     * exist yet, and was named {@code ...WhenTracingIsPresent} for that conditional; Task 7's
     * {@code FapiInteractionIdFilter} is that tracer, it sets the MDC on every request, and the condition is
     * now unconditionally true — measured: the hand-stuffed value lost to the filter's minted UUID and this
     * test went red. So the id arrives the way a caller actually supplies it, and the header must carry the
     * same value as the body: two independent {@code exists()} checks would be satisfied by two unrelated ids.
     */
    @Test
    void problemBodiesCarryTheInteractionId() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new IdempotencyConflictException(MOVEMENT));

        String interactionId = "9f8e7d6c-5b4a-4c3d-8e2f-1a2b3c4d5e6f";
        mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", ACCOUNT, MOVEMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY)
                        .header("x-fapi-interaction-id", interactionId))
                .andExpect(status().isConflict())
                .andExpect(header().string("x-fapi-interaction-id", interactionId))
                .andExpect(jsonPath("$.traceId").value(interactionId));
    }

    @Test // §6.5: no stack traces, no internal identifiers, no messages cross the boundary
    void unexpectedFailureLeaksNothing() throws Exception {
        given(recordMovement.deposit(any())).willThrow(new RuntimeException("boom with secrets"));

        deposit()
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("boom"))))
                .andExpect(content().string(not(containsString("RuntimeException"))))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.status").value(500));
    }

    @Test // §4.4: the params-qualified mapping is the write side's own strong read
    void strongReadIsServedByTheLedgerModule() throws Exception {
        given(strongBalance.strongBalance("captain-nemo", new AccountId(ACCOUNT)))
                .willReturn(new StrongBalance(new AccountId(ACCOUNT), Money.of("GBP", 8000), NOW, 3));

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT).param("consistency", "strong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountUid").value(ACCOUNT.toString()))
                .andExpect(jsonPath("$.amount.minorUnits").value(8000))
                .andExpect(jsonPath("$.streamVersion").value(3))
                .andExpect(jsonPath("$.asOf").exists());
    }

    private org.springframework.test.web.servlet.ResultActions deposit() throws Exception {
        return mvc.perform(put("/api/v1/accounts/{a}/deposits/{d}", ACCOUNT, MOVEMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    private static MovementResult recorded(Outcome outcome) {
        return new MovementResult(
                new AccountId(ACCOUNT),
                MOVEMENT,
                MovementType.DEPOSIT,
                2,
                Money.of("GBP", 10000),
                Money.of("GBP", 25000),
                NOW,
                outcome,
                null);
    }

    private static MovementResult rejected(Outcome outcome, String reason) {
        return new MovementResult(
                new AccountId(ACCOUNT),
                MOVEMENT,
                MovementType.WITHDRAWAL,
                2,
                Money.of("GBP", 10000),
                null,
                NOW,
                outcome,
                reason);
    }
}
