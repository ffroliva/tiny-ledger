package com.ffroliva.tinyledger.ledger.domain.policy;

import com.ffroliva.tinyledger.shared.Money;

public final class OverdraftPolicy {
    private OverdraftPolicy() {}

    /** No overdraft in this PoC (spec §2.2 invariant 1, §15 assumption 2). */
    public static boolean permits(Money balanceAfter) {
        return !balanceAfter.isNegative();
    }
}
