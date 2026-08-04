# tiny-ledger Plan 2 — Full Persistence (spec §14 steps 5–7)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend tiny-ledger with full persistence under the `full` profile — Postgres event store with OCC & Liquibase migrations, Redis balance cache with event-driven eviction, Spring Modulith Kafka event publication relay, and the `audit` module serving auditor endpoints (`/api/v1/accounts/{accountUid}/events` and `/api/v1/audit/entries`), validated by Testcontainers `*IT` integration suites and Docker Compose.

**Architecture:** 
- `full` Spring profile activating production adapters alongside `standalone`.
- **Postgres Event Store**: Liquibase-managed schema `events`, optimistic concurrency control on aggregate sequence numbers, client UID idempotency index, transaction outbox table.
- **Postgres Balance Projection**: Persistent projection store for account balances and transaction history.
- **Redis Cache Adapter**: `RedisBalanceCache` implementing `BalanceCachePort` with key-based cache invalidation and TTL.
- **Kafka Relay**: Spring Modulith event publication relay publishing events to Kafka topics.
- **Audit Module**: Consumes Kafka event streams to maintain audit logs and serve auditor REST endpoints with pagination and filtering.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring Modulith (Kafka module), Liquibase, PostgreSQL (Driver + Testcontainers), Redis (Lettuce + Testcontainers), Kafka (Spring Kafka + Testcontainers), OpenAPI, ArchUnit.

---

## Global Constraints

- Package root `com.flaviooliva.ledger`; package layout per spec §3.1.
- `standalone` remains intact and passing all existing unit and Cucumber tests.
- `full` profile adds Postgres, Redis, Kafka, and Audit adapters without modifying domain model or application contracts.
- Hexagonal rules hold: zero framework annotations in `domain` or `application` logic.
- All integration tests use Testcontainers (`*IT.java` naming) and are excluded from default unit verify unless `full` integration suite is targeted or testcontainers environment is available.
- TDD strictly: failing test first, minimal implementation, green, commit per task.

---

## Plan 2 Task Breakdown

### Task 0: Infrastructure dependencies & Testcontainers Scaffold
- **Goal**: Add Testcontainers dependencies (PostgreSQL, Redis, Kafka) and Liquibase migration support to `pom.xml`. Create base `AbstractIntegrationTest` with shared container configuration.
- **Model**: `sonnet`
- **Files**:
  - `pom.xml`
  - `src/test/java/com/flaviooliva/ledger/testsupport/AbstractIntegrationTest.java`
- **Verification**: `./mvnw test-compile`

### Task 1: Liquibase Migrations for Event Store & Outbox (Spec §14 step 5)
- **Goal**: Create V1 Liquibase SQL migration script for `events` table, unique index on `(aggregate_id, sequence_number)`, unique index on `client_movement_uid`, and `event_outbox` table.
- **Model**: `sonnet`
- **Files**:
  - `src/main/resources/db/changelog/db.changelog-master.sql`
  - `src/test/java/com/flaviooliva/ledger/ledger/adapter/out/postgres/LiquibaseMigrationTest.java`
- **Verification**: `./mvnw test -Dtest=LiquibaseMigrationTest`

### Task 2: Postgres Event Store Adapter with Optimistic Concurrency & Idempotency (Spec §14 step 5)
- **Goal**: Implement `PostgresEventStore` implementing `EventStorePort`. Enforce OCC (`ConcurrencyConflictException` on duplicate sequence number) and idempotency (`IdempotencyConflictException` / `DuplicateMovementException`).
- **Model**: `opus`
- **Files**:
  - `src/main/java/com/flaviooliva/ledger/ledger/adapter/out/postgres/PostgresEventStore.java`
  - `src/test/java/com/flaviooliva/ledger/ledger/adapter/out/postgres/PostgresEventStoreIT.java`
- **Verification**: `./mvnw test -Dtest=PostgresEventStoreIT`

### Task 3: Postgres Transactional Outbox & Event Publisher (Spec §14 step 5)
- **Goal**: Write outbox records atomically with events in `PostgresEventStore` and implement outbox relay to publish domain events.
- **Model**: `opus`
- **Files**:
  - `src/main/java/com/flaviooliva/ledger/ledger/adapter/out/postgres/OutboxEventPublisher.java`
  - `src/test/java/com/flaviooliva/ledger/ledger/adapter/out/postgres/OutboxEventPublisherIT.java`
- **Verification**: `./mvnw test -Dtest=OutboxEventPublisherIT`

### Task 4: Persistent Postgres Balance Projection & Keyset Pagination (Spec §14 step 6)
- **Goal**: Add V2 Liquibase migration for `balance_projections` and `account_history` tables. Implement `PostgresBalanceProjection` implementing `BalanceProjectionPort`.
- **Model**: `opus`
- **Files**:
  - `src/main/resources/db/migration/V2__init_balance_projection.sql`
  - `src/main/java/com/flaviooliva/ledger/balance/adapter/out/postgres/PostgresBalanceProjection.java`
  - `src/test/java/com/flaviooliva/ledger/balance/adapter/out/postgres/PostgresBalanceProjectionIT.java`
- **Verification**: `./mvnw test -Dtest=PostgresBalanceProjectionIT`

### Task 5: Redis Balance Cache & Event-Driven Eviction (Spec §14 step 6)
- **Goal**: Implement `RedisBalanceCache` implementing `BalanceCachePort` with key-based cache invalidation and 60s TTL.
- **Model**: `opus`
- **Files**:
  - `src/main/java/com/flaviooliva/ledger/balance/adapter/out/redis/RedisBalanceCache.java`
  - `src/test/java/com/flaviooliva/ledger/balance/adapter/out/redis/RedisBalanceCacheIT.java`
- **Verification**: `./mvnw test -Dtest=RedisBalanceCacheIT`

### Task 6: Kafka Event Relay & Audit Module Consumer (Spec §14 step 7)
- **Goal**: Configure Spring Modulith `@Externalized` event publication to Kafka topics (`ledger.events`). Implement `audit` module event listener saving audit entries to audit table.
- **Model**: `opus`
- **Files**:
  - `src/main/resources/db/migration/V3__init_audit_store.sql`
  - `src/main/java/com/flaviooliva/ledger/audit/adapter/in/events/AuditKafkaListener.java`
  - `src/main/java/com/flaviooliva/ledger/audit/adapter/out/postgres/PostgresAuditRepository.java`
  - `src/test/java/com/flaviooliva/ledger/audit/KafkaAuditModuleIT.java`
- **Verification**: `./mvnw test -Dtest=KafkaAuditModuleIT`

### Task 7: Auditor REST Endpoints in `full` Profile (Spec §7, §14 step 7)
- **Goal**: Implement `AuditController` serving `GET /api/v1/accounts/{accountUid}/events` and `GET /api/v1/audit/entries` in `full` profile mode.
- **Model**: `opus`
- **Files**:
  - `src/main/java/com/flaviooliva/ledger/audit/adapter/in/web/AuditController.java`
  - `src/test/java/com/flaviooliva/ledger/audit/adapter/in/web/AuditControllerTest.java`
- **Verification**: `./mvnw test -Dtest=AuditControllerTest`

### Task 8: Docker Compose & End-to-End `@full` Scenario Verification
- **Goal**: Provide `docker/docker-compose.yml` with PostgreSQL, Redis, Kafka, and Zookeeper/KRaft services. Wire `application-full.properties`. Run full verification pipeline.
- **Model**: `sonnet`
- **Files**:
  - `docker/docker-compose.yml`
  - `src/main/resources/application-full.properties`
- **Verification**: `./mvnw -q verify`
