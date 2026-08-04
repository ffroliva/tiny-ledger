package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.flaviooliva.ledger.ledger.application.port.out.ClockPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.application.port.out.IdGeneratorPort;
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
}
