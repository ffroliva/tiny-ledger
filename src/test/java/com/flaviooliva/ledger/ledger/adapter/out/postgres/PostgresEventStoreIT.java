package com.flaviooliva.ledger.ledger.adapter.out.postgres;

import com.flaviooliva.ledger.LedgerApplication;
import com.flaviooliva.ledger.contract.EventStoreContract;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = LedgerApplication.class)
@ActiveProfiles("full")
public class PostgresEventStoreIT extends EventStoreContract {

    @Autowired
    private EventStorePort store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events RESTART IDENTITY CASCADE");
    }

    @Override
    protected EventStorePort store() {
        return store;
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", AbstractIntegrationTest.POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", AbstractIntegrationTest.POSTGRES::getDriverClassName);

        registry.add("spring.data.redis.host", AbstractIntegrationTest.REDIS::getHost);
        registry.add("spring.data.redis.port", AbstractIntegrationTest.REDIS::getFirstMappedPort);

        registry.add("spring.kafka.bootstrap-servers", AbstractIntegrationTest.KAFKA::getBootstrapServers);
    }
}
