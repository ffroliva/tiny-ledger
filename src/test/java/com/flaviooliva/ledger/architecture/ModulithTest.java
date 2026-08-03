package com.flaviooliva.ledger.architecture;

import com.flaviooliva.ledger.LedgerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithTest {
    @Test
    void modularStructureIsValid() {
        ApplicationModules.of(LedgerApplication.class).verify();
    }
}
