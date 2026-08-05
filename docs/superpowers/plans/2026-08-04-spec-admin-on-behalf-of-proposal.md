# Spec revision proposal — admin on-behalf-of (`ledger:admin`)

**Status:** APPROVED by the product owner 2026-08-04 — pending application at Plan 3 planning
(one spec revision pass together with the §7.2 Open Banking alignment table). Open questions 1 and 2
both closed as proposed (feed silent; no trail actor-filter yet). **[repaired] Target spec version:**
the version is assigned by the plan that applies this proposal, not here — 3.9 was spent on the Plan 3
close-out truth alignment.
**Renumbered 2026-08-05:** this proposal originally targeted 3.8, but the Plan 2 close-out consumed that
number for the CR14 truth-alignment pass (Kafka routing is programmatic; the in-process legs are
synchronous `@EventListener` in both run modes). Nothing about the content changed — only the number.
**Applies with:** Plan 3 — Keycloak + RBAC, §14 implementation order step 8. Nothing here is
buildable before auth exists, and nothing in it changes the `standalone` contract.
**Author:** spec-revision pass, 2026-08-04.

**Repaired 2026-08-05 after a four-lens council.** Approved 2026-08-04 and found, on review against
the code, to contain four defects that block implementation. The repairs are inline and marked
**[repaired]**. The proposal is still **not applied to the spec** — its content lands in `docs/spec.md`
in the same commit as the code that implements it, because a spec has no test gate and prose
describing unbuilt behaviour has nothing holding it accountable.

---

## 1. Motivation

Change operations on an account are the owner's, and the owner's alone — until they are not. Fraud
response, a court order, a customer who cannot act for themselves: in each case an operator must
move money on an account they do not own, and refusing that is not a security posture, it is an
outage of the business. What the current spec has no answer for is the second half of that
sentence. §6.4's ownership check refuses the admin along with the attacker, and if the check were
simply relaxed the log would record the movement without recording who made it — the account's
`owner` is on the stream, the acting principal is not. An investigation needs both: **who acted**
and **whose account it was**. This revision adds one role and one field, and nothing else.

---

## 2. Design summary

**D1 — `ledger:admin` widens the ownership term, never the role term.** **[repaired]** At the two
§6.4 sites that compare a caller against an account's owner — the in-service check against the
rehydrated aggregate, and the decorator wrapping the read-model ports — the authorisation rule
becomes *operation role* **AND** (*subject is owner* **OR** *caller holds `ledger:admin`*). The
other two sites are untouched: the collection endpoint carries its scope in the port signature and
D8 forbids widening it, and the auditor routes turn on role alone with no subject to compare, which
D2 keeps admin out of. The admin role grants no operation on its own: an admin who is to record
movements holds `ledger:writer` as well. *Rejected: a superuser role that short-circuits the
ownership comparison.* One
`if (admin) return;` at the top is smaller code and deletes two boundaries at once — it cannot
express "may move money on any account but may not read the compliance trail", and every positive
scenario stays green while it does so. The conjunctive form keeps the audit trail out of reach
through the ordinary `ledger:auditor` role check, with no exception clause to write.

**D2 — admin is not an auditor.** **[repaired]** Reading the audit trail and the raw event stream
stays `ledger:auditor`-only. What separation of duties withholds is **attribution, not the
record**: widening `ledger:reader` for admin already hands over every account's transactions and
balances — that *is* the record — so the trail's exclusive value once admin exists is the `actor`
field an ordinary reader never sees. The principal who may move money on any account must not also
be the principal positioned to confirm who moved it, or the trail stops proving anything about that
principal in particular. *The alternative — admin implies auditor, for operational convenience —
was rejected:* an investigator with an admin's write scope is a conflict of interest the ledger
would then be unable to detect. `dave` reviews; `trent` acts.

**[repaired]** **OPEN — requires a product decision before implementation.** Should `ledger:admin`
widen the ownership term for *reads* at all, or only for change operations? Widening reads lets an
operator browse every customer's balances and transactions; the stated motivation (fraud response,
court order) is about *acting*. Narrowing admin to change operations only is a tighter posture and
departs from D1's uniform widening. Not decided.

