package com.flaviooliva.ledger.ledger.application.port.out;

import java.time.Instant;

@FunctionalInterface
public interface ClockPort {
    Instant now();
}
