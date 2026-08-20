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
- **A container image, and the image is what CI tests** (issue #11, spec §12, ADR 0005) — spec
  v3.42. Built by `spring-boot:build-image` with **Paketo buildpacks and no `Dockerfile`**, so it is
  layered by construction and there is no base image to patch and forget. The **JVM AOT cache** is
  on, trained under `standalone` because the training run starts the application and would otherwise
  block on Postgres, Redis, Kafka and Liquibase: **startup 6.588 s → 3.011 s, −54%**, measured over
  three runs each rather than asserted.
- The application is now a **Compose service** behind `profiles: [app]`, so `full` no longer means
  "four containers plus a JVM on your host". A plain `up` still starts exactly the four backing
  services. `scripts/e2e/run-e2e.sh` runs the **image** by default and keeps the host jar behind
  `E2E_MODE=jar`. The jar path was run by hand when it landed (7 passed), but **nothing in CI
  exercises it** — the e2e job builds the image, not the jar — so it is retained coverage, not
  asserted coverage. Keycloak's hostname is pinned, because without it the issuer it
  stamps varies with how the caller dialled in — `127.0.0.1:8081` and `localhost:8081` minted
  different issuers and only one authenticated.
- **Stage 11 is no longer partial.** Trivy scans the built image (`CRITICAL,HIGH`, `exit-code: 1`)
  in the **required** `security` job, because a scan in a job nobody must pass cannot stop a merge.
  OWASP Dependency-Check scans the build tree (`failBuildOnCVSS=7`) in its **own `depcheck` job**,
  which is **not** a required check — inside `security` it did not finish within 80 minutes, and a
  required check that slow is one people route around. So a CVSS ≥ 7 finding fails the workflow and
  is visible on the PR, but does **not** block the merge button until `depcheck` is added to branch
  protection. It does cover **test-scope** dependencies — but only after `skipTestScope` was set to
  `false`, because the plugin defaults it to `true`; for two runs this job closed a gap of exactly
  zero while the docs said otherwise. The proof is `android-json` and `httpcore5`, two jars that
  reach the build via `spring-boot-starter-test` and Testcontainers and are never packaged into the
  image, so Trivy cannot see them.
  **The Trivy gate found a real HIGH on its first honest run** —
  `CVE-2026-54291` in `org.postgresql:postgresql` — which was **fixed by upgrading to 42.7.13, not
  suppressed**. The image is built and scanned but **never published**; that stays stage 12.
- **Distributed tracing, OTLP export and an opt-in OTel Collector** (spec §14 step 9 parts 2 and 3,
  §6.6, ADR 0005) — **step 9 complete**, spec v3.41. Micrometer Tracing over the OpenTelemetry
  *bridge*, wired by a single `spring-boot-starter-opentelemetry`. Four spans: HTTP, the use-case
  decorator, the projection apply, and the audit consumer — which is **linked** to the producing span
  rather than parented by it, because a child would make the request appear to last until the slowest
  consumer finished and would misreport latency to every dashboard. One bounded counter,
  `ledger.movements`, tagged `type`/`outcome`/`reason`; account ids and movement UIDs stay on spans and
  logs and reach no meter.
- **Two blind spots in the audit path closed**: `ledger.audit.dead_lettered` counts records parked on
  `ledger.events.DLT` — the mechanism written to prevent "a silent, permanent hole in the compliance
  trail" had nobody counting what it caught — and Kafka consumer lag, which needed no code once a
  `MeterRegistry` existed.
- **One Collector behind a Compose `profiles: [observability]` key**, forwarding to hosted Grafana
  Cloud and tail-sampling: the application records 100% because only the Collector can see how a trace
  *ended*, so every error and every request over 150 ms is kept and the rest sampled at 5%. A plain
  `docker compose up` still starts exactly four containers. **Nothing in CI runs it and CI holds no
  Grafana credential** — the gate is a Collector receiving OTLP, which a debug exporter satisfies.
- **Structured JSON logs in `full`**, via Boot's built-in structured logging rather than a
  `logstash-logback-encoder` dependency. `standalone` keeps a human-readable console; both carry the
  trace id, the span id and the FAPI interaction id on every line.
- **Fixed:** the FAPI interaction id and the OTel trace id were both claiming the MDC key `traceId`, so
  every 401 and 403 problem body carried a 32-hex trace id where the caller's own UUID belonged. Caught
  by `SecurityConfigIT` on CI. The MDC key is now `interactionId`; the published field name is
  unchanged and §6.5 records it as a misnomer rather than breaking a contract for tidiness.
- **Fixed:** part 1 declared `service.namespace` and `service.instance.id` as observation key-values,
  which makes them span *and meter* tags rather than OTel resource attributes. `service.instance.id` is
  a per-process UUID, so as a meter tag it minted one permanent time series per restart and per
  replica. No test would have caught it.
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
- **A Docker runbook and a security-material page** (`docs/docker.md`, `docs/security-material.md`) —
  the `full` profile end to end as verified commands, and one place that says where every credential,
  key and certificate lives and where each is injected.
- **A `ledger-cli` runbook** (`docs/ledger-cli.md`) and real per-task prerequisites in the README:
  the toolchain split is JDK-only for the Java gate, JDK + Docker for `-Pit`, uv for the CLI, and all
  three for e2e. `run-e2e.sh` gained a `uv` guard on its **first** line, so a missing install costs
  the error and not a ~90 s image build first.
- **TLS at the edge** (spec §6.4a, v3.44). Traefik terminates HTTPS for the application **and for
  Keycloak** — one ingress, one certificate story, no second scheme in the stack. The certificate is
  generated on demand by `scripts/tls/gen-dev-ca.sh` into a gitignored directory, and **CI holds no
  certificate secret**: it runs the same generator in-run, so a fork's build goes green holding
  nothing. Three findings the work produced about itself, each caught by a control rather than by
  review: Traefik served its own `CN=TRAEFIK DEFAULT CERT` while every request succeeded end to end,
  because certificate selection is by SNI and RFC 6066 forbids an IP literal there; a `traefik.yml`
  static config was written and deleted, because Traefik does not expand `${VARS}` in its own file
  and — measured — ignores CLI flags entirely when `--configFile` is given; and the plaintext
  redirect pointed at a port nothing published.
- **The `X-Forwarded-For` trust boundary, which is the part of the TLS work that is a security
  control.** A proxy in front makes every request arrive from the proxy's address, and §6.1 row 4
  meters on it — so `forward-headers-strategy=native` (Tomcat's `RemoteIpValve`, which has a
  trusted-proxy concept) with `internal-proxies` naming the ingress and nothing else. **Boot's
  default is exploitable on this stack and that is measured, not argued**: it covers `172.16.0.0/12`,
  the range Docker hands to Compose networks. `ForwardedHeaderSpoofingTest` and
  `ForwardedHeaderTrustedProxyTest` are differential — the identical pair of requests scores opposite
  outcomes either side of one property — and `ProxyAddressPinTest` guards the pinned address, after
  a span-replacing edit deleted the Compose block holding it and nothing noticed.
- **`E2E_MODE=jar` is now RUN, as CI stage 9b.** It had been kept to avoid silent coverage loss for
  `java -jar` and, as wired, *was* that loss — a branch nobody executed, described in comments as
  exercised. It only became conflict-free once the app container stopped publishing 8080.
- **OWASP ZAP baseline as stage 11c**, deferred to the TLS commit on purpose so its first report is
  about this application rather than about the defaults the same work was configuring.
  `fail_action: true`, because the action defaults it to `false` — a scan that reports findings and
  exits 0 is the defect the deleted stage 6 had. First report: `FAIL-NEW: 0, WARN-NEW: 1, PASS: 66`.
- **`docs/urls-and-tls.md` and `docs/pitfalls.md`** — which URLs exist and where the encryption
  stops, and the runtime failures that cost hours, grouped by the symptom you actually see.
- **Trivy over the Compose images, CI stage 11d** (#28). The last uncovered scanning surface:
  Dependabot's `docker` ecosystem matches only `/dockerfile|containerfile/i` and this repository has
  no Dockerfile by design, so no configuration could ever have reached postgres, redis, kafka,
  keycloak, traefik or the Collector. Reports rather than gates — first run found 269 fixable
  CRITICAL/HIGH — but it **does** fail if it parses fewer than six image refs, because a parse that
  matched nothing would print a clean-looking empty table.
- **ZAP API scan over the OpenAPI contract, CI stage 11e** (#30). `zap-api-scan.py -S` enumerates the
  nine operations from the contract instead of crawling for links, lifting the baseline's structural
  ceiling: 14 URLs and 119 passive rules against the baseline's 3 and 66, on the same stack in the
  same run. `-S` is load-bearing — the script active-scans by default, and a baseline and never an
  active scan is this repository's rule. Two differential gates: the token is proven live before ZAP
  is handed it (`200` with the bearer, `401` without), and the scan must beat the baseline's URL count.
- **`PropertiesAreAsciiTest`** (#32). The four `application*.properties` files must contain no byte
  above `0x7F`. `java.util.Properties#load(InputStream)` is specified as ISO-8859-1 and editors
  disagree, so UTF-8 punctuation rendered as `â€"` for some readers and correctly for others.

### Removed
- **CI stage 6 and the vendored ISO-compliance skill, deleted rather than repaired.** The script
  resolved `REPO_ROOT` inside its own directory, so all five of its checks scanned a tree holding
  none of their 17 artefacts and the gate reported `governance OK: 17 known, 0 new` unconditionally.
  A green check that verifies nothing is worse than no check, and generating the 17 artefacts to turn
  it green would have added exactly the noise the pass existed to remove. §12.1's stage 6 is struck
  in place rather than the table renumbered, because six citations across three files name absolute
  stage numbers and no gate would catch them drifting.
- `docs/how-to/` and `docs/tutorial/`, both empty and both routed to. The eleven delivered plans
  (8,838 lines of agent execution script, five times the spec) removed from the tree; the commit history is the record.
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
- **Keycloak stopped publishing `8081` entirely**, which made the TLS change a *rename* rather than a
  toggle: `iss` moved to `https://auth.localhost/realms/tiny-ledger` in eight places at once. A
  published plaintext Keycloak mints tokens whose issuer is derived from whatever the caller typed,
  which is a different issuer from the one the application trusts. Traefik publishes **443**, not
  8443, because the published port lands inside `iss` and 443 is the one that drops out of the URL.
- **The application publishes no host port at all** — neither `8080` nor `9090`. A published 8080
  would leave a plaintext route straight past the terminator, and publishing 9090 had falsified
  §6.6's own claim that the management endpoints "rely on the port not being published".
- **The Sonar job became a gate that can actually fail** (`8eb84db`). It had reported success on
  2026-08-07 while the project's quality gate was ERROR and the README badge read
  `quality gate failed`. `-Dsonar.qualitygate.wait=true` makes the scanner poll for the verdict and
  exit non-zero on ERROR.
- **A repository-wide documentation accuracy pass** (spec v3.45). Ten documents were cross-checked
  against the code, the Compose stack and the workflow that actually runs; every correction is a
  document having described a system different from the one that shipped, and nothing in the
  application changed. Spec §9.6 called stage 9 unbuilt sixty lines from the §12.1 row describing it
  as running; §11's convention table specified eight properties the CLI does not have; §1.5 named a
  PostgreSQL and a Testcontainers version nothing runs; §1 still had Keycloak on 8081; the README
  listed observability as "not yet built" below the section explaining how to turn it on; and
  `agentic-workflow.md` told an auditor the Compose file has no Keycloak service. **No gate was
  added** — nothing in CI checks documentation here (§8.4), so the mechanism that let these
  accumulate is unchanged.
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
- **HSTS was being sent while five documents said it was not** (#32). The claim was asserted against
  the terminator, where the header genuinely was never configured; the *application* sent it, because
  `SecurityConfig` had no `headers` configuration and `server.forward-headers-strategy=native` makes
  proxied requests secure — the only condition Spring Security's default `HstsHeaderWriter` needs. A
  browser opening `https://localhost` therefore received a one-year, port-independent pin on the bare
  host. `SecurityConfig#hstsOff()` on all three chains, gated by
  `SecurityConfigTest#hstsIsNotSentOnASecureRequest`; `.secure(true)` is what makes that test able to
  fail. ZAP had reported it every run — rule 10035 `PASS` means the header is *present* — and
  `.zap/rules.tsv` dispositioned that rule `IGNORE` on the premise it was absent.
- **The application image tag no longer carries a version** (#28). It was spelled in four files and
  only `pom.xml` derived it from `${project.version}`, so a version bump left Compose starting a
  **stale** image while the e2e guard confirmed the image it named was present. The build now produces
  `tiny-ledger:local`, and the `gate` job asserts all four sites spell the identical tag.
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
