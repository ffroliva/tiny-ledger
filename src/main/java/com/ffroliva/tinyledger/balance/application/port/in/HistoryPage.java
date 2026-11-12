package com.ffroliva.tinyledger.balance.application.port.in;

import java.util.List;

/** {@code nextCursor} is {@code null} once the feed is exhausted. */
public record HistoryPage(List<TransactionView> transactions, String nextCursor) {
    public HistoryPage {
        transactions = transactions == null
                ? null
                : List.copyOf(transactions); // own copy; null-preserving: an absent list stays absent
    }
}