**D3 — the acting principal is recorded on the event.** **[repaired]** `LedgerEvent` gains an
`actor()` accessor beside `accountId`, `version` and `occurredAt`; the three movement events carry
it as a record component, and `AccountOpened` derives it from `owner` (an account has no owner to
act for until it exists). The use case stamps the caller principal it already receives (§2.4) onto
every event it emits. The pair `(actor, owner)` on one immutable row *is* the record of the
delegation. *Rejected: the `metadata` JSONB envelope column,* where `traceparent` lives —
attribution of a money movement is a domain fact, not transport context, and burying it in
envelope metadata puts it outside the aggregate's vocabulary and outside the audit projection's
mapper. *Rejected: a separate `MovementPerformedOnBehalf` event* — a second event per movement to
say one word about the first one. *No `reason` field either:* §2.4's free-text `reference` already
travels to the feed item and carries the case number.

**[repaired]** **The event payload is the record; the audit trail is a projection of it.**
Whichever transport carries `actor` to the `audit` module, a trail entry whose `actor` disagrees
with the payload is a fault, and E8's rebuild must surface it. That constraint holds either way and
is what makes the transport choice safe to defer to the implementing plan.

**[repaired]** A movement is recorded as an event only the first time it succeeds:
`RecordMovementService` returns the replay result before any event is emitted, so *the log records
who **first** performed a movement, not everyone who subsequently requested it.* Without this,
§3.2's claim that "who acted survives in the log" is false for replays.

**D4 — the audit entry surfaces it; the transaction feed does not.** `audit_entries` gains a
nullable `actor` column and the `AuditEntry` schema gains an optional `actor` property. The
customer-facing transaction resource is unchanged — the trail is where attribution is read, and
because the field would be optional, adding it to the feed later is not a breaking change (open
question 1). **[repaired]** How `actor` crosses from the event stream into this column — a Kafka
header, or a parse of the event payload — is not decided here; the constraint stated under D3 is
what makes deferring that choice safe.

**D5 — no delegation protocol.** Same endpoints, the admin's own JWT, on-behalf-of implicit in
targeting another owner's account. *Rejected for this POC: OAuth 2.0 token exchange (RFC 8693),*
in which a back-office console mints a scoped, audited, time-boxed on-behalf-of token — the right
production answer, recorded as the upgrade path in §13 and built nowhere here. *Rejected: a
separate `/api/v1/admin/...` endpoint tree* — a second copy of every write path, with a second
copy of every invariant behind it, to express one clause of one predicate. *Rejected, again:
`@PreAuthorize` on controllers* — council-closed (§6.4, §9.2), and the ownership half of the rule
needs the event stream, which the web layer does not have.

**D6 — `trent` is the test user.** The classic cast's trusted arbitrator: authorised, and still
not above the record. He holds `ledger:writer`, `ledger:reader`, `ledger:admin`, and owns no
account of his own — which also proves the admin path needs none.

**D7 — pre-cutover events are read, not rewritten.** **[repaired]** Events are immutable and there is
no backfill. Absence reads as `actor = owner` only for events whose `occurredAt` precedes the
cutover instant recorded when this lands. After it, absence is a defect and the trail reports
`unknown`, never the owner.

**D8 — `ledger:admin` never widens `GET /api/v1/accounts`.** **[repaired]** N12 holds for `trent`.
The collection endpoint encodes authorisation in the port signature (`accountsOwnedBy(String
owner)`), so widening it is a port-signature change, not a clause — and a widened collection
enumerates every customer's account, voiding §6.5's "`accountUid`s are unguessable" premise, which
is the stated justification for answering wrong-owner access with 403 rather than 404.

---

## 3. Exact spec edits

### 3.1 §2.3 Domain events

**Current (last three table rows, and the line after the table):**

> | `MoneyDeposited` | Funds credited. |
> | `MoneyWithdrawn` | Funds debited after invariant checks pass. |
> | `MovementRejected` | A command failed a business invariant. Recorded, not thrown away — rejections are audit-relevant. |
>
> Events are the write model's source of truth. Nothing else is.

**Replace with:**

> | `MoneyDeposited` | Funds credited. Carries the **`actor`**. |
> | `MoneyWithdrawn` | Funds debited after invariant checks pass. Carries the **`actor`**. |
> | `MovementRejected` | A command failed a business invariant. Recorded, not thrown away — rejections are audit-relevant. Carries the **`actor`**. |
>
> Every event answers **`actor()`** — the principal that issued the command — declared on the
> sealed `LedgerEvent` interface beside `accountId`, `version` and `occurredAt`, so the audit
> projection maps one accessor instead of switching on type. On the three movement events it is a
> record component; on `AccountOpened` it is derived from `owner`, because an account has no owner
> to act on behalf of until it exists (§15.8). For an owner-initiated movement `actor` equals the
> stream's `owner`; when an admin acts on the owner's behalf (§6.4) the pair `(actor, owner)` is
> the whole record of the delegation — one immutable row answering both *who acted* and *whose
> account it was*.
>
> Events are the write model's source of truth. Nothing else is.

