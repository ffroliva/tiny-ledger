package com.flaviooliva.ledger.ledger.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LiquibaseMigrationTest extends AbstractIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void verifiesDatabaseTablesExistAfterLiquibaseMigration() {
        Boolean eventsExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.events') IS NOT NULL", Boolean.class);
        Boolean outboxExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.event_outbox') IS NOT NULL", Boolean.class);

        assertThat(eventsExists).isTrue();
        assertThat(outboxExists).isTrue();
    }
}
