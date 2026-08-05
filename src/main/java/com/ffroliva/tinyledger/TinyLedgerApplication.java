package com.ffroliva.tinyledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class TinyLedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TinyLedgerApplication.class, args);
    }
}
