package com.flaviooliva.ledger.ledger.application.port.out;

import java.util.UUID;

@FunctionalInterface
public interface IdGeneratorPort {
    UUID next();
}
