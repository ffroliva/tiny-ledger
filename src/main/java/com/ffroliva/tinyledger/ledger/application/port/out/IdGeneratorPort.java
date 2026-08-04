package com.ffroliva.tinyledger.ledger.application.port.out;

import java.util.UUID;

@FunctionalInterface
public interface IdGeneratorPort {
    UUID next();
}
