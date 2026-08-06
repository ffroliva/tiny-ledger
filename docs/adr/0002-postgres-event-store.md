# ADR 0002 — Postgres as the event store, Kafka as the bus

**Status:** Accepted
**Date:** 2026-08-03 (decision), 2026-08-06 (recorded here)
**Context:** spec §4.2, §4.3; superseded the author's own V2 design

> **Why this file arrives after `0003`.** The decision was taken at spec v3.0 and written into
> `docs/spec.md` §4.2 rather than into its own file, while four documents went on citing it as
> "ADR-002" (`spec.md` §4.2/§4.3, `agentic-workflow.md` §6 and its tooling table). `0003` took the
> next free number rather than occupy one the spec already pointed elsewhere. This file closes that
> gap by writing the ADR the references always assumed, so the number now resolves to the decision
> it names. The spec remains the contract; this records the reasoning and the rejected alternative.

## Context

An event-sourced ledger needs one store to be the system of record — the thing that decides, under
concurrency, whether a movement happened. That store has to answer a single question correctly:
*append these events to this stream **only if** the stream is still at the version I read.* Without
it, two concurrent withdrawals can each read a balance of 100, each judge themselves affordable, and
both commit.

The author's own V2 specification had **Kafka as the log itself** — the event stream as topic,
consumers building state from it. This ADR reverses that position.

Separately, the `audit` module must read every ledger event without sharing the ledger's database.
That is a fan-out problem, not a system-of-record problem, and the two are easy to conflate because
one technology is marketed for both.

## Options

### A — Kafka as the event log (the V2 position)

The topic is the stream; Postgres holds only read models, if anything.

- One technology for both persistence and distribution.
- **No conditional append.** Kafka has no "write only if the partition is at offset N" primitive, so
  per-stream optimistic concurrency cannot be enforced at the point of write. The invariant a ledger
  exists to protect becomes advisory.
- **No read-your-writes on a stream.** Rehydrating an aggregate means consuming a partition and
  hoping the local view is current.
- **Unbounded retention is a liability, not a feature.** A compacted topic holding customer
  transaction history forever is a compliance surface — right-to-erasure, retention policy and
  jurisdiction all land on infrastructure designed to be replayed, not audited.

### B — Postgres as the event store, Kafka as the publication bus

`ledger_event` is the system of record. Events reach Kafka afterwards via a transactional outbox
(the mechanism itself is [ADR 0001](0001-kafka-delivery-path.md)).

- `append(streamId, expectedVersion, events)` is atomic, and `UNIQUE (stream_id, version)` **is** the
  concurrency control — the database rejects the second writer rather than the application detecting
  it after the fact.
- Read-your-writes on a stream is free.
- Retention is an ordinary data-governance problem against an ordinary table.
- Kafka then does what it is genuinely good at: fanning events out to consumers that must not share
  a database.

## Decision

**Option B.** PostgreSQL is the event store and system of record. Kafka is the publication bus, fed
by a transactional outbox.

The deciding reason is narrow and sufficient: **Kafka offers no conditional append on a stream, and
therefore cannot enforce the one invariant a ledger exists to protect.** Everything else — retention,
read-your-writes, operational familiarity — followed the same way, but none of it was needed once the
concurrency argument landed.

Two Kafka choices are design rather than configuration, and both are fixed by this decision (spec
§4.3):

- **One topic, `ledger.events`.** `audit` consumes every event type and needs their per-account order
  preserved; topic-per-event-type would destroy it.
- **Partition key = `accountId`.** Kafka orders only within a partition, and the key is what makes
  the ordering scenarios physically possible.

Everything else — partition count, retention hours, compression, batch sizes — is `spring.kafka.*`
configuration.

## Consequences

**Kafka retention is deliberately short.** The topic is transport, never the record. The multi-year
answer lives in `ledger_event`, which keeps every event regardless.

**The ledger owns its own concurrency.** A version conflict surfaces as `409` and the caller retries
(spec §4.1 step 5). No distributed coordination, no lock service.

**Delivery to Kafka is at-least-once, so consumers must be idempotent.** The balance projection is,
keyed on `(accountId, streamVersion)`. This is a property of the outbox in ADR 0001, inherited here.

**Postgres becomes a hard dependency of `full`, and of nothing else.** `standalone` runs the same
domain code against an in-memory store, which is what keeps the brief-compliant path to one command.
The hexagonal boundaries in spec §4 are what make that substitution possible — the aggregate never
learns which store it is talking to.

**Reversing this is expensive**, which is why it is an ADR rather than a configuration value. Moving
the system of record later means migrating history, not swapping an adapter.

## Note on how this decision was reached

This reverses the author's own prior design. It is recorded because changing position against your
own earlier specification is worth more than defending it — and because the `llm-council` pass that
pressure-tested the spec was aimed at exactly this decision, alongside the §13 non-goals and the
dual-mode delivery (`agentic-workflow.md` §6).
