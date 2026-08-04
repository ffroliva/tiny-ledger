package com.flaviooliva.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spec §3: the large-movement threshold, in minor units — a composition-root decision. */
@ConfigurationProperties(prefix = "ledger.notification")
public record NotificationProperties(long largeMovementMinorUnits) {}
