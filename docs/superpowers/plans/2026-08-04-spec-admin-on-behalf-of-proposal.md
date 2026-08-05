# Spec revision proposal — admin on-behalf-of (`ledger:admin`)

**Status:** APPROVED by the product owner 2026-08-04 — pending application at Plan 3 planning
(one spec revision pass together with the §7.2 Open Banking alignment table). Open questions 1 and 2
both closed as proposed (feed silent; no trail actor-filter yet). **Target spec version:** 3.9 (current: 3.8).
**Renumbered 2026-08-05:** this proposal originally targeted 3.8, but the Plan 2 close-out consumed that
number for the CR14 truth-alignment pass (Kafka routing is programmatic; the in-process legs are
synchronous `@EventListener` in both run modes). Nothing about the content changed — only the number.
**Applies with:** Plan 3 — Keycloak + RBAC, §14 implementation order step 8. Nothing here is
buildable before auth exists, and nothing in it changes the `standalone` contract.
**Author:** spec-revision pass, 2026-08-04.

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

**D1 — `ledger:admin` widens the ownership term, never the role term.** The decorator's rule
becomes *operation role* **AND** (*subject is owner* **OR** *caller holds `ledger:admin`*). The
admin role grants no operation on its own: an admin who is to record movements holds
`ledger:writer` as well. *Rejected: a superuser role that short-circuits the decorator.* One
`if (admin) return;` at the top is smaller code and deletes two boundaries at once — it cannot
express "may move money on any account but may not read the compliance trail", and every positive
scenario stays green while it does so. The conjunctive form keeps the audit trail out of reach
through the ordinary `ledger:auditor` role check, with no exception clause to write.

**D2 — admin is not an auditor.** Reading the audit trail and the raw event stream stays
`ledger:auditor`-only. Separation of duties is the entire point of the trail: the principal with
the power to move money on any account must not also be the principal who reviews the record of
those movements, or the record protects nobody. *The alternative — admin implies auditor, for
operational convenience — was rejected:* an investigator with an admin's write scope is a
conflict of interest the ledger would then be unable to detect. `dave` reviews; `trent` acts.

**D3 — the acting principal is recorded on the event.** `LedgerEvent` gains an `actor()` accessor
beside `accountId`, `version` and `occurredAt`; the three movement events carry it as a record
component, and `AccountOpened` derives it from `owner` (an account has no owner to act for until
it exists). The use case stamps the caller principal it already receives (§2.4) onto every event
it emits. The pair `(actor, owner)` on one immutable row *is* the record of the delegation.
*Rejected: the `metadata` JSONB envelope column,* where `traceparent` lives — attribution of a
money movement is a domain fact, not transport context, and burying it in envelope metadata puts
it outside the aggregate's vocabulary and outside the audit projection's mapper. *Rejected: a
separate `MovementPerformedOnBehalf` event* — a second event per movement to say one word about
the first one. *No `reason` field either:* §2.4's free-text `reference` already travels to the
feed item and carries the case number.

**D4 — the audit entry surfaces it; the transaction feed does not.** `audit_entries` gains a
nullable `actor` column and the `AuditEntry` schema gains an optional `actor` property. The
customer-facing transaction resource is unchanged — the trail is where attribution is read, and
because the field would be optional, adding it to the feed later is not a breaking change (open
question 1).

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

**D7 — pre-v3.9 events are read, not rewritten.** Events are immutable and there is no backfill.
An event whose payload has no `actor` was written when the only principal permitted to write to a
stream was its owner, so absence reads as `actor = owner` — an exact inference, not a default.

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

**Current:**

> **The ownership mechanism, end to end:** `AccountOpened` records the `owner` (§2.3), so ownership
> is a fact of the event stream, not sidecar state; every command and query carries the caller
> principal (§2.4); the use case compares the two. `mallory`'s N7 is a test of that comparison, not
> of a role.

**Replace with:**

> **The ownership mechanism, end to end:** `AccountOpened` records the `owner` (§2.3), so ownership
> is a fact of the event stream, not sidecar state; every command and query carries the caller
> principal (§2.4); the decorator compares the two, and where they differ admits the caller only if
> they hold `ledger:admin`. Every event the command then emits records that caller as its `actor`
> (§2.3), so an admin-performed movement carries both halves of the answer an investigation needs —
> *who acted* and *whose account it was* — on the same immutable row, and the audit trail surfaces
> the pair (§7). `mallory`'s N7 is a test of the comparison, not of a role; `trent`'s P9 and N13 are
> tests that the admin clause widened that comparison and nothing else.

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

**Replace with:**

