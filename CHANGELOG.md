# Changelog — Keep a Changelog format
## [Unreleased]
### Added
- Docs scaffold (spec §14 step 0).
- Maven skeleton, module markers, Modulith verification, CI (spec §14 step 1).
- Ledger domain, in-memory event store, balance projection, notification module (spec §14 step 2).
- OpenAPI contract and generated server interfaces (spec §14 step 3).
- Web adapters (§7 API on the in-memory core), `@standalone` Cucumber suite, README quickstart
  (spec §14 step 4) — **Plan 1 (standalone core) complete.**
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
- **One error catalogue.** `ErrorCode` (all eleven spec §6.5 rows) and `TinyLedgerException` in
  `shared`, framework-free; six `@ExceptionHandler` methods collapse to one. A completeness test pins
  the catalogue against the spec table, so a dropped row is a red test rather than a silent hole.
- **Spring Security, configured per profile rather than excluded in one.** `full` requires a JWT;
  `standalone` stays contractually open. The 401 and the chain-level 403 carry the catalogued RFC 7807
  body via `SecurityProblemHandler`, because both are written before `DispatcherServlet` and
  `ErrorHandlingAdvice` never sees them.
- **A real caller principal.** Resolved from the JWT subject, failing **closed** outside `standalone`,
  and refusing a well-signed token that carries no `sub` — which would otherwise stamp `null` as an
  account's owner.
- **Ownership authorization on the projection-backed reads**, applied by a decorator at the port
  boundary in the composition root. Writes and strong reads keep their in-service check, because that
  one authorises against the rehydrated aggregate — the system of record — which a boundary check
  cannot see. Absent answers 404, wrong-owner answers 403 (spec §6.5).
- `x-fapi-interaction-id` **validate-or-mint**, ordered ahead of the security chain so the 401 and the
  chain-level 403 are correlatable, and surfaced as the `traceId` on every problem response. A value
  that is not an RFC 4122 UUID is **replaced**, not echoed and not sanitised — an allowlist cannot be
  defeated by an encoding a stripper did not anticipate, and the rejected value is never logged.
- One `full` Spring test context for the whole integration suite, and a CI split into
  `gate`/`unit`/`integration` — **the integration suite was previously gated by nothing**: no job ran
  `-Pit` at all. (The count once quoted here is dropped rather than refreshed: it was never paired
  with its run's exit code, which is the thing that makes a count mean anything — AGENTS trap 3, and
  the same retraction ADR 0003 carries.)
- `..shared..` fenced from frameworks alongside `..domain..`. ArchUnit checks *direct* dependencies,
  and the domain now compiles against `shared.error`, so a domain-only rule would have stayed green
  while a Spring import reached the domain's transitive compile path.
- **`actor` on every domain event**, so a movement records *who acted* alongside *who owns*. The
  `(actor, owner)` pair is the whole record of a delegation; a legacy payload with no `actor` key
  still deserialises (spec §2.3/§2.4/§4.1).
- **`ledger:admin`, which widens ownership for change operations only — never reads.** Of five
  comparison points exactly one widens: `RecordMovementService`'s in-service check. Strong reads are
  reads, so `?consistency=strong` gets no widening, and admin is not an auditor — separation of
  duties is kept. It is a conjunction, not a superuser short-circuit: `ledger:admin` without
  `ledger:writer` is still refused (spec §6.4, scenario N15).
- **The audit trail surfaces the acting principal**, via `audit_entries.actor` (Liquibase changeset
  `005`) and an `actor` Kafka header. The payload is authoritative and the header an optimisation: a
  dropped header warns and uses the payload, and only a genuine *disagreement* faults to the DLT —
  losing a correctly attributable compliance entry is worse than recording it loudly (spec §15.10,
  §15.11).
- Realm fixture user `trent` (`ledger:writer` + `ledger:reader` + `ledger:admin`) and the P9 /
  N13–N18 authorisation scenarios, exercised through the real chain against a Keycloak container
  — **Plan 4 (admin on-behalf-of) complete**, spec v3.12.
- **Actuator liveness and readiness probes, on their own management port** (spec §14 step 9 part 1,
  §6.6, ADR 0004/0005). `9090`, pinned to loopback in `standalone` only — the kubelet dials the pod
  IP, so pinning it in `full` would fail every Kubernetes `httpGet` probe. The probes answer without a
  credential, because one that needs a token cannot report the outage that took the issuer away.
- **Endpoint exposure assessed per endpoint rather than defaulted**, and closed by two independent
  layers: `exposure.include=health`, and `denyAll` on a management-scoped security chain. They do not
  overlap on the health *root* — exposing `health` is what maps it — so `denyAll` is the only thing
  withholding the aggregate status §6.6 refuses. `heapdump`, `env`, `configprops`, `loggers`,
  `httpexchanges`, `threaddump` and the rest are unreachable, each for a stated reason.
- **`ledger.outbox.pending.age.seconds`**, `full` only: the age of the oldest incomplete event
  publication, excluding `FAILED` rows so one poison row cannot pin the reading forever. **Nothing
  gates on it** — readiness gating on outbox lag would remove an instance during exactly the Kafka
  outage `E11` requires the ledger to survive (ADR 0004).
- `server.shutdown=graceful` — a correctness property for a ledger, not an operational nicety:
  without it, in-flight writes die mid-request on an ordinary rolling deploy.
- **`E9` closed, and the §9.3 catalogue now has no open cases for the first time.** `AuditLagIT`
  pauses the broker, watches the gauge cross §6.6's 5 s threshold, reads the balance back exactly, and
  asserts readiness stays `UP`.

