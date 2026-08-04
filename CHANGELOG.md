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

- Kafka event relay via Spring Modulith `@Externalized` routing and the `audit` module consuming
  `ledger.events` into an append-only trail (spec §14 step 7).

### Changed
- **ADR 0001:** the hand-rolled transactional outbox is gone. Modulith's event-publication
  registry owns the relay, with `completion-mode=DELETE` so the queue only ever holds in-flight
  work. The use case now runs in one transaction so the publication row is written with the event.

### Fixed
- `standalone` no longer fails to boot on the JDBC driver the `full` profile put on the classpath,
  and the Liquibase changelog actually runs (Boot 4 ships that auto-configuration separately).
