package com.flaviooliva.ledger.ledger.adapter.out.inmemory;

import com.flaviooliva.ledger.ledger.application.error.*;
import com.flaviooliva.ledger.ledger.application.port.out.EventStorePort;
import com.flaviooliva.ledger.ledger.domain.*;
import com.flaviooliva.ledger.shared.AccountId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEventStore implements EventStorePort {
    private final Map<AccountId, List<LedgerEvent>> streams = new ConcurrentHashMap<>();
    private final Map<UUID, LedgerEvent> byMovementUid = new ConcurrentHashMap<>();
    private final Object appendLock =
            new Object(); // ponytail: global lock — per-stream striping if contention ever matters

    @Override
    public void append(AccountId id, long expectedVersion, List<LedgerEvent> events) {
        synchronized (appendLock) {
            List<LedgerEvent> stream = streams.computeIfAbsent(id, k -> new ArrayList<>());
            long current = stream.isEmpty() ? 0 : stream.getLast().version();
            if (current != expectedVersion) throw new ConcurrencyConflictException(id, expectedVersion, current);
            for (LedgerEvent event : events) {
                uidOf(event).ifPresent(uid -> {
                    if (byMovementUid.containsKey(uid)) throw new DuplicateMovementException(uid);
                });
            }
            stream.addAll(events);
            events.forEach(e -> uidOf(e).ifPresent(uid -> byMovementUid.put(uid, e)));
        }
    }

    @Override
    public List<LedgerEvent> read(AccountId id) {
        synchronized (appendLock) {
            return List.copyOf(streams.getOrDefault(id, List.of()));
        }
    }

    @Override
    public Optional<LedgerEvent> findByMovementUid(UUID uid) {
        return Optional.ofNullable(byMovementUid.get(uid));
    }

    private static Optional<UUID> uidOf(LedgerEvent event) {
        return switch (event) {
            case MoneyDeposited d -> Optional.of(d.movementUid());
            case MoneyWithdrawn w -> Optional.of(w.movementUid());
            case MovementRejected r -> Optional.of(r.movementUid());
            case AccountOpened a -> Optional.empty();
        };
    }
}