### Removed
- **CI stage 6 and the vendored ISO-compliance skill, deleted rather than repaired.** The script
  resolved `REPO_ROOT` inside its own directory, so all five of its checks scanned a tree holding
  none of their 17 artefacts and the gate reported `governance OK: 17 known, 0 new` unconditionally.
  A green check that verifies nothing is worse than no check, and generating the 17 artefacts to turn
  it green would have added exactly the noise the pass existed to remove. §12.1's stage 6 is struck
  in place rather than the table renumbered, because six citations across three files name absolute
  stage numbers and no gate would catch them drifting.
- `docs/how-to/` and `docs/tutorial/`, both empty and both routed to. The eleven delivered plans
  (8,838 lines of agent execution script, five times the spec) moved to `docs/_archive/`.
  `docs/agentic-workflow.md` deliberately stays — it is the readable account of how this was built.

### Security
- **Rate limiting (§6.1) exists.** Token buckets per principal and per IP, whichever is more
  restrictive; Bucket4j over Redis in `full`, Caffeine in `standalone`; `429` with `Retry-After` and
  the catalogued problem type. The per-IP backstop runs **ahead of authentication**, so a flood of
  invalid bearer tokens is metered — placed behind it, the cheapest flood was unmetered. A Redis
  outage fails **open** after a bounded 250 ms, rather than taking the API down.
- **Token audience is validated.** With only `issuer-uri`, any token the realm issued was accepted —
  including one minted for a different client.
- **Boot's `/error` no longer echoes the request path** (§6.5 forbids internal identifiers crossing
  the boundary). Excluded in both properties files, because a profile declaration shadows the base
  entirely rather than appending to it.
- **A forged `x-fapi-interaction-id` can no longer write log lines.** The header was echoed verbatim
  into the response and the MDC, and the filter runs ahead of the security chain — so log forging was
  reachable by an unauthenticated request.
- **Operator-managed IP exemptions** for rate limiting: empty by default, matched on
  `getRemoteAddr()` only and never a header, configuration-only with no runtime endpoint.
- **The committed test signing key is removed from the tree — not from history.** Deleted in
  `58f2638`; the blob remains reachable through `f334f81`, and that is a deliberate decision rather
  than an oversight. `TestJwt` now generates a keypair per JVM, which is strictly stronger — the
  issuer cannot know a key that did not exist when the container started. The key was test-only, had
  no consumer, and no issuer ever trusted it, so **no rotation is owed**. Rewriting history to drop
  the blob was considered and rejected: it would force-push every branch and orphan every clone, to
  remove a key that authenticates nothing. Should this repository ever be made public, revisit that
  trade — the calculation changes with the audience, not with the key. `.gitignore:26` refuses
  `*.pem`, so the filename cannot silently return.
- **`full` temporarily refused both auditor operations with 403 until the `ledger:auditor` role
  existed.** `accountUid` is optional on the trail and `PostgresAuditTrail` builds `WHERE true`, so once
  `full` became authenticated *any* valid token could page every account's id, amount and reference —
  which also voids §6.5's "account UUIDs are unguessable" premise. Confirmed live before it was closed.
  Ownership decoration could not help: an audit trail is deliberately not owner-scoped. **Closed** by the
  role enforcement below — a token holding `ledger:auditor` now reads the trail; every other token is
  still refused.
- **`ledger:reader` / `ledger:writer` / `ledger:auditor` enforced on the `full` filter chain**,
  replacing the temporary auditor denial above and the previously-unenforced writer/reader roles.
  `KeycloakRealmRolesConverter` maps Keycloak's nested `realm_access.roles` onto Spring authorities;
  rules use `hasAuthority` rather than `hasRole`, since Spring's `hasRole` prepends `ROLE_`, which these
  names do not carry.
- **A real Keycloak container and realm behind the integration suite**
  (`docker/keycloak/realm-tiny-ledger.json`), provisioning the three roles and six pinned-UUID test
  users, so every IT exercises the production `issuer-uri` decoder branch instead of a committed test
  key.
- **`HEAD` is subject to the same role rule as `GET`, and the lesson now applies to the write path too.**
  The reader matcher named `GET` explicitly; `hasAuthority` matches on `request.getMethod()`, and Spring
  MVC serves `HEAD` from the same `@GetMapping` handler by default, so a `HEAD` request fell through
  every role rule to `anyRequest().authenticated()` while still returning real status and
  `Content-Length` — fixed by making the reader matcher method-less. The deposits/withdrawals matcher had
  the same shape of gap: scoped to `PUT`, it let any other verb on a money path fall through to the
  weaker reader rule. Now method-less there too. `POST /api/v1/accounts` stays method-scoped
  deliberately — broadening it the same way would block readers from `GET /api/v1/accounts`.
- **Closed a framework-contributed `/logout` route** present in both run modes and authorised by
  nothing. Spring applies `logout(withDefaults())` unconditionally and, with CSRF disabled, matches
  `GET`/`PUT`/`DELETE` too; `LogoutFilter` precedes `AuthorizationFilter`, so `full` answered an
  unauthenticated `GET /logout` with a 302 to a page this API does not serve.

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
- **A stray `IllegalArgumentException` no longer claims to be an invalid amount (CR12).** Every one
  used to map to `400 /errors/invalid-amount`, so a malformed pagination cursor told the caller their
  *amount* was wrong. Both request-path `Currency.getInstance` call sites are now typed through one
  guard — the open-account path went through a bare JDK call, not `Money.of`, so patching only the
  latter would have turned the very test that proves this into a 500.
