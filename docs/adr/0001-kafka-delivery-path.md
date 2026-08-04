# ADR 0001 — How domain events reach Kafka

**Status:** Accepted — option B, `@Externalized` via `spring-modulith-events-kafka`; the outbox goes
**Date:** 2026-08-04
**Context:** spec §14 steps 5–7; Plan 2 Tasks 3, 6

## Context

The `audit` module reads the ledger's event stream from Kafka. The ledger writes its events to
Postgres. Something has to move an event from one to the other, and that gap is where ledgers
lose data: write the row, publish to Kafka, and if the process dies in between, the two stores
disagree permanently — either an event the ledger believes happened never reaches the audit
trail, or a rolled-back event is published as if it were real. For a ledger the second is the
worse failure, and neither is acceptable.

Both candidate designs answer this the same way: write to exactly one store transactionally
(Postgres), and let a separate step move the record to Kafka afterwards, retrying until it
sticks. Delivery is at-least-once, so consumers must be idempotent — the balance projection
already is, keyed on `(accountId, streamVersion)`.

They differ in who owns that machinery.

## Options

### A — Our own outbox table plus polling relay (built in Task 3)

`PostgresEventStore` inserts the `event_outbox` row in the same transaction as the event append.
`OutboxEventPublisher` polls unprocessed rows (`ORDER BY created_at`, `FOR UPDATE SKIP LOCKED`),
publishes, and marks them processed. Task 6 would add a Kafka send alongside the existing
in-process publish.

- We own the schema, the SQL and the poll loop: roughly 60 lines we must test and defend.
- The atomic write — outbox row in the event-append transaction — is the part that makes it
  correct, and it already exists and is covered by `OutboxEventPublisherIT`.
- The `ledger` module needs no Kafka knowledge; `standalone` keeps the identical publish path.
- Backlog is observable as `SELECT count(*) FROM event_outbox WHERE processed = false`.

### B — Spring Modulith `@Externalized`

Annotate the domain events; add `spring-modulith-events-kafka` and the JDBC event-publication
store. Modulith owns the `event_publication` table, completion tracking, republish-on-restart
and the Kafka bridge.

- An annotation and two dependencies instead of our code; failure modes are documented by the
  framework rather than by us.
- It is what the spec §14 wording assumes, and it is instantly recognisable to anyone who runs
  Modulith already — less bespoke infrastructure for the next maintainer to learn.
- Event publication becomes coupled to the registry: `standalone` either carries it too or
  diverges from `full`.
- Same at-least-once semantics, same need for idempotent consumers, same backlog query against
  a different table.

### Not an option — both

Adding `@Externalized` on top of the existing outbox means two relays, two backlogs and two sets
of failure modes, with every event traversing both. Whichever design wins, the other goes.

## Decision

**Option B.** Routing to Kafka is declared programmatically via an `EventExternalizationConfiguration`
bean (`FullAdapterConfig`), not `@Externalized` on the events themselves — `domain` carries no
framework annotations; `spring-modulith-events-kafka` plus the JDBC publication store own the relay.
`OutboxEventPublisher`, `OutboxEventPublisherIT` and the `event_outbox` table are deleted — one relay,
not two.

Reasons, in the order they mattered:

1. **One mechanism.** Running both means two backlogs and two sets of failure modes for one
   stream of events.
2. **Retention arrives as configuration.** `spring.modulith.events.completion-mode` gives
   `UPDATE` / `DELETE` / `ARCHIVE`, and `CompletedEventPublications` gives a purge API. Our own
   outbox had no retention policy and no partial index on the unprocessed rows — the framework
   already learned what our table would have taught us at volume.
3. **Reversible cheaply.** If the publication table ever becomes the bottleneck, the events are
   still in `events` and a bespoke relay is a week's work with the same guarantees. Take the
   framework default when reversing it later is cheap; hand-roll when reversing later is dear.

We use `completion-mode=DELETE`. The publication table is a **delivery receipt, not the
regulatory record**: the moment Kafka acknowledges, the receipt has done its job and the row goes.
The queue then only ever holds in-flight and failed work, stays small permanently, and needs no
purge job, no partitioning and no retention policy of its own. The multi-year retention belongs to
`events`, which keeps every event regardless, and Kafka keeps the messages for its own retention
window on top of that.

`ARCHIVE` was the alternative and is the more conservative choice — it answers "when was this
event handed to Kafka, and did it succeed?" months later, at the cost of a second table that grows
and eventually needs partitioning. Rejected here because that question is answerable from the
event log plus broker offsets, and an unbounded table in the OLTP database lengthens every
restore.

## Consequences

**A transaction boundary has to move, and this is load-bearing.** Today `PostgresEventStore.append`
is `@Transactional` and writes the outbox row inside its own transaction, which is what makes the
current design atomic. `RecordMovementService` then calls `publisher.publish(event)` *after* that
transaction has committed. Modulith writes its publication row in whatever transaction is open
when the event is published — so with the boundary where it is now, the publication would land in
a separate transaction and the dual-write window we removed would silently reopen.

The fix: a `@Transactional` decorator around the use-case port, wired in the composition root, so
that read, append and publish share one transaction. It cannot be an annotation on the service —
`application` carries no framework annotations (ArchUnit enforces it) — and it belongs there
anyway: a transaction is an infrastructure concern applied at the port boundary.

**Only persisted listeners get a publication row.** The Kafka externalization is a transactional
listener, so it is tracked; the in-process balance projection stays a plain synchronous listener
and is not, which keeps `standalone` behaviour and read-your-writes identical.

**Unchanged either way:** delivery is at-least-once, consumers stay idempotent (the projection is,
keyed on `(accountId, streamVersion)`), and Kafka partitioning must be keyed by account id
explicitly — neither design orders across partitions for us.
