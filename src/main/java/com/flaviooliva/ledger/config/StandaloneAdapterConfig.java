package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.balance.adapter.out.inmemory.InMemoryBalanceProjection;
import com.flaviooliva.ledger.balance.adapter.out.inmemory.MapBalanceCache;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.flaviooliva.ledger.ledger.application.port.out.ClockPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.application.port.out.IdGeneratorPort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("standalone")
public class StandaloneAdapterConfig {
    @Bean
    EventStorePort eventStore() {
        return new InMemoryEventStore();
    }

    @Bean
    ClockPort clock() {
        return Instant::now;
    }

    @Bean
    IdGeneratorPort ids() {
        return UUID::randomUUID;
    }

    @Bean
    BalanceProjectionPort balanceProjection() {
        return new InMemoryBalanceProjection();
    }

    @Bean // §6.2: the 60 s TTL is a composition-root decision, not a cache-implementation one
    BalanceCachePort balanceCache(ClockPort clock) {
        return new MapBalanceCache(Duration.ofSeconds(60), clock::now);
    }
}
