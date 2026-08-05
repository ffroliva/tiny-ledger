package com.ffroliva.tinyledger.balance.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.balance.application.port.in.AccountView;
import com.ffroliva.tinyledger.balance.application.port.in.BalanceView;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryPage;
import com.ffroliva.tinyledger.balance.application.port.in.QueryAccountsUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryBalanceUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.QueryHistoryUseCase;
import com.ffroliva.tinyledger.balance.application.port.in.TransactionView;
import com.ffroliva.tinyledger.ledger.adapter.in.web.LedgerController;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccountUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryStrongBalanceUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.RecordMovementUseCase;
import com.ffroliva.tinyledger.ledger.application.port.in.StrongBalance;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.platform.CallerPrincipal;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec §4.4: both controllers are loaded together, so the one path with two disambiguated mappings —
 * {@code GET /balance} on the projection, {@code GET /balance?consistency=strong} on the aggregate — is
 * proven to coexist rather than to collide.
 */
@WebMvcTest(controllers = {BalanceController.class, LedgerController.class})
class BalanceControllerTest {

    private static final UUID ACCOUNT = UUID.fromString("f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50");
    private static final UUID MOVEMENT = UUID.fromString("8b0c1d2e-3f40-4152-8637-4a5b6c7d8e9f");
    private static final Instant NOW = Instant.parse("2026-08-03T17:12:09Z");
    private static final String CURSOR = "eyJ0IjoxNzU0MzE1MTI5fQ";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QueryBalanceUseCase queryBalance;

    @MockitoBean
    private QueryHistoryUseCase queryHistory;

    @MockitoBean
    private QueryAccountsUseCase queryAccounts;

    @MockitoBean
    private QueryStrongBalanceUseCase strongBalance;

    @MockitoBean
    private OpenAccountUseCase openAccount;

    @MockitoBean
    private RecordMovementUseCase recordMovement;

    // A platform @Component is not part of a @WebMvcTest slice, so constructor-injecting it into the
    // controllers fails the context at startup with NoSuchBeanDefinitionException unless it is mocked.
    @MockitoBean
    private CallerPrincipal callerPrincipal;

    /**
     * "captain-nemo" is a value nothing in the system can produce: {@code standalone} always resolves
     * "local", so every stub in this class that expected that literal was satisfied by a controller which
     * hardcoded the principal instead of resolving it. The sentinel is what makes {@code listAccounts},
     * {@code getAccount} and the strong read fail if they stop passing {@code callerPrincipal.current()} —
     * and {@code accountsOwnedBy(caller)} is itself an authorization filter (§6.4/N12), not just a query.
     */
    @BeforeEach
    void caller() {
        given(callerPrincipal.current()).willReturn("captain-nemo");
    }

    @Test // §4.4: the parameterless mapping is balance's — the projection read
    void plainBalanceReadIsServedByTheProjection() throws Exception {
        given(queryBalance.balance(anyString(), eq(new AccountId(ACCOUNT))))
                .willReturn(Optional.of(new BalanceView(new AccountId(ACCOUNT), Money.of("GBP", 8000), NOW, 3)));

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountUid").value(ACCOUNT.toString()))
                .andExpect(jsonPath("$.amount.minorUnits").value(8000))
                .andExpect(jsonPath("$.asOf").value("2026-08-03T17:12:09Z"))
                .andExpect(jsonPath("$.streamVersion").value(3));

        verifyNoInteractions(strongBalance);
    }

