package com.ffroliva.tinyledger.notification.adapter.out.log;

import com.ffroliva.tinyledger.notification.application.Notification;
import com.ffroliva.tinyledger.notification.application.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spec §3/P8: the standalone profile's only delivery mechanism — one structured log line. */
public class LogNotificationAdapter implements NotificationPort {
    private static final Logger log = LoggerFactory.getLogger(LogNotificationAdapter.class);

    @Override
    public void recordNotification(Notification notification) {
        log.info(
                "notification kind={} movementUid={} accountUid={} minorUnits={}",
                notification.kind(),
                notification.movementUid(),
                notification.accountId().value(),
                notification.amount().minorUnits());
    }
}
