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
    @Bean
    OpenAccountService openAccount(
            EventStorePort store, EventPublisherPort publisher, ClockPort clock, IdGeneratorPort ids) {
        return new OpenAccountService(store, publisher, clock, ids);
    }

    @Bean
    RecordMovementService recordMovement(EventStorePort store, EventPublisherPort publisher, ClockPort clock) {
        return new RecordMovementService(store, publisher, clock);
    }

    @Bean
    QueryStrongBalanceUseCase strongBalance(EventStorePort store, ClockPort clock) {
        return new StrongBalanceService(store, clock);
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
