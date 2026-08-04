package com.flaviooliva.ledger.balance.application.port.in;

import java.time.Instant;

/** Keyset paging: {@code cursor} is opaque to callers; the timestamp bounds are inclusive. */
public record HistoryQuery(
        String cursor, int limit, Instant minTransactionTimestamp, Instant maxTransactionTimestamp) {}