### 3.2 §2.4 Commands

**Current (first sentences):**

> Every command carries the **caller principal** (the JWT subject; a fixed local principal in
> `standalone`) — authorisation is a use-case concern (§6.4), and a use case cannot check what it
> never receives.

**Replace with:**

> Every command carries the **caller principal** (the JWT subject; a fixed local principal in
> `standalone`) — authorisation is a use-case concern (§6.4), and a use case cannot check what it
> never receives. The principal is not only checked: the use case stamps it onto every event it
> emits as the `actor` (§2.3), so *who acted* survives in the log rather than only in a request
> that is already gone.

### 3.3 §4.1 Write path — steps 2 and 4

**Current:**

> 2. Aggregate rehydrated by replaying its event stream; **ownership checked against the caller
>    principal before anything else is answered** — a foreign caller gets the §6.5 refusal, never an
>    idempotency oracle.
> …
> 4. Command applied; the aggregate emits events or rejects.

**Replace with:**

> 2. Aggregate rehydrated by replaying its event stream; **ownership checked against the caller
>    principal before anything else is answered** — or, for a caller holding `ledger:admin`, the
>    widened check of §6.4. A caller who satisfies neither gets the §6.5 refusal, never an
>    idempotency oracle.
> …
> 4. Command applied; the aggregate emits events or rejects — each emitted event stamped with the
>    caller principal as its `actor` (§2.3).

Ordering is untouched: authorise, then idempotency, then apply.

### 3.4 §6.4 Security — role table

**Add one row, after `ledger:auditor`:**

> | `ledger:admin` | Widen `ledger:reader` / `ledger:writer` to **any** account, acting on behalf of its owner. Grants no operation on its own, and no access to the audit trail |

### 3.5 §6.4 Security — the authorisation paragraph

**[superseded]** This edit assumed a single authorisation decorator wrapping every use case
("inside that wrapper"). v3.9's §6.4 replaced the paragraph below with a principle — *"Every
authorisation decision is made by the component that holds the state the decision needs"* — and a
four-row table of enforcement sites, explicitly closed against a fifth (§6.4). The admin clause has
to be reasoned about against every site in that table, not confined to one wrapper — and D1/D8 (§2)
already establish that it widens ownership at only two of the four, leaving the collection endpoint
and the auditor routes untouched. So the exact replacement text below must not be applied as
written; it is retained only as a record of what was originally proposed. The implementing plan
needs to write this edit against the real four-site table.

**Current (second sentence):**

> Ownership is checked against the JWT subject, inside that wrapper, before the use case runs (§4.1).

**Replace with:**

> Ownership is checked against the JWT subject, inside that wrapper, before the use case runs
> (§4.1): the operation's role check **and** then *subject is the owner* **or** *the caller holds
> `ledger:admin`*. The two terms are conjunctive by construction — `ledger:admin` widens the
> ownership term only, never the role term — so an admin without `ledger:writer` still cannot move
> money, and an admin without `ledger:auditor` still cannot read the trail (N13). An
> `if (admin) return;` short-circuit at the top of the decorator would satisfy every positive
> scenario in §9.3 and quietly delete both boundaries; the conjunction is what makes an
> administrator something other than a superuser.

### 3.6 §6.4 Security — test-users table

**Add one row, after `mallory`:**

> | `trent` | `ledger:writer`, `ledger:reader`, `ledger:admin` | — | **On-behalf-of.** Moves money on an account he does not own; the movement records `actor=trent` on `alice`'s stream while the owner stays `alice`. **403 on the audit trail** — acting and reviewing are different jobs |

**And add one paragraph, immediately after the existing `mallory` paragraph:**

> `trent` is the cryptographic literature's trusted arbitrator, and the name is the point:
> authorised, and still not above the record. He earns his place from the opposite side to
> `mallory` — `mallory` proves the ownership comparison exists, `trent` proves the exception to it
> is exactly one clause wide. A suite whose `trent` can also read the audit trail has tested a
> superuser and called it an administrator.

### 3.7 §6.4 Security — the ownership mechanism

**[repaired]** The original edit here rewrote the document's *correct* phrase — "the use case
compares the two" — into "the decorator compares the two," which contradicts v3.9's §6.4: three of
the four enforcement sites are not a decorator at all (`RecordMovementService` and
`StrongBalanceService` compare in-service; the collection endpoint compares nothing, by D8; and the
auditor routes are decided by the filter chain in `config`). The original phrase is restored below;
only the admin clause is added to it.