    @Test // §4.4: the params-qualified mapping wins and lands in the ledger module
    void strongBalanceReadIsServedByTheAggregate() throws Exception {
        given(strongBalance.strongBalance("captain-nemo", new AccountId(ACCOUNT)))
                .willReturn(new StrongBalance(new AccountId(ACCOUNT), Money.of("GBP", 9500), NOW, 4));

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT).param("consistency", "strong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount.minorUnits").value(9500))
                .andExpect(jsonPath("$.streamVersion").value(4));

        verifyNoInteractions(queryBalance);
    }

    @Test // §6.5
    void unknownAccountBalanceIsNotFound() throws Exception {
        given(queryBalance.balance(any(), any())).willReturn(Optional.empty());

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/account-not-found"));
    }

    @Test // §6.4: the caller must be the resolved principal, not a literal — Task 6 authorises on this value
    void theResolvedCallerIsPassedToTheQuery() throws Exception {
        given(queryBalance.balance(any(), any()))
                .willReturn(Optional.of(new BalanceView(new AccountId(ACCOUNT), Money.of("GBP", 5000), NOW, 3)));
        ArgumentCaptor<String> caller = ArgumentCaptor.forClass(String.class);

        mvc.perform(get("/api/v1/accounts/{a}/balance", ACCOUNT)).andExpect(status().isOk());

        verify(queryBalance).balance(caller.capture(), any());
        assertThat(caller.getValue()).isEqualTo("captain-nemo");
    }

    @Test // §6.4: the same for history — Task 6 decorates both ports, so both call sites need proving
    void theResolvedCallerIsPassedToTheHistoryQuery() throws Exception {
        // The three feed tests below stub with any(), so a hardcoded literal here would go unnoticed too.
        given(queryHistory.history(any(), any(), any())).willReturn(new HistoryPage(List.of(transaction()), null));
        ArgumentCaptor<String> caller = ArgumentCaptor.forClass(String.class);

        mvc.perform(get("/api/v1/accounts/{a}/transactions", ACCOUNT)).andExpect(status().isOk());

        verify(queryHistory).history(caller.capture(), any(), any());
        assertThat(caller.getValue()).isEqualTo("captain-nemo");
    }

    @Test // §7: links.next is a URL — same path, the cursor as a query parameter
    void transactionFeedCarriesTheNextPageUrl() throws Exception {
        given(queryHistory.history(any(), any(), any())).willReturn(new HistoryPage(List.of(transaction()), CURSOR));

        mvc.perform(get("/api/v1/accounts/{a}/transactions", ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions[0].transactionUid").value(MOVEMENT.toString()))
                .andExpect(jsonPath("$.transactions[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.transactions[0].direction").value("OUT"))
                .andExpect(jsonPath("$.transactions[0].amount.minorUnits").value(2000))
                .andExpect(jsonPath("$.transactions[0].balanceAfter.minorUnits").value(8000))
                .andExpect(jsonPath("$.links.next")
                        .value("/api/v1/accounts/" + ACCOUNT + "/transactions?limit=50&cursor=" + CURSOR));
    }

    @Test // §7: the next page must repeat the caller's window, not silently widen it
    void transactionFeedNextPageUrlKeepsTheLimitAndTheFilters() throws Exception {
        given(queryHistory.history(any(), any(), any())).willReturn(new HistoryPage(List.of(transaction()), CURSOR));

        mvc.perform(get("/api/v1/accounts/{a}/transactions", ACCOUNT)
                        .param("limit", "25")
                        .param("minTransactionTimestamp", "2026-08-04T00:00:00+01:00")
                        .param("maxTransactionTimestamp", "2026-08-05T00:00:00+01:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.next")
                        .value("/api/v1/accounts/" + ACCOUNT + "/transactions"
                                + "?minTransactionTimestamp=2026-08-03T23:00:00Z"
                                + "&maxTransactionTimestamp=2026-08-04T23:00:00Z&limit=25&cursor=" + CURSOR))
                // A '+' offset would decode back as a space and 400 the very next page.
                .andExpect(jsonPath("$.links.next").value(not(containsString("+"))));
    }

    @Test // §7: absent once the feed is exhausted
    void exhaustedFeedHasNoNextLink() throws Exception {
        given(queryHistory.history(any(), any(), any())).willReturn(new HistoryPage(List.of(transaction()), null));

        mvc.perform(get("/api/v1/accounts/{a}/transactions", ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links.next").doesNotExist());
    }

    @Test // §6.5: limit is validated at the edge, against the contract's declared range
    void limitBelowTheContractMinimumIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/accounts/{a}/transactions", ACCOUNT).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(queryHistory);
    }

    @Test // §6.5
    void limitAboveTheContractMaximumIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/accounts/{a}/transactions", ACCOUNT).param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

        verifyNoInteractions(queryHistory);
    }

    @Test // §7/N12: the caller's own accounts
    void listsTheCallersOwnAccounts() throws Exception {
        given(queryAccounts.accountsOwnedBy("captain-nemo")).willReturn(List.of(account()));

        mvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts[0].accountUid").value(ACCOUNT.toString()))
                .andExpect(jsonPath("$.accounts[0].name").value("ACC-001"))
                .andExpect(jsonPath("$.accounts[0].currency").value("GBP"))
                // Deliberately NOT the caller: `owner` must be mapped from the view, not echoed from the
                // principal, and only a fixture owner that differs from the caller can tell the two apart.
                .andExpect(jsonPath("$.accounts[0].owner").value("local"));
    }

    @Test // §7
    void readsAccountMetadata() throws Exception {
        given(queryAccounts.accountsOwnedBy("captain-nemo")).willReturn(List.of(account()));

        mvc.perform(get("/api/v1/accounts/{a}", ACCOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountUid").value(ACCOUNT.toString()))
                .andExpect(jsonPath("$.name").value("ACC-001"));
    }

    @Test // §6.5
    void unknownAccountMetadataIsNotFound() throws Exception {
        given(queryAccounts.accountsOwnedBy("captain-nemo")).willReturn(List.of());

        mvc.perform(get("/api/v1/accounts/{a}", ACCOUNT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/errors/account-not-found"));
    }

    private static TransactionView transaction() {
        return new TransactionView(
                MOVEMENT,
                new AccountId(ACCOUNT),
                MovementType.WITHDRAWAL,
                TransactionView.OUT,
                Money.of("GBP", 2000),
                Money.of("GBP", 8000),
                TransactionView.SETTLED,
                NOW,
                NOW,
                "rent");
    }

    private static AccountView account() {
        return new AccountView(new AccountId(ACCOUNT), "ACC-001", "local", Currency.getInstance("GBP"), NOW);
    }
}
