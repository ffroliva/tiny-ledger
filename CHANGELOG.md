# Changelog — Keep a Changelog format
## [Unreleased]
### Added
- Docs scaffold and governance baseline (spec §14 step 0).
- Maven skeleton, module markers, Modulith verification, CI (spec §14 step 1).
- Ledger domain, in-memory event store, balance projection, notification module (spec §14 step 2).
- OpenAPI contract and generated server interfaces (spec §14 step 3).
- Web adapters (§7 API on the in-memory core), `@standalone` Cucumber suite, README quickstart,
  CI Stage 6 docs governance (spec §14 step 4) — **Plan 1 (standalone core) complete.**
- Postgres event store with OCC and client-UID idempotency, Liquibase changelog, transactional
  outbox and its relay (spec §14 step 5).
- Postgres balance projection with keyset history pagination and Redis balance cache with
  event-driven eviction, both under the `full` profile (spec §14 step 6).
- Kafka event relay via Spring Modulith event externalisation, routed by a programmatic
  `EventExternalizationConfiguration` bean, and the `audit` module consuming `ledger.events` into an
  append-only trail (spec §14 step 7).
- Auditor REST endpoints under the `full` profile, returning 501
  `/errors/not-available-in-standalone` when the trail is absent (spec §14 step 7).
- `docker/docker-compose.yml` (Postgres, Redis, Kafka) with a named volume for the event store, the
  `full`-profile wiring, and a Failsafe `-Pit` pipeline so plain `verify` starts no containers
  (spec §14 step 8) — **Plan 2 (full persistence) complete.**
- Bounded retry (9 × 1s) and a dead-letter topic for the audit consumer, so one unprocessable record
  can no longer stall the compliance trail.
- Liquibase changeset `004` owning Modulith's `event_publication` table, with Modulith's own schema
  initializer switched off — one schema authority (spec §12).

### Changed
- **Spec v3.8 — truth alignment.** The spec and `docs/architecture.md` still promised the mechanism
  ADR 0001 replaced: Kafka routing is programmatic, not `@Externalized`, and the in-process legs are
  plain synchronous `@EventListener` in **both** run modes rather than becoming
  `@ApplicationModuleListener` in `full`. No behaviour changed; the documents were stale. The spec
  header, four revisions behind its own revision history, is now correct too.
- **ADR 0001** records a known limitation: publishing inside the transaction lets a `notification`
  log line escape for a movement whose commit then fails. `AFTER_COMMIT` is queued for Plan 3.
- **ADR 0001:** the hand-rolled transactional outbox is gone. Modulith's event-publication
  registry owns the relay, with `completion-mode=DELETE` so the queue only ever holds in-flight
  work. The use case now runs in one transaction so the publication row is written with the event.

### Fixed
- `standalone` no longer fails to boot on the JDBC driver the `full` profile put on the classpath,
  and the Liquibase changelog actually runs (Boot 4 ships that auto-configuration separately).
- The two run modes now order and filter history identically (spec §9.2b), which they did not: the
  in-memory projection broke same-millisecond ties with signed `UUID.compareTo` while Postgres
  compares `uuid` bytewise-unsigned, and it compared filter bounds at full precision while Postgres
  stores and compares them truncated to milliseconds.
- `links.next` survives being followed. Timestamp filters containing a `+` offset are percent-encoded
  on the audit endpoints instead of decoding back as a space, and the transactions feed now carries
  the caller's `limit` and both timestamp filters instead of silently paging a different result set.
- A Redis outage costs a cache miss rather than the request: `get` reads through, `put`/`evict`
  degrade to no-ops, and eviction no longer risks rolling back a money movement because a cache is
  down.
- `as_of` no longer moves backwards when an `AccountOpened` event is replayed.
