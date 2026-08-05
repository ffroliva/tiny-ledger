# Council review — security-auditor lens

**Subject:** `docs/superpowers/plans/2026-08-05-plan-3-security-and-authorization.md` (revised, 7 tasks)
**Tree:** `main` @ `0d9123e`. Read-only review; no build or test was run.
**Scope:** threat-model the plan as written. The four already-folded findings (full-chain decoder, decorator
wiring proof, `CallerPrincipal` fail-closed, in-service checks moving to the boundary) are **not** re-reported;
neither is the measured `AccessDeniedException` → opaque 500 result nor the `cnf`-on-bearer refusal.

---

## The authorization surface after all 7 tasks

Chain is `anyRequest().authenticated()` + `oauth2ResourceServer().jwt()` under `full`. Everything below
therefore requires *a* valid token; the column says what happens **after** that.

| Endpoint | Handler | Protection after Plan 3 | Verdict |
|---|---|---|---|
| `POST /api/v1/accounts` | `LedgerController.openAccount` | owner := `CallerPrincipal.current()`; no role check | OK (see nit N4) |
| `PUT /accounts/{id}/deposits/{uid}` | `LedgerController.putDeposit` | **nothing** — plan deletes the in-service check and wires no replacement | **P0-A** |
| `PUT /accounts/{id}/withdrawals/{uid}` | `LedgerController.putWithdrawal` | **nothing** — same | **P0-A** |
| `GET /accounts/{id}/balance?consistency=strong` | `LedgerController.getStrongBalance` | **nothing** — same | **P0-A** |
| `GET /accounts/{id}/balance` | `BalanceController.getBalance` | `AuthorizedUseCases.Balances` → `OwnershipException` 403 | sound |
| `GET /accounts/{id}/transactions` | `BalanceController.listTransactions` | `AuthorizedUseCases.History` → 403 | sound |
| `GET /accounts` | `BalanceController.listAccounts` | `accountsOwnedBy(caller)`, exact-match filter | sound |
| `GET /accounts/{id}` | `BalanceController.getAccount` | filters the owned list, 404 otherwise | sound |
| `GET /accounts/{id}/events` | `AuditController.getEvents` | **nothing** — no ownership, no `ledger:auditor` | **P0-B** |
| `GET /audit/entries` | `AuditController.listAuditEntries` | **nothing**, and `accountUid` is optional | **P0-B** |

Two endpoints are reachable with a valid token and no check because the plan **intends** to defer them
(P0-B); three are reachable because the plan **deletes** their existing check without adding the replacement
(P0-A). Only P0-A is a regression against today's code; P0-B is a pre-existing hole that this plan converts
from "the whole API is open, honestly" into "the whole API is closed except these two, silently".

---

## P0

### P0-A — Task 6 deletes the money-path ownership check and never adds the decorator that replaces it

**Claim.** Task 6's *prose* says to delete `if (!account.owner().equals(caller))` from
`RecordMovementService` and `StrongBalanceService` and to "add `Movements` and `StrongBalance` decorators
alongside the `Balances` and `History` ones below". **Nothing below adds them.** Every artefact of the task
covers only the two read ports:

- plan L866 (prose): delete both in-service checks — the only instruction with teeth.
- plan L870–873 **Files**: `AuthorizedUseCases.java`, `UseCaseConfig.java`, `AuthorizedUseCasesTest.java`.
  `RecordMovementService.java`, `StrongBalanceService.java`, `RecordMovementServiceTest`,
  `StrongBalanceServiceTest` are **not listed** — nor are they in the plan's own File Structure section (L45–53).