**Current:**

> **The ownership mechanism, end to end:** `AccountOpened` records the `owner` (§2.3), so ownership
> is a fact of the event stream, not sidecar state; every command and query carries the caller
> principal (§2.4); the use case compares the two. `mallory`'s N7 is a test of that comparison, not
> of a role.

**Replace with:**

> **The ownership mechanism, end to end:** `AccountOpened` records the `owner` (§2.3), so ownership
> is a fact of the event stream, not sidecar state; every command and query carries the caller
> principal (§2.4); the use case compares the two, and where they differ admits the caller only if
> they hold `ledger:admin`. Every event the command then emits records that caller as its `actor`
> (§2.3), so an admin-performed movement carries both halves of the answer an investigation needs —
> *who acted* and *whose account it was* — on the same immutable row, and the audit trail surfaces
> the pair (§7). `mallory`'s N7 is a test of the comparison, not of a role; `trent`'s P9 and N13–N18
> are tests that the admin clause widened that comparison and nothing else.

### 3.8 §6.5 Error handling — the 403 row

**Current:**

> | Forbidden — wrong role *or* wrong owner | 403 | `/errors/forbidden` |

**Replace with:**

> | Forbidden — wrong role, or wrong owner without `ledger:admin` | 403 | `/errors/forbidden` |

### 3.9 §7 API

**Add one paragraph, immediately before "The balance resource returns the money object plus the
staleness markers…":**

> An audit entry carries one field the transaction does not: the **`actor`**, the principal that
> issued the command, which for an on-behalf-of movement is not the account's `owner` (§6.4). The
> raw event stream exposes it inherently — it is a field of the event. The customer-facing
> transaction resource is deliberately silent on it: the compliance trail is where attribution is
> read, and `actor` is an optional field, so surfacing it on the feed later is an addition, not a
> break.

### 3.10 §9.3 Scenario catalogue

**Current (tagging paragraph):**

> The auth scenarios (N6–N10), the shared-limiter N9, Kafka's E6, auditor P7, restart-persistence E7
> and real-Postgres N2 are `@full` by necessity — a mode with no auth cannot assert a `403`, and a
> mode that loses state on restart cannot assert recovery.

**Replace with: [repaired — N13 split into N13/N14, N15–N18 added (see below)]**

> The auth scenarios (N6–N10, N13–N18), the shared-limiter N9, Kafka's E6, auditor P7, on-behalf-of
> P9, restart-persistence E7 and real-Postgres N2 are `@full` by necessity — a mode with no auth
> cannot assert a `403` or an admin, and a mode that loses state on restart cannot assert recovery.

**Current (positive table heading):**

> **Positive — `deposits.feature`, `withdrawals.feature`, `history.feature`**

**Replace with:**

> **Positive — `deposits.feature`, `withdrawals.feature`, `history.feature`, `authorisation.feature`**

**Add one positive row, after P8: [repaired]**

> | P9 | `trent` (admin) deposits 100.00 into `alice`'s account, addressed by its `accountUid` — not the `ACC-001` name, then reads its balance | `201`; balance 100.00; `MoneyDeposited` on the stream carrying `actor=trent` while the stream's `owner` stays `alice`; the audit entry for that version reports the same `actor`; the balance read (also by `accountUid`) returns `200` — an admin's scope covers reads as well as writes, and the movement is attributable to the person, not merely to "an admin" |

**[repaired] Why P9 addresses the account by `accountUid`, not by name.** As originally written, P9
could not execute: `trent` owns no account (D6), so `GET /api/v1/accounts` returns an empty list for
him, and §11's CLI resolves `--account ACC-001` to an `accountUid` **through that list**, while §9.6
runs the whole catalogue through the CLI's client. Neither obvious fix works — giving `trent` an
account lets him resolve *his own*, not `alice`'s, and widening `GET /accounts` for admin is
forbidden (D8). **P9 references the deterministic `accountUid` directly, not the account name.**
§6.4 already provides for `ACC-001`…`ACC-900` being pinned to deterministic UUIDs via the realm file
and seed script "so scenarios can reference them" — not built yet at v3.9, but the intended
mechanism. No realm change, no CLI change, and N12 continues to hold for `trent`.

**Add negative rows, after N12: [repaired — N13 split into N13/N14 across both auditor routes; four further negatives added]**