> The auth scenarios (N6–N10, N13), the shared-limiter N9, Kafka's E6, auditor P7, on-behalf-of P9,
> restart-persistence E7 and real-Postgres N2 are `@full` by necessity — a mode with no auth cannot
> assert a `403` or an admin, and a mode that loses state on restart cannot assert recovery.

**Current (positive table heading):**

> **Positive — `deposits.feature`, `withdrawals.feature`, `history.feature`**

**Replace with:**

> **Positive — `deposits.feature`, `withdrawals.feature`, `history.feature`, `authorisation.feature`**

**Add one positive row, after P8:**

> | P9 | `trent` (admin) deposits 100.00 into `alice`'s `ACC-001`, then reads its balance | `201`; balance 100.00; `MoneyDeposited` on `ACC-001`'s stream carrying `actor=trent` while the stream's `owner` stays `alice`; the audit entry for that version reports the same `actor`; the balance read returns `200` — an admin's scope covers reads as well as writes, and the movement is attributable to the person, not merely to "an admin" |

**Add one negative row, after N12:**

> | N13 | `trent` (admin) requests `GET /api/v1/audit/entries` | `403`. `ledger:admin` widens ownership, not roles: the trail belongs to `ledger:auditor`, and the principal who may move money on any account is not the one who reviews it |

**Why N13 and not a cross-account write refusal.** The obvious candidate — `mallory` deposits into
`ACC-001` and is refused — mirrors N7 onto the write path, and N7 already fails the moment the
ownership comparison stops discriminating. What no existing scenario can fail is the shape this
change actually invites: an admin clause implemented as a blanket bypass. That implementation
passes every role check, every ownership check and every positive scenario in the catalogue, and is
visible only where an admin reaches something an admin should not have. N13 is that scenario.

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
> 9. An event or audit entry with no `actor` predates v3.9, when the only principal permitted to
>    write to a stream was its owner; absence therefore reads as `actor = owner`. Events are
>    immutable and there is no backfill — the inference is exact, not a default.

### 3.13 Revision history

**Add one row:**

> | 3.9 | 2026-08-05 | Admin on-behalf-of, landing with step 8: `ledger:admin` widens the ownership term for reads and change operations on any account without widening the role term (§6.4); every event records the acting principal as `actor` (§2.3/§2.4/§4.1) and the audit entry surfaces it (§7); admin is not an auditor — separation of duties kept; test user `trent`, scenarios P9/N13, error row (§6.5), assumptions 8–9, delegation protocols declared a non-goal (§13) |

---

## 4. Impact inventory

| Surface | Change |
|---|---|
| `docs/api/openapi.yaml` — `AuditEntry` | One **optional** property `actor` (`type: string`), *not* added to `required`. Description: the issuing principal; equal to `owner` for owner-initiated movements, different for on-behalf-of; absent on pre-v3.8 entries, where it reads as the owner (§15.9). Additive and backward-compatible for generated clients |
| `docs/api/openapi.yaml` — everything else | Unchanged. No new path, no new parameter, no new response, no change to `Transaction`, `Balance` or any error response |
| Event store schema | **No DDL.** `actor` lives inside the existing `payload` JSONB of `events`; the table already has `metadata` and needs neither. Zero migration on the system of record |
| `audit_entries` | New changeset `004-add-audit-actor.sql`: `ALTER TABLE audit_entries ADD COLUMN actor VARCHAR(255);` — **nullable by design** (§15.9), no backfill, no index. An `actor` filter on the trail is one parameter and one index the day an investigation needs it (open question 2) |
| Existing events | Read, never rewritten. A payload without `actor` deserialises to absent and is interpreted as `actor = owner`. The audit trail rebuild (E8) reproduces exactly the same reading, so a full replay after this change is stable |
| `docker/keycloak/realm-tiny-ledger.json` | New realm role `ledger:admin`; new user `trent` with `ledger:writer`, `ledger:reader`, `ledger:admin`, no seeded account, password `dev-only` like the rest |
| Authorisation decorator (§4.5 composition root) | One clause in one predicate — the shared ownership comparison both the command and query decorators route through. The role check, the decorator order and §4.1's ordering are untouched |
| Use cases | The command use cases already receive the caller principal; they now pass it to the aggregate, which stamps it on each emitted event |
| Cucumber (§9.3) | `authorisation.feature` gains P9 and N13, both `@full` |
| pytest-bdd (§9.6) | Re-runs both against the composed stack; no new step vocabulary beyond an actor assertion on the audit trail |
| Integration (§9.4) | The step-8 security IT gains the admin cases: admin-with-writer succeeds cross-account, admin-without-writer is refused, admin is refused on the auditor endpoints |
| Unit | Predicate tests for the widened ownership rule; a use-case test asserting emitted events carry the caller as `actor`; **an event-deserialisation test on a legacy payload with no `actor`**, which is the only executable statement of §15.9 |
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