- plan L877 **Produces**: "`AuthorizedUseCases.Balances` and `AuthorizedUseCases.History`" — full stop.
- plan L937–1003 **Step 3 implementation**: `Balances` and `History` only.
- plan L1015–1037 **Step 5 wiring**: `authorizedBalance`, `authorizedHistory` only.
- plan L1052–1070 **Step 7 wiring proof**: `GET /balance` only.
- plan L1074–1077 **Step 8 commit pathspec**: `src/main/java/.../config`, `src/test/java/.../config`,
  `src/test/resources/features` — it does **not** include `ledger/application/usecase`, so the deletion the
  prose demands would not even be committed by the command the plan supplies.

**Attack.** Under `full`, after Task 6, `mallory` presents her own perfectly valid token and issues
`PUT /api/v1/accounts/{alices-account}/withdrawals/{new-uuid}` with `{"currency":"GBP","minorUnits":…}`.
`RecordMovementService` reads the stream, rehydrates, finds no ownership guard, and appends
`MoneyWithdrawn`. Alice's balance is drained. `GET .../balance?consistency=strong` on any account likewise
returns the authoritative balance to any token holder. This is exactly the `mallory` case spec §6.4 names as
"the authorisation bug that role checks alone miss" — and the plan removes the only code that catches it.

**Why nothing stops it.** `RecordMovementServiceTest` and `StrongBalanceServiceTest` currently assert the
`OwnershipException`; the prose tells the implementer those assertions will now fail and to move them into
`AuthorizedUseCasesTest`. An implementer following the checkboxes deletes two red tests and adds two green
ones about *balance reads*. Both pipelines exit 0. The Step 7 wiring proof asserts only the read path.
There is no artefact anywhere in the plan whose failure signals "the write path is unguarded".

