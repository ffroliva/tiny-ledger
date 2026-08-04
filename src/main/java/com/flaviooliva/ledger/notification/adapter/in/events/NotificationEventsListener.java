package com.flaviooliva.ledger.notification.adapter.in.events;

import com.flaviooliva.ledger.ledger.domain.LedgerEvent;
import com.flaviooliva.ledger.notification.application.NotificationPort;
import com.flaviooliva.ledger.notification.application.NotificationRules;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The inbound edge of {@code notification}: turns published ledger events into notification
 * records via {@link NotificationRules}, then hands qualifying ones to {@link NotificationPort}.
 *
 * <p>Standalone caveat (spec v3.5, §4.3): plain {@code @EventListener}, not
 * {@code @ApplicationModuleListener} — see {@code balance.adapter.in.events.LedgerEventsListener}'s
 * javadoc for the full story of why the latter is registered but never fires in this profile.
 */
@Component
public class NotificationEventsListener {
    private final NotificationRules rules;
    private final NotificationPort port;

    public NotificationEventsListener(NotificationRules rules, NotificationPort port) {
        this.rules = rules;
        this.port = port;
    }

    @EventListener
    void on(LedgerEvent event) {
        rules.evaluate(event).ifPresent(port::record);
    }
}