> | N13 | `trent` (admin) requests `GET /api/v1/audit/entries` | `403`. `ledger:admin` widens ownership, not roles: the trail belongs to `ledger:auditor`, and the principal who may move money on any account is not the one who reviews it |
> | N14 | `trent` (admin) requests `GET /api/v1/accounts/{accountUid}/events` | `403`, same reason as N13. `SecurityConfig` denies both auditor routes with a single matcher; a fix that split the routes and covered only `/audit/**` would pass N13 while an admin still reads the raw event stream on the other route |
> | N15 | `trent` **without** `ledger:writer` attempts a cross-account deposit | 403. The actual conjunction test — P9 cannot fail against a short-circuit that also grants roles |
> | N16 | `trent` requests `GET /api/v1/accounts` | Only accounts he owns. Proves D8 |
> | N17 | `mallory` (writer, no admin) attempts a cross-account write | 403. Proves the widening is gated on the role rather than always-on |
> | N18 | An event written after the cutover with no `actor` | Reported as `unknown`, never as the owner |

**[repaired] N18 cannot be driven through the HTTP API**, which §9.3 requires of catalogue scenarios
— no endpoint writes an event without stamping `actor`. It needs a non-BDD home (a repository-level
test), and the implementing plan must place it before adopting this catalogue.

**[repaired] §5's ID-set gap.** §5's `Requirement IDs` sentence hardcodes the catalogue's membership
as `(P0…P8, N1…N12, E1…E9)`; P9 and N13–N18 fall outside that literal set the moment they exist. The
original edit list omitted the fix — added as §3.13 below.

**Why N13/N14 and not a cross-account write refusal.** The obvious candidate — `mallory` deposits into
`ACC-001` and is refused — mirrors N7 onto the write path, and N7 already fails the moment the
ownership comparison stops discriminating. What no existing scenario can fail is the shape this
change actually invites: an admin clause implemented as a blanket bypass. That implementation
passes every role check, every ownership check and every positive scenario in the catalogue, and is
visible only where an admin reaches something an admin should not have. N13–N18 are those scenarios.

### 3.11 §13 Non-goals

**Add one bullet:**

> - Delegation and impersonation protocols. An admin acts under their own identity and their own
>   token (§15.8); OAuth 2.0 Token Exchange (RFC 8693) — a console minting a scoped, time-boxed
>   on-behalf-of token — is the recorded production upgrade path, and is not built here.

### 3.12 §15 Documented assumptions

**Add, as items 8 and 9:**

> 8. On-behalf-of is **implicit**: an admin acts under their own identity and their own JWT, against
>    the same endpoints, and the operation is "on behalf of" the owner purely because it targets that
>    owner's account. There is no impersonation header, no delegation token and no token exchange
>    (§13). Account *opening* has no on-behalf-of form — an account has no owner until it exists.
> 9. **[repaired]** Events are immutable and there is no backfill. An event or audit entry with no
>    `actor` reads as `actor = owner` only if it predates the cutover instant recorded when this
>    lands. After that instant, an event or audit entry with no `actor` is a defect, and the trail
>    reports `unknown`, never the owner.

### 3.13 §5 Spec-driven design — requirement ID membership

