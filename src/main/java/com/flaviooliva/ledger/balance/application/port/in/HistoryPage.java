package com.flaviooliva.ledger.balance.application.port.in;

import java.util.List;

/** {@code nextCursor} is {@code null} once the feed is exhausted. */
public record HistoryPage(List<TransactionView> transactions, String nextCursor) {}
