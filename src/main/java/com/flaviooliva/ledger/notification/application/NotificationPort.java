package com.flaviooliva.ledger.notification.application;

/** Outbound port: delivers a notification. The standalone profile's only implementation logs it. */
public interface NotificationPort {
    void record(Notification notification);
}
