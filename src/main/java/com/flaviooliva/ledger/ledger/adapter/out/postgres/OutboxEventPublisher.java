package com.flaviooliva.ledger.ledger.adapter.out.postgres;

import com.flaviooliva.ledger.ledger.application.port.out.EventPublisherPort;
import com.flaviooliva.ledger.ledger.domain.AccountOpened;
import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.ledger.domain.MoneyDeposited;
import com.flaviooliva.ledger.ledger.domain.MoneyWithdrawn;
import com.flaviooliva.ledger.ledger.domain.MovementRejected;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile("full")
public class OutboxEventPublisher {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EventPublisherPort eventPublisher;

    public OutboxEventPublisher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, EventPublisherPort eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        String selectSql = "SELECT id, event_type, payload FROM event_outbox WHERE processed = false ORDER BY created_at ASC LIMIT 50 FOR UPDATE SKIP LOCKED";
        List<OutboxEntry> entries = jdbcTemplate.query(selectSql, (rs, rowNum) -> {
            UUID id = rs.getObject("id", UUID.class);
            String eventType = rs.getString("event_type");
            String payload = rs.getString("payload");
            return new OutboxEntry(id, eventType, payload);
        });

        for (OutboxEntry entry : entries) {
            try {
                LedgerEvent event = switch (entry.eventType()) {
                    case "AccountOpened" -> objectMapper.readValue(entry.payload(), AccountOpened.class);
                    case "MoneyDeposited" -> objectMapper.readValue(entry.payload(), MoneyDeposited.class);
                    case "MoneyWithdrawn" -> objectMapper.readValue(entry.payload(), MoneyWithdrawn.class);
                    case "MovementRejected" -> objectMapper.readValue(entry.payload(), MovementRejected.class);
                    default -> throw new IllegalStateException("Unknown event type: " + entry.eventType());
                };
                eventPublisher.publish(event);
                jdbcTemplate.update("UPDATE event_outbox SET processed = true WHERE id = ?", entry.id());
            } catch (JacksonException e) {
                throw new RuntimeException("Failed to deserialize outbox payload", e);
            }
        }
    }

    private record OutboxEntry(UUID id, String eventType, String payload) {}
}
