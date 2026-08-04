package com.flaviooliva.ledger.notification.adapter.out.log;

import com.flaviooliva.ledger.notification.application.Notification;
import com.flaviooliva.ledger.notification.application.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spec §3/P8: the standalone profile's only delivery mechanism — one structured log line. */
public class LogNotificationAdapter implements NotificationPort {
    private static final Logger log = LoggerFactory.getLogger(LogNotificationAdapter.class);

    @Override
    public void record(Notification notification) {
        log.info(
                "notification kind={} movementUid={} accountUid={} minorUnits={}",
                notification.kind(),
                notification.movementUid(),
                notification.accountId().value(),
                notification.amount().minorUnits());
    }
}
