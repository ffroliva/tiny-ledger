package com.flaviooliva.ledger.ledger.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FlywayMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationCreatesEventsAndEventOutboxTables() {
        Boolean eventsExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'events')",
                Boolean.class);
        Boolean eventOutboxExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'event_outbox')",
                Boolean.class);

        assertThat(eventsExists).isTrue();
        assertThat(eventOutboxExists).isTrue();
    }
}
