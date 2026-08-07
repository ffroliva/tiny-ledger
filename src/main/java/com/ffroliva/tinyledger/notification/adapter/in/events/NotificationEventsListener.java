package com.ffroliva.tinyledger.notification.adapter.in.events;

import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.notification.application.NotificationPort;
import com.ffroliva.tinyledger.notification.application.NotificationRules;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The inbound edge of {@code notification}: turns published ledger events into notification
 * records via {@link NotificationRules}, then hands qualifying ones to {@link NotificationPort}.
 *
 * <p>Plain {@code @EventListener} in both run modes, not {@code @ApplicationModuleListener} (spec
 * v3.8, §4.3) — see {@code balance.adapter.in.events.LedgerEventsListener}'s javadoc for the full
 * story of why the latter is registered but never fires.
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
        rules.evaluate(event).ifPresent(port::recordNotification);
    }
}
