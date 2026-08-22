package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.balance.application.port.in.*;
import com.ffroliva.tinyledger.balance.application.port.out.*;
import com.ffroliva.tinyledger.balance.application.projection.BalanceProjector;
import com.ffroliva.tinyledger.balance.application.usecase.*;
import com.ffroliva.tinyledger.ledger.adapter.out.spring.SpringEventPublisher;
import com.ffroliva.tinyledger.ledger.application.port.in.*;
import com.ffroliva.tinyledger.ledger.application.port.out.*;
import com.ffroliva.tinyledger.ledger.application.usecase.*;
import com.ffroliva.tinyledger.notification.application.NotificationRules;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class UseCaseConfig { // profile-independent — the whole trick of spec §1
    @Bean
    EventPublisherPort publisher(ApplicationEventPublisher p) {
        return new SpringEventPublisher(p);
    }

    // Concrete return types: the `full` profile wraps these in a transactional decorator
    // (ADR 0001) and needs to inject the undecorated service unambiguously.
    // §6.5: the account cap is composed here because the count lives in `balance`. One CODE PATH in
    // both run modes — not one rule: `standalone` and the test contexts set a negative limit and the
    // check does nothing there, so the only automated proof of a refusal is OpenAccountServiceTest.
    @Bean
    OpenAccountService openAccount(
            EventStorePort store,
            EventPublisherPort publisher,
            ClockPort clock,
            IdGeneratorPort ids,
            QueryAccountsUseCase accounts,
            @Value("${ledger.accounts.max-per-owner}") int maxAccountsPerOwner) {
        return new OpenAccountService(
                store,
                publisher,
                clock,
                ids,
                owner -> accounts.accountsOwnedBy(owner).size(),
                maxAccountsPerOwner);
    }

    @Bean
    RecordMovementService recordMovement(EventStorePort store, EventPublisherPort publisher, ClockPort clock) {
        return new RecordMovementService(store, publisher, clock);
    }

    /**
     * §6.6 / §14 step 9 part 2. Profile-independent, like everything else in this class — and it has
     * to be, because the chain differs by profile while the decorator does not:
     *
     * <pre>
     *   full:        traced -&gt; transactional -&gt; service
     *   standalone:  traced -&gt; service
     * </pre>
     *
     * <p>The {@link ObjectProvider} is what lets one bean method cover both. In {@code full},
     * {@code FullAdapterConfig} contributes the transactional decorator and it is selected; in
     * {@code standalone} there is none and the plain service is used. Tracing is OUTERMOST so the
     * span covers the commit — see {@link TracedUseCases} for why that ordering is not a preference.
     *
     * <p>This is the {@code @Primary} bean for its interface now, which is why
     * {@code FullAdapterConfig}'s transactional beans no longer are: two {@code @Primary} candidates
     * of one type is a context-startup failure.
     */
    @Bean
    @Primary
    OpenAccountUseCase tracedOpenAccount(
            ObjectProvider<TransactionalUseCases.Opening> transactional, OpenAccountService plain, Tracer tracer) {
        // Not getIfAvailable(Supplier): that overload fixes the supplier's type to the provider's own,
        // so the standalone fallback cannot be widened to the interface. Two lines, and it compiles.
        TransactionalUseCases.Opening decorated = transactional.getIfAvailable();
        return new TracedUseCases.Opening(decorated != null ? decorated : plain, tracer);
    }

    /** See {@link #tracedOpenAccount} — same shape, same reason, plus the {@code ledger.movements} counter. */
    @Bean
    @Primary
    RecordMovementUseCase tracedRecordMovement(
            ObjectProvider<TransactionalUseCases.Movements> transactional,
            RecordMovementService plain,
            Tracer tracer,
            MeterRegistry meters) {
        TransactionalUseCases.Movements decorated = transactional.getIfAvailable();
        return new TracedUseCases.Movements(decorated != null ? decorated : plain, tracer, meters);
    }

    @Bean
    QueryStrongBalanceUseCase strongBalance(EventStorePort store, ClockPort clock) {
        return new StrongBalanceService(store, clock);
    }

    // Reads the event stream directly, like strongBalance above and unlike the balance queries
    // below — a proof computed from a projection would attest to the derivation rather than to the
    // log. No transactional decorator: this only reads, in one call, and takes no ClockPort because
    // nothing here is timestamped; the artefact is a function of the stream alone, which is what
    // makes two callers on two days able to compare roots.
    @Bean
    QueryMerkleProofUseCase merkleProof(EventStorePort store) {
        return new MerkleProofService(store);
    }

    @Bean
    BalanceProjector balanceProjector(BalanceProjectionPort projection, BalanceCachePort cache) {
        return new BalanceProjector(projection, cache);
    }

    // Concrete return types again, for the same reason: the authorization decorator (§6.4) needs to
    // inject the undecorated read service unambiguously.
    @Bean
    BalanceQueryService balanceQueries(BalanceProjectionPort projection, BalanceCachePort cache) {
        return new BalanceQueryService(projection, cache);
    }

    @Bean
    HistoryQueryService historyQueries(BalanceProjectionPort projection) {
        return new HistoryQueryService(projection);
    }

    // §6.4: what the controllers get is the authorised port, in both run modes. @Primary is what makes
    // that true — without it the two candidates of each type are ambiguous and the context fails to
    // start. Omitting the `authorizedHistory` bean instead is the silent failure: one candidate remains,
    // the context starts clean, and any caller can page any other caller's history. SecurityConfigIT
    // holds the only proof of either.
    @Bean
    @Primary
    QueryBalanceUseCase authorizedBalance(BalanceQueryService delegate, QueryAccountsUseCase accounts) {
        return new AuthorizedUseCases.Balances(delegate, accounts);
    }

    @Bean
    @Primary
    QueryHistoryUseCase authorizedHistory(HistoryQueryService delegate, QueryAccountsUseCase accounts) {
        return new AuthorizedUseCases.History(delegate, accounts);
    }

    @Bean
    QueryAccountsUseCase queryAccounts(BalanceProjectionPort projection) {
        return new AccountsQueryService(projection);
    }

    @Bean
    NotificationRules notificationRules(NotificationProperties properties) {
        return new NotificationRules(properties.largeMovementMinorUnits());
    }
}