**Secondary hazard in the fix.** Wiring the missing decorators is *not* a copy-paste of Step 5.
`FullAdapterConfig` already declares `@Primary RecordMovementUseCase transactionalRecordMovement` and
`@Primary OpenAccountUseCase transactionalOpenAccount`. Adding a second `@Primary RecordMovementUseCase` in
`UseCaseConfig` makes the `full` context fail to start with `NoUniqueBeanDefinitionException` ("more than one
'primary' bean"). Likewise `UseCaseConfig.strongBalance` returns the *interface* `QueryStrongBalanceUseCase`,
so it needs the same rename-to-concrete-type trick Step 5 applies to the balance beans. An implementer who
hits this at 2am under a green suite has every incentive to drop the decorator rather than restructure the
composition root — which lands exactly on the hole above.

**Required before execution.** Task 6 must carry, as concrete code: a `Movements` decorator wrapping
`RecordMovementUseCase` and a `StrongBalance` decorator wrapping `QueryStrongBalanceUseCase`; the wiring for
both, resolving the `@Primary` collision (the decorator belongs in `FullAdapterConfig` wrapping the
transactional bean, or `TransactionalUseCases` loses its `@Primary` — decide it in the plan, not at the
keyboard); a `mallory`-deposits/withdraws refusal test in the `FullProfile` nested class; and the §6.3
ordering assertion that ownership still precedes the idempotency lookup (a replay of a UID against an
account you do not own must 403, not 200).

**Confidence: HIGH.** This is a textual gap between the task's prose and all six of its executable
artefacts, verified line by line. Not verified by running code.

---

### P0-B — Both auditor endpoints hand every ledger's full contents to any authenticated principal, and one of them takes no `accountUid` at all

**Claim.** `AuditController.getEvents` and `listAuditEntries` (`audit/adapter/in/web/AuditController.java`)
have no ownership check and no role check today. The plan defers role checks entirely (Follow-up 3) and the
audit module's authorization seam entirely (Follow-up 4, parked finding `m2`). Task 3's chain adds
`anyRequest().authenticated()` and nothing else. So under `full`, **a valid `alice` token — or any token the
issuer will mint — gets `ledger:auditor` access to every account in the system.**

**Attack, concretely.**
1. `GET /api/v1/audit/entries` with **no `accountUid`**. `PostgresAuditTrail.trail` builds
   `SELECT account_id, event_type, stream_version, payload, occurred_at, recorded_at FROM audit_entries
   WHERE true ORDER BY occurred_at DESC, account_id DESC, stream_version DESC LIMIT ?` — the entire
   cross-account trail, cursor-paginated so it walks cleanly to the end. That yields every `accountUid` in
   the ledger.
2. For each harvested `accountUid`: `GET /api/v1/accounts/{accountUid}/events`, which returns
   `Event.payload` — the verbatim event JSON, deserialised straight through
   (`objectMapper.readValue(entry.payload(), Map.class)`). That is `owner`, `name`, `currency`, every
   `MoneyDeposited`/`MoneyWithdrawn` amount, every `reference` string, every timestamp, every stream version.

The attacker reconstructs every account's complete transaction history and balance without ever touching a
single ownership-checked endpoint. §6.4 says `ledger:auditor` "Read the audit trail across all accounts";
§7 marks both rows `ledger:auditor` only. This plan grants that authority to everyone.

**This also destroys the existence-disclosure trade-off §6.5 accepted.** §6.5 reasons that 403-not-404 is
safe "because `accountUid`s are unguessable UUIDs". Step 1 above turns unguessable UUIDs into a paginated
list. The accepted oracle stops being a theoretical trade and becomes a working enumeration primitive.

**Why the plan's own reasoning does not cover this.** `AuditController`'s javadoc (L40–42) says the
`ledger:auditor` check "arrives with Keycloak; until then this controller enforces exactly what the rest of
the API does — nothing". That reasoning was **true at `0d9123e` and becomes false the moment Task 6 lands**:
after Plan 3 the rest of the API enforces ownership and these two do not. The comment is now a load-bearing
justification for an inconsistency it no longer describes.

**Minimum acceptable mitigation inside this plan** (all three are small, pick one):
(a) require `ledger:auditor` on the two operations — `CallerPrincipal.roles()` already exists after Task 4,
and a two-line check in `AuditController` throwing `OwnershipException` yields the catalogued 403 with no new
mechanism; (b) deny both operations in the `full` chain
(`.requestMatchers("/api/v1/audit/**", "/api/v1/accounts/*/events").denyAll()`) until Follow-up 4 lands,
which is fail-closed and honest; (c) explicitly state in the plan that `full` **must not be deployed** until
Follow-up 3 and 4 land, and record it as a release gate rather than a follow-up list item.

Option (a) is not "inventing a second parallel mechanism" any more — the mechanism now exists.

**Confidence: HIGH** on the exposure and on the null-`accountUid` global query (both read directly from
source). Not verified by running code.

---

## P1

### P1-1 — The write path's ownership source moves from the event stream to a derived projection: an *authority* regression, not a timing one

**Claim (with a correction to the premise).** The brief asks whether moving the check into a decorator over
an **eventually consistent** read model is a timing regression. On this codebase it is **not** a timing
regression: `LedgerEventsListener` is a plain `@EventListener` firing on the publishing thread inside the
append transaction, in *both* run modes (its javadoc, spec §4.3 "Standalone caveat", §6.6 "the projection
does not [cross a thread], it runs on the publishing thread inside the same transaction", ADR 0001). After
`POST /accounts` commits, `accountsOwnedBy` sees the row. Read-your-writes holds. Kafka carries only the
audit leg. I will not manufacture a lag that this design does not have.

**What is a real regression** is the *authority* of the fact being compared. Spec §6.4 states the mechanism
verbatim: "`AccountOpened` records the `owner` (§2.3), so ownership is a fact of the event stream, not
sidecar state … the use case compares the two." `RecordMovementService` did exactly that — it rehydrated the
aggregate and compared against the owner reconstructed from the events. The decorator compares against a
**row in a projection table**, i.e. precisely the sidecar state §6.4 rules out. Two consequences:

1. **A projection defect becomes a money-movement authorization defect.** The `accounts` table is derived
   and rebuildable. A truncate-and-replay, a restore from a stale dump, a partial migration, a DLT replay of
   a mis-keyed record, or a manual data fix that writes the wrong `owner` now *authorises a withdrawal* that
   the event stream would have refused. The old check could not be fooled this way — the only way to change
   the answer was to change the event stream itself. There is no reconciliation job, no invariant, and no
   test asserting `accounts.owner` still agrees with `AccountOpened.owner`.
2. **Authorization and the append no longer share a snapshot.** The plan correctly puts the authorization
   decorator *outside* the transactional one (§6.3 ordering). That means `accountsOwnedBy` executes in its
   own read, commits, and only then does the movement transaction open. The in-aggregate check got
   same-transaction consistency for free. Nothing today mutates ownership, so this is not currently
   exploitable — but the guarantee is gone and nothing records that it was traded away.

**Lazy fix that satisfies both the user's "one mechanism at the boundary" choice and §6.4:** have the
*write-side* decorators authorise from `EventStorePort` — the same source the deleted check used —
rather than from `QueryAccountsUseCase`. `config` already injects `EventStorePort`
(`UseCaseConfig.strongBalance(EventStorePort, ClockPort)`), so it costs nothing architecturally and the
boundary stays the one mechanism.

**Confidence: HIGH** that the source of truth changes and contradicts §6.4's stated mechanism.
**MEDIUM** on exploitability, which requires an operational event (rebuild/restore/data-fix) rather than a
remote action.

### P1-2 — Task 4's own unit test contradicts Task 4's implementation, and the natural fix is to fail open

**Claim.** Task 4 Step 1's `CallerPrincipalTest.withNoAuthenticationTheCallerIsTheStandalonePrincipal()` is
a plain JUnit test with no Spring context, so `CallerPrincipal`'s constructor never runs and the
`private static volatile boolean standalone` field keeps its default of `false`. Step 3's implementation
then takes the `if (!standalone) throw new IllegalStateException(...)` branch. **The test the plan tells the
implementer to make pass cannot pass against the implementation the plan supplies.**

**Failure.** The implementer is now standing in front of a red test with two ways out: instantiate the bean
in the test (correct), or flip the default to `standalone = true` (one character, makes it green, and
reintroduces P1-1 from the previous council round — fail-open whenever authentication is absent, which is
the exact defect this task was revised to close).

**Compounding: the flag is JVM-global mutable static.** Surefire forks are reused across test classes. Once
Task 3 Step 7's `@ActiveProfiles("full")` context instantiates `CallerPrincipal`, `standalone` is `false`
for **every later test in that JVM**, including the 21 standalone Cucumber scenarios and Task 5 Step 4's
captor test asserting `caller == "local"`. Ordering-dependent failures that look like flakes are the worst
possible pressure to apply to a security guard. Separately, `@WebMvcTest` slices (`BalanceControllerTest`)
do not instantiate `@Component` beans outside the web layer, so the flag is whatever the JVM last wrote.

**Also, minor and self-correcting:** Step 3's import block is missing
`org.springframework.stereotype.Component` and `org.springframework.core.env.Environment` — a compile error,
harmless, but it signals the block was not compiled before being written into the plan.

**Fix.** Make profile resolution per-call and stateless (read the `Environment` from an injected instance,
or resolve `standalone` from the absence of a `JwtDecoder`/the security chain rather than a static flag),
and make the Step 1 test construct the bean explicitly so the two halves of the task agree.

**Confidence: HIGH** on the static-default contradiction (pure code reading).
**MEDIUM** on the cross-test contamination, which depends on surefire fork configuration I did not inspect.

### P1-3 — No audience validation on the production decoder, and no test can see it

**Claim.** Task 3 configures `spring.security.oauth2.resourceserver.jwt.issuer-uri` only. That produces
Spring's default validator set — issuer, expiry, not-before — and **no audience (`aud`) check**. Every test
in the plan bypasses this entirely: `TrustTheTestKey` supplies
`NimbusJwtDecoder.withPublicKey(...)`, which validates neither issuer nor audience, and `TestJwt.token`
mints no `iss` and no `aud` claim at all.

**Attack.** In any realm serving more than the ledger (the spec's own realm already carries a `ledger-cli`
service account alongside human users), a token minted for a *different* client in the same realm is
accepted by the ledger with full authority of its `sub`. Classic confused deputy. It is also the reason the
`ledger:*` role checks being deferred hurts more than it looks: with neither `aud` nor role validated, "a
token from our realm" is the entire authorization decision for three endpoints (P0-A) and two more (P0-B).

**Fix.** One bean: `JwtValidators.createDefaultWithIssuer(issuer)` plus an
`JwtClaimValidator<List<String>>("aud", …)` in a `DelegatingOAuth2TokenValidator`, and a `TestJwt` overload
that mints a wrong-`aud` token so the refusal is asserted rather than assumed.

**Confidence: MEDIUM.** The default-validator behaviour is from Spring Security's documented defaults, not
from a run on 7.1.0 in this tree; I could not execute anything to confirm the exact validator set that
`issuer-uri` installs on this version.

### P1-4 — `AuthorizedUseCases.requireOwner` loads the caller's entire account list to answer a one-row question

**Claim.** `requireOwner` calls `accounts.accountsOwnedBy(caller).stream().anyMatch(view -> …)`, which under
`full` executes `SELECT … FROM accounts WHERE owner = ? ORDER BY created_at, account_id` — unbounded, no
`LIMIT`, sorted — **on every authorised request**, including the money path once P0-A is fixed.

**Failure.** A principal holding many accounts (nothing limits account creation — see N4) turns every one of
their own requests into a growing sorted scan. That is an authenticated, self-inflicted resource-exhaustion
lever on the request path that also gates money movement, so degrading it degrades availability of writes.

**The exact primitive already exists.** `BalanceProjectionPort.account(AccountId)` →
`Optional<AccountView>` runs `SELECT … WHERE account_id = ?` and `AccountView` carries `owner()`.
An ownership check is `projection.account(id).map(AccountView::owner).filter(caller::equals)` — one row,
one index hit, and it expresses "does this caller own *this* account" instead of "is this account among
everything the caller owns". It needs a one-method port addition on `QueryAccountsUseCase` (or the decorator
taking `BalanceProjectionPort` directly, which `config` may do).

**Confidence: HIGH** on the query shape (read from `PostgresBalanceProjection.java:205-216`).
No load testing performed.

---

## Answers to the specific questions

**Q2 — is `GET /api/v1/accounts` safe under the new caller resolution, and does it leak across owners?**
Yes, safe, and no cross-owner leak. `accountsOwnedBy` is an exact-match `WHERE owner = ?`; the only owner
value ever echoed in the response body (`AccountView.owner()` → `Account.owner`) is the caller's own
subject. `getAccount` filters the same list and 404s otherwise, so it discloses nothing either. Two residual
notes, both nits (N1, N2) rather than findings: the caller is the bare JWT `sub` with no issuer binding, and
a null `sub` is not rejected.

**Q3 — existence disclosure: does a caller learn "exists but not yours" vs "does not exist"?**
Considered in isolation, the plan **improves** this. Today `getBalance` 404s when the projection has no row.
After Task 6 the decorator's `requireOwner` runs *first*, so a non-existent account and a
someone-else's account both return **403 `/errors/forbidden`** — the two become indistinguishable on that
path. The residual oracle is only the one §6.5 already accepted as a deliberate decision ("Wrong-owner access
returns 403, not 404. The account-existence oracle this admits is accepted because `accountUid`s are
unguessable UUIDs"), and 122 bits of UUIDv4 entropy makes blind enumeration infeasible regardless of the
absent rate limiter. **However**, this soundness is entirely undone by P0-B: `GET /api/v1/audit/entries`
with no `accountUid` returns every `accountUid` in the ledger, paginated. There is nothing to enumerate once
the list is handed over. So: the 403/404 pair is not exploitable *by itself*; it is irrelevant while P0-B
stands. Fix P0-B and this area is genuinely sound.

**Q4 — is the write path's new ownership source a security regression?**
Yes, but not for the reason posed. It is not a timing regression — the projection is synchronous and
in-transaction in both modes, so there is no window where a movement is authorised against a stale read
model. It is an **authority** regression: money movement is now authorised against a rebuildable derived
copy instead of the event stream that spec §6.4 names as the source of truth, and authorisation no longer
shares a transaction snapshot with the append. Full detail and the concrete fix in P1-1. Note this is
academic until P0-A is fixed — today the plan authorises the write path against *nothing at all*.

**Q5 — interim exposure if `full` ships after this plan and before the follow-ups.**
1. Any valid token reads the complete transaction history, amounts, references and owners of **every**
   account, via the two auditor endpoints (P0-B). This is the deployment-blocking one.
2. Any valid token deposits to, withdraws from, and strong-reads **any** account (P0-A).
3. `ledger:reader` is not enforced, so `carol` — whom the spec provisions specifically to prove "403 on
   write" — can move money. `ledger:writer` is not enforced. `dave`, whom the spec provisions to prove
   "403 on every write", can write.
4. No `aud` validation, so any token from the same realm minted for any client is accepted (P1-3).
5. No DPoP, so a leaked bearer token (proxy log, error report, referrer) is fully replayable for its
   lifetime. Deliberate and documented; listing it for completeness.
6. No rate limiter exists despite §6.5 cataloguing 429 `/errors/rate-limit-exceeded`, so nothing throttles
   the enumeration in (1) or the account-creation lever in N4.

---

## Nits

- **N1 — `sub` is used as the ownership key with no issuer binding.** `AccountOpened.owner` stores the raw
  JWT subject. `sub` is only unique *within* an issuer. An issuer rotation, a realm rebuild, or a second IdP
  silently transfers ownership of every account whose stored `sub` collides. Storing `iss|sub` (or a
  realm-scoped id) costs nothing now and is a data migration later. LOW-MEDIUM.
- **N2 — `CallerPrincipal.current()` can return `null`.** `jwt.getToken().getSubject()` is null for a token
  with no `sub` claim; nothing rejects it. Postgres fails closed (`WHERE owner = NULL` matches no rows →
  403 everywhere), but `POST /accounts` would then mint an account with `owner = null` that nobody can ever
  reach. A `null`/blank subject should throw the same `IllegalStateException` the profile guard throws —
  same fail-closed principle the task already adopted. MEDIUM.
- **N3 — `AuditController`'s javadoc becomes false when Task 6 lands.** Lines 40–42 justify the missing
  auditor check with "until then this controller enforces exactly what the rest of the API does — nothing".
  After Task 6 the rest of the API enforces ownership. Whatever is decided for P0-B, that paragraph must be
  rewritten in the same commit or it becomes the next reviewer's reason not to look. LOW.
- **N4 — unbounded account creation.** `POST /api/v1/accounts` has no role check and no quota; any token can
  open accounts indefinitely, each a row in the `accounts` projection that P1-4's per-request scan then
  sorts. Storage plus the P1-4 amplification. LOW.
- **N5 — the `x-fapi-interaction-id` filter echoes caller input verbatim into a response header and the
  MDC.** `FapiInteractionIdFilter` does not validate the supplied value. The MDC path reaches log output and,
  via `ErrorHandlingAdvice.traced()`, the JSON `traceId` field of problem responses. Header injection is not
  possible (the servlet container rejects CR/LF in `setHeader`), and JSON encoding neutralises the body path,
  so this is log-forging / log-poisoning only — an attacker can write arbitrary attacker-chosen text into the
  operator's log line and into a correlation field auditors trust. FAPI expects a UUID here; one
  `UUID.fromString` guard (mint a fresh one on failure) closes it and costs a line. LOW-MEDIUM.

---

## Well done

- **Throwing `OwnershipException` rather than `AccessDeniedException` is correct and now evidence-backed.**
  The experiment's five scenarios show every Spring denial variant — including `AuthorizationDeniedException`
  and `@PreAuthorize` — collapsing to an opaque 500, and `OwnershipException` alone answering 403 with the
  catalogued `type`. A refusal that looks like a server fault is the kind of defect that survives for years.
- **CSRF-off is argued, not assumed.** The javadoc names the actual threat model (ambient credentials), notes
  the system has no cookie, no session and no browser surface, and makes `SessionCreationPolicy.STATELESS`
  the tripwire that forces the question open again if anyone adds one. That is the right way to disable a
  control.
- **Security is configured per profile rather than excluded in `standalone`.** Excluding the
  autoconfiguration would leave no `HttpSecurity` at all, and `standalone` would have nothing to attach the
  FAPI filter — or any future chain concern — to. The plan takes the more expensive option for the right
  reason and says so.
- **`FailClosedGuard` and `CallerPrincipal` now assert the same principle at two scopes.** A missing profile
  flag refuses to boot; a missing principal refuses the request. Consistency between a startup guard and a
  per-request guard is rare and worth keeping.
- **§6.3's authorise-before-idempotency ordering is called out explicitly** in Task 6 (the decorator must sit
  outside the transactional one). That ordering is easy to lose silently during a decorator refactor and the
  plan names it. It now needs a test — see P0-A.
- **The Step 7 wiring proof with the "verify it fails if you remove `@Primary`" instruction** is exactly the
  right shape of evidence: a test that is required to demonstrate its own sensitivity. Extending that
  discipline to the write path is most of the P0-A fix.

---

## What I could not verify without running code

Nothing below was executed; another agent shares this working tree and the brief forbids builds.

1. **Whether the `full` `@SpringBootTest` context can start without containers** (Task 3 Step 7's own open
   question). If it cannot, every wiring proof in the plan moves to `-Pit`, and the P0-A test I am asking for
   moves with it.
2. **Which validators `issuer-uri` actually installs on Spring Security 7.1.0** (P1-3). I read the default
   behaviour, not this version's code path.
3. **Whether surefire reuses a JVM fork across the standalone and `full` test classes** (P1-2's contamination
   half). The static-default contradiction itself needs no run to see; the ordering interaction does.
4. **The actual `NoUniqueBeanDefinitionException` on the double `@Primary`** (P0-A secondary). Read from two
   `@Configuration` classes; not reproduced.
5. **Whether `@WebMvcTest` slices register `FapiInteractionIdFilter`** (Task 7 Step 5 hedges on this itself).
6. **No `management.endpoints` / actuator configuration was found** in `application*.properties`, so I could
   not assess actuator exposure. `anyRequest().authenticated()` would cover it if present, but an unfound
   config is not an absent one.
7. **Runtime confirmation of the P0-B attack chain.** Both queries were read from
   `PostgresAuditTrail.java`; the null-`accountUid` branch producing `WHERE true` is unambiguous in source,
   but I did not issue the HTTP calls.

---

## Verdict

**Do not execute Tasks 6 and 7 as written.** Tasks 1–5 are safe to execute in order. Task 6 must first gain
the `Movements` and `StrongBalance` decorators, their wiring (with the `@Primary` collision resolved), and a
`mallory`-writes refusal test — otherwise the plan ships an authenticated API in which any token holder can
move anyone's money, and both pipelines go green while it does. Independently of the tasks, `full` must not
be deployed until the two auditor endpoints are either role-checked or denied, because on that build a valid
`alice` token is a complete read of every account in the ledger.
