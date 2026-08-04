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
    void changelogCreatesTheTablesTheFullProfileNeeds() {
        assertThat(exists("events")).isTrue();
        assertThat(exists("balance_projections")).isTrue();
        assertThat(exists("account_history")).isTrue();
        assertThat(exists("audit_entries")).isTrue();
    }

    @Test
    void modulithOwnsThePublicationTableAndWeNoLongerShipAnOutbox() {
        // ADR 0001: the relay is Modulith's, created by its own schema initializer.
        assertThat(exists("event_publication")).isTrue();
        assertThat(exists("event_outbox")).isFalse();
    }

    private Boolean exists(String table) {
        return jdbcTemplate.queryForObject("SELECT to_regclass('public." + table + "') IS NOT NULL", Boolean.class);
    }
}
