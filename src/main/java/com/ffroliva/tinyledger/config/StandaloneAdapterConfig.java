package com.ffroliva.tinyledger.config;

import com.ffroliva.tinyledger.balance.adapter.out.inmemory.InMemoryBalanceProjection;
import com.ffroliva.tinyledger.balance.adapter.out.inmemory.MapBalanceCache;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceCachePort;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.ffroliva.tinyledger.ledger.adapter.out.tenant.FixedTenantResolver;
import com.ffroliva.tinyledger.ledger.application.port.out.ClockPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.application.port.out.IdGeneratorPort;
import com.ffroliva.tinyledger.ledger.application.port.out.TenantResolverPort;
import com.ffroliva.tinyledger.notification.adapter.out.log.LogNotificationAdapter;
import com.ffroliva.tinyledger.notification.application.NotificationPort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Phase 0's mocked tenant <em>value</em>. Declared here and nowhere else, so {@code full} cannot
     * compose it — provenance is a fact about the profile's bean graph, not a claim the resolver
     * makes about itself.
     */
    @Bean
    TenantResolverPort tenantResolver(@Value("${ledger.tenant.standalone}") String tenant) {
        return new FixedTenantResolver(tenant);
    }

    @Bean
    NotificationPort logNotificationAdapter() {
        return new LogNotificationAdapter();
    }
}
