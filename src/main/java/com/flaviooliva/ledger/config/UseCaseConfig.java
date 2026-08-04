package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.balance.application.port.in.*;
import com.flaviooliva.ledger.balance.application.port.out.*;
import com.flaviooliva.ledger.balance.application.projection.BalanceProjector;
import com.flaviooliva.ledger.balance.application.usecase.*;
import com.flaviooliva.ledger.ledger.adapter.out.spring.SpringEventPublisher;
import com.flaviooliva.ledger.ledger.application.port.in.*;
import com.flaviooliva.ledger.ledger.application.port.out.*;
import com.flaviooliva.ledger.ledger.application.usecase.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfig { // profile-independent — the whole trick of spec §1
    @Bean
    EventPublisherPort publisher(ApplicationEventPublisher p) {
        return new SpringEventPublisher(p);
    }

    @Bean
    OpenAccountUseCase openAccount(
            EventStorePort store, EventPublisherPort publisher, ClockPort clock, IdGeneratorPort ids) {
        return new OpenAccountService(store, publisher, clock, ids);
    }

    @Bean
    RecordMovementUseCase recordMovement(
            EventStorePort store, EventPublisherPort publisher, ClockPort clock, IdGeneratorPort ids) {
        return new RecordMovementService(store, publisher, clock, ids);
    }

    @Bean
    QueryStrongBalanceUseCase strongBalance(EventStorePort store, ClockPort clock) {
        return new StrongBalanceService(store, clock);
    }

    @Bean
    BalanceProjector balanceProjector(BalanceProjectionPort projection, BalanceCachePort cache) {
        return new BalanceProjector(projection, cache);
    }

    @Bean
    QueryBalanceUseCase queryBalance(BalanceProjectionPort projection, BalanceCachePort cache) {
        return new BalanceQueryService(projection, cache);
    }

    @Bean
    QueryHistoryUseCase queryHistory(BalanceProjectionPort projection) {
        return new HistoryQueryService(projection);
    }

    @Bean
    QueryAccountsUseCase queryAccounts(BalanceProjectionPort projection) {
        return new AccountsQueryService(projection);
    }
}
