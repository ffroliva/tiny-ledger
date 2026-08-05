package com.ffroliva.tinyledger.ledger.adapter.out.postgres;

import com.ffroliva.tinyledger.contract.EventStoreContract;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

public class PostgresEventStoreIT extends AbstractIntegrationTest implements EventStoreContract {

    @Autowired
    private EventStorePort store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE events RESTART IDENTITY CASCADE");
    }

    @Override
    public EventStorePort store() {
        return store;
    }
}
