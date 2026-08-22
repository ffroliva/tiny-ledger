package com.ffroliva.tinyledger.ledger.application.port.out;

import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import java.util.List;

/**
 * One page of the global event log, plus the cursor to resume from.
 *
 * <p>{@code nextCursor} is opaque to the caller: pass it back verbatim to get the following page.
 * An exhausted log returns an empty {@code events} list and the cursor unchanged, so a caller loops
 * until {@code events} is empty rather than comparing cursors.
 */
public record EventPage(List<LedgerEvent> events, long nextCursor) {
    public EventPage {
        events = List.copyOf(events);
    }
}
