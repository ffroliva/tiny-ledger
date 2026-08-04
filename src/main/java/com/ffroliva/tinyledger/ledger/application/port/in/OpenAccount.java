package com.ffroliva.tinyledger.ledger.application.port.in;

import java.util.Currency;

/** §2.4: caller = JWT subject or the fixed standalone principal. */
public record OpenAccount(String caller, String name, Currency currency) {}
