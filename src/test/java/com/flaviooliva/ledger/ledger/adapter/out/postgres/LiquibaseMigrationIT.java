package com.flaviooliva.ledger.ledger.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.flaviooliva.ledger.testsupport.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LiquibaseMigrationIT extends AbstractIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void changelogCreatesTheTablesTheFullProfileNeeds() {
        assertThat(exists("events")).isTrue();
        assertThat(exists("balance_projections")).isTrue();
        assertThat(exists("account_history")).isTrue();
        assertThat(exists("audit_entries")).isTrue();
        // §12: Modulith's publication registry is changeset 004, not its own schema initializer.
        assertThat(exists("event_publication")).isTrue();
    }

    @Test
    void modulithOwnsTheRelayAndWeNoLongerShipAnOutbox() {
        // ADR 0001: the relay is Modulith's; the columns its v2 repository reads have to be there.
        assertThat(columns("event_publication"))
                .contains("id", "listener_id", "event_type", "serialized_event", "publication_date", "completion_date");
        assertThat(exists("event_outbox")).isFalse();
    }

    private Boolean exists(String table) {
        return jdbcTemplate.queryForObject("SELECT to_regclass('public." + table + "') IS NOT NULL", Boolean.class);
    }

    private List<String> columns(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?", String.class, table);
    }
}
