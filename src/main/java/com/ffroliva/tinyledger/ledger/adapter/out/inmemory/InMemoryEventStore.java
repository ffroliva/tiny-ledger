package com.ffroliva.tinyledger.ledger.adapter.out.inmemory;

import com.ffroliva.tinyledger.ledger.application.error.*;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.domain.*;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEventStore implements EventStorePort {
    private final Map<AccountId, List<LedgerEvent>> streams = new ConcurrentHashMap<>();
    private final Map<UUID, MovementEvent> byMovementUid = new ConcurrentHashMap<>();
    private final Object appendLock =
            new Object(); // ponytail: global lock — per-stream striping if contention ever matters

    @Override
    public void append(AccountId id, long expectedVersion, List<? extends LedgerEvent> events) {
        synchronized (appendLock) {
            List<LedgerEvent> stream = streams.computeIfAbsent(id, k -> new ArrayList<>());
            long current = stream.isEmpty() ? 0 : stream.getLast().version();
            if (current != expectedVersion) throw new ConcurrencyConflictException(id, expectedVersion, current);
            for (LedgerEvent event : events) {
                if (event instanceof MovementEvent m && byMovementUid.containsKey(m.movementUid())) {
                    throw new DuplicateMovementException(m.movementUid());
                }
            }
            stream.addAll(events);
            events.forEach(e -> {
                if (e instanceof MovementEvent m) byMovementUid.put(m.movementUid(), m);
            });
        }
    }

    @Override
    public List<LedgerEvent> read(AccountId id) {
        synchronized (appendLock) {
            return List.copyOf(streams.getOrDefault(id, List.of()));
        }
    }

    @Override
    public Optional<MovementEvent> findByMovementUid(UUID uid) {
        return Optional.ofNullable(byMovementUid.get(uid));
    }
}
