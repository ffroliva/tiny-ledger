package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.balance.adapter.out.inmemory.InMemoryBalanceProjection;
import com.flaviooliva.ledger.balance.adapter.out.inmemory.MapBalanceCache;
import com.flaviooliva.ledger.balance.application.port.out.BalanceCachePort;
import com.flaviooliva.ledger.balance.application.port.out.BalanceProjectionPort;
import com.flaviooliva.ledger.ledger.adapter.out.postgres.PostgresEventStore;
import com.flaviooliva.ledger.ledger.application.port.out.ClockPort;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.application.port.out.IdGeneratorPort;
import com.flaviooliva.ledger.notification.adapter.out.log.LogNotificationAdapter;
import com.flaviooliva.ledger.notification.application.NotificationPort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("full")
public class FullAdapterConfig {

    @Bean
    public SpringLiquibase liquibase(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        return liquibase;
    }

    @Bean
    public EventStorePort eventStore(JdbcTemplate jdbcTemplate) {
        return new PostgresEventStore(jdbcTemplate, new ObjectMapper());
    }

    @Bean
    public ClockPort clock() {
        return Instant::now;
    }

    @Bean
    public IdGeneratorPort ids() {
        return UUID::randomUUID;
    }

    @Bean
    public BalanceProjectionPort balanceProjection() {
        return new InMemoryBalanceProjection();
    }

    @Bean
    public BalanceCachePort balanceCache(ClockPort clock) {
        return new MapBalanceCache(Duration.ofSeconds(60), clock::now);
    }

    @Bean
    public NotificationPort logNotificationAdapter() {
        return new LogNotificationAdapter();
    }
}
