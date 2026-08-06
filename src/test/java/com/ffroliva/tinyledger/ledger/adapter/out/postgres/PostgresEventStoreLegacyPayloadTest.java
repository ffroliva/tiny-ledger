package com.ffroliva.tinyledger.ledger.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.ledger.domain.MoneyDeposited;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * §15.9: a payload written before this feature has no `actor` key at all. `PostgresEventStore`
 * deserialises with the same plain {@link ObjectMapper} it uses in production — no custom module, no
 * {@code @JsonCreator} anywhere on these records — so this proves a legacy row still reads, with
 * {@code actor()} absent, rather than the read failing outright.
 */
class PostgresEventStoreLegacyPayloadTest {

    @Test
    void aPayloadWithNoActorKeyDeserialisesWithActorAbsent() {
        ObjectMapper mapper = new ObjectMapper();
        MoneyDeposited withActor = new MoneyDeposited(
                AccountId.random(),
                2,
                Instant.parse("2026-08-01T00:00:00Z"),
                UUID.randomUUID(),
                new Money(Currency.getInstance("GBP"), 100),
                "rent",
                new Money(Currency.getInstance("GBP"), 100),
                "alice");

        // Round-trips today's shape, then strips the key a pre-feature payload never had, rather than
        // hand-writing the nested AccountId/Money/Currency JSON shape and risking it drifting from
        // what PostgresEventStore actually stores.
        ObjectNode legacy = (ObjectNode) mapper.readTree(mapper.writeValueAsString(withActor));
        legacy.remove("actor");

        MoneyDeposited deserialised = mapper.treeToValue(legacy, MoneyDeposited.class);

        assertThat(deserialised.actor()).isNull();
        assertThat(deserialised.amount()).isEqualTo(new Money(Currency.getInstance("GBP"), 100));
    }
}