**[repaired — added; omitted from the original edit list, see §3.10's flag above.]**

**Current:**

> **Requirement IDs:** the scenario IDs *are* the requirement IDs — `REQ-<scenario-id>` for every
> catalogue row (P0…P8, N1…N12, E1…E9), and the `REQ-NNN` tags §8.2 harvests from tests use exactly
> these. Membership is the catalogue itself, never a range that can drift.

**Replace with:**

> **Requirement IDs:** the scenario IDs *are* the requirement IDs — `REQ-<scenario-id>` for every
> catalogue row (P0…P9, N1…N18, E1…E9), and the `REQ-NNN` tags §8.2 harvests from tests use exactly
> these. Membership is the catalogue itself, never a range that can drift.

### 3.14 Revision history

**Add one row: [repaired — version/date cells released (see Status block above); ownership-widening clause corrected to match D1/D8]**

> | TBD | TBD | Admin on-behalf-of, landing with step 8: `ledger:admin` widens the ownership term at the two §6.4 sites that compare a caller against an account's owner, for reads and change operations, without widening the role term and without widening the account collection (D8); every event records the acting principal as `actor` (§2.3/§2.4/§4.1) and the audit entry surfaces it (§7); admin is not an auditor — separation of duties kept; test user `trent`, scenarios P9/N13–N18, error row (§6.5), assumptions 8–9, delegation protocols declared a non-goal (§13) |
>
> Both cells are placeholders: the version and date belong to the plan that applies this proposal to
> `docs/spec.md`, not to this proposal. Pinning a number here would only reserve one a later plan may
> also spend — the same defect class as the rest of this repair, a document asserting something about
> another document that stopped being true.

---

## 4. Impact inventory

| Surface | Change |
|---|---|
| `docs/api/openapi.yaml` — `AuditEntry` **[repaired]** | One **optional** property `actor` (`type: string`), *not* added to `required`. Description: the issuing principal; equal to `owner` for owner-initiated movements, different for on-behalf-of; absent on pre-cutover entries, where it reads as the owner (§15.9); after the cutover, absence is a defect and the trail reports `unknown`. Additive and backward-compatible for generated clients |
| `docs/api/openapi.yaml` — everything else | Unchanged. No new path, no new parameter, no new response, no change to `Transaction`, `Balance` or any error response |
| Event store schema | **No DDL.** `actor` lives inside the existing `payload` JSONB of `events`; the table already has `metadata` and needs neither. Zero migration on the system of record |
| `audit_entries` | New changeset `004-add-audit-actor.sql`: `ALTER TABLE audit_entries ADD COLUMN actor VARCHAR(255);` — **nullable by design** (§15.9), no backfill, no index. An `actor` filter on the trail is one parameter and one index the day an investigation needs it (open question 2) |
| Existing events **[repaired]** | Read, never rewritten. A payload without `actor` deserialises to absent; absence is read as `actor = owner` only where `occurredAt` precedes the cutover instant, and as `unknown` after it. The audit trail rebuild (E8) applies the same cutover comparison, and a post-cutover event with no `actor` is a fault E8 must surface |
| `docker/keycloak/realm-tiny-ledger.json` | New realm role `ledger:admin`; new user `trent` with `ledger:writer`, `ledger:reader`, `ledger:admin`, no seeded account, password `dev-only` like the rest |
| Authorisation (§4.5 composition root) **[repaired]** | Not one clause in one predicate — there is no shared predicate and no command decorator. The admin clause lands at **three** comparison sites: `AuthorizedUseCases.requireOwner` (the read decorator wrapping `QueryBalanceUseCase`/`QueryHistoryUseCase`), `RecordMovementService`, and `StrongBalanceService`, each compared independently. The collection endpoint (`GET /api/v1/accounts`) is excluded by D8. `requireOwner` is `private static` in `config`, and the cycle rule (`HexagonalRulesTest`) forbids `ledger.application` importing `config`, so a genuinely shared predicate would have to be a new type in `shared`. The role check and §4.1's ordering are untouched |
| Use cases | The command use cases already receive the caller principal; they now pass it to the aggregate, which stamps it on each emitted event |
| Cucumber (§9.3) **[repaired]** | `authorisation.feature` gains P9 and N13–N18, all `@full` |
| pytest-bdd (§9.6) **[repaired]** | Re-runs all of them against the composed stack; no new step vocabulary beyond an actor assertion on the audit trail |
| Integration (§9.4) | The step-8 security IT gains the admin cases: admin-with-writer succeeds cross-account, admin-without-writer is refused, admin is refused on the auditor endpoints |
| Unit **[repaired]** | Predicate tests for the widened ownership rule; a use-case test asserting emitted events carry the caller as `actor`; **an event-deserialisation test on a legacy payload with no `actor`**; and, since N18 cannot run as BDD, the repository-level post-cutover-absence test that is now the only executable statement of §15.9's post-cutover half |
| ArchUnit / Modulith | Unchanged. No new module, no new dependency, no framework annotation anywhere new |
| `standalone` | Unchanged. It has a fixed local principal and no roles; `actor` is recorded there too and always equals the owner |

---

## 5. Open questions for the council

1. **Feed transparency.** Should the customer-facing transaction resource expose `actor` when it
   differs from `owner` — telling the account holder that an operator touched their account — or
   stay silent, treating that disclosure as a policy decision above the ledger? This proposal says
   silent; the field is optional, so either answer is non-breaking, and the choice can be deferred
   without a schema decision.
2. **Trail queryability.** Should `GET /api/v1/audit/entries` gain an `actor` filter, so an
   investigation can ask *"everything trent did"* rather than *"everything on this account"*? This
   proposal says no for the POC — per-entry visibility satisfies the stated requirement, and the
   filter is one query parameter plus one index when a real investigation asks for it.
