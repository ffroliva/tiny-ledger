package com.ffroliva.tinyledger.architecture;

import com.ffroliva.tinyledger.TinyLedgerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithTest {
    @Test
    void modularStructureIsValid() {
        ApplicationModules.of(TinyLedgerApplication.class).verify();
    }
}
