# Open Banking (OBIE) facade — compliance and architecture assessment

**Project:** tiny-ledger · **Date:** 2026-08-04 · **Status:** assessment only, nothing implemented
**Scope:** what it would cost to expose the existing event-sourced ledger through an OBIE-conformant
read API, and which ISO standards are genuinely in play.

---

## 1. Executive summary

"Open Banking compliance" is three separable things, and conflating them is how this kind of project
goes wrong:

1. **A wire contract** — resource paths, the `Data`/`Links`/`Meta` envelope, OBIE data-dictionary
   types, error shape, `x-fapi-*` headers. This is a *mapping* problem. The ledger already has every
   fact these payloads need.
2. **A consent resource** — `account-access-consents` is a stateful, event-shaped aggregate with a
   lifecycle, an expiry, a permissions array and a transaction window. This is *new capability*, not
   a mapping, and it is the bulk of the work.
3. **A security profile** — FAPI 1.0 Advanced Final, plus OBIE's own detached-JWS message signing
   anchored in the Open Banking Directory PKI. Configuration and infrastructure, and partly
   unobtainable outside a real regulated participant.

**Headline cost.** Bucket 1 is roughly a fortnight of focused work for one engineer: a second
OpenAPI contract, generated interfaces, a controller set and a mapper package, hanging off the
*existing* use-case ports with the domain untouched. Bucket 2 roughly doubles that and cannot start
before Postgres exists. Bucket 3 is unbounded at the top end and must be scoped by explicit
non-goals, because a chunk of it (Directory membership, eIDAS certificates, conformance
certification) is not purchasable with engineering time.

**Recommendation.** Target the **UK OBIE / Open Banking Limited Read-Write Standard, v4.0.1, AISP
subset only** (`account-access-consents`, `accounts`, `accounts/{id}/balances`,
`accounts/{id}/transactions`). Build it as a **second inbound hexagonal adapter behind its own
OpenAPI contract**, as **Plan 5**, after Plan 4. Make exactly one thing move earlier: the read-port
signature decision already parked from Plan 1 Task 11 — decide it in Plan 2/3 *with the consent
use-case in mind*, so it is changed once rather than twice.

**Biggest single cost driver:** the consent module and the authorisation seam it lands on — not the
payload mapping.

---

## 2. Standard selection

**Recommendation: UK OBIE (Open Banking Limited) Read-Write Standard v4.0.1, AISP subset.**

*Why UK OBIE over Berlin Group NextGenPSD2 / openFinance.* This is a UK-fintech take-home; Teya's UK
entity operates under the FCA / CMA Open Banking regime, where OBIE is the standard an ASPSP is
measured against. Berlin Group's openFinance API Framework (the successor programme to NextGenPSD2
XS2A) is the pan-European counterpart and is genuinely the larger installed base, but it answers a
question nobody is asking about this repository, and its documents ship under CC BY-ND — you may
redistribute but not publish a modified derivative, which is awkward for a repo whose whole thesis is
a hand-written contract under version control. OBIE's specs and OpenAPI files are on GitHub under the
Open Licence. One standard, chosen deliberately, cited in the spec, beats two half-done.

*Why v4.0.1 over 3.1.x.* v4.0 is the first major release of the UK standard since 2018 and is the
line OBL is actively maintaining; CMA9 banks were required to implement it by end of Q1 2025 and
FAPI 1.0 Advanced Final by 31 December 2024. v4.x is also the line aligned to ISO 20022 — codes such
as `OBBalanceType1Code` are now ISO `ExternalBalanceType1Code` four-letter values (`CLAV`, `ITAV`,
`ITBD`, …) rather than 3.1.x's long-form `InterimAvailable`. Choosing 3.1.x would mean writing a
mapping to a vocabulary the standard has already moved away from. 4.0.1 is the current patch on that
line (`info.version: 4.0.1` in the published account-info OpenAPI document).

*Honest caveat.* tiny-ledger is not an ASPSP and has no compliance obligation whatsoever. Nothing
here is "required". The value of doing it is demonstrating that the hexagonal claim in §4 survives a
second, hostile-shaped inbound adapter — which is a stronger architectural proof than any number of
additional endpoints on the v1 surface.

---

## 3. Delta table — v1 contract ↔ OBIE requirement

Legend: **F** = pure-facade mapping · **C** = new stateful capability · **S** = security
infrastructure.

| Concern | tiny-ledger v1 today | OBIE v4.0.1 requirement | Kind | Notes |
|---|---|---|---|---|
| Base path | `/api/v1/...` | `/open-banking/v4.0/aisp/...` (server URL in the published contract) | F | Second contract, second controller package. v1 keeps its own path. |
| Resources | `accounts`, `.../balance`, `.../transactions`, `.../deposits`, `.../withdrawals`, `.../events`, `audit/entries` | `account-access-consents`, `accounts`, `accounts/{AccountId}`, `accounts/{AccountId}/balances`, `accounts/{AccountId}/transactions` (25 AISP paths total; the rest are legitimately out of scope for a ledger) | F + C | Only the consent resource is new capability. Note `/balances` is **plural** and returns an array. |
| Envelope | Wrapped list key: `{"transactions":[…],"links":{…}}` | `{"Data":{…},"Links":{…},"Meta":{…}}`; PascalCase throughout; `Risk` object required on consent request *and* response (`OBRisk2`, an empty object for AIS) | F | Mechanical. `Links.Self` is required on every response. |
| Money | `{"currency":"GBP","minorUnits":10000}`, `int64` | `{"Amount":"100.00","Currency":"GBP"}` — `OBActiveCurrencyAndAmount_SimpleType` is a **string**, pattern `^\d{1,13}$\|^\d{1,13}\.\d{1,5}$`, example `1209.06`; `Currency` is ISO 4217 alpha-3 | F | The one mapping with real correctness risk. Convert with `BigDecimal.movePointLeft(iso4217Exponent)` — never a `double`. Pattern permits up to 5 dp; our minor units are exponent-driven, so output scale must come from `Currency.getDefaultFractionDigits()`, not a hard-coded 2. |
| Sign | `direction: IN\|OUT` with unsigned amount (§7.1, adopted from Starling) | `CreditDebitIndicator: Credit\|Debit` with unsigned amount | F | Same idea, different vocabulary. Two-value enum map. This is the delta row that validates the original §7.1 decision. |
| Balance | One object: `{amount, asOf, streamVersion}` | `Data.Balance[]`, each `{AccountId, CreditDebitIndicator, Type, DateTime, Amount}` required; `Type` from ISO `ExternalBalanceType1Code` (`CLAV CLBD FWAV INFO ITAV ITBD OPAV OPBD PRCD XPCD`) | F | Emit a single-element array. `ITAV`/`ITBD` (interim available/booked) are the honest codes for a ledger where appends settle atomically; `streamVersion` and `asOf` have no OBIE home and are dropped at the facade — an argument for keeping v1. |
| Transactions | `{transactionUid, type, direction, amount, balanceAfter, status, transactionTime, settlementTime, reference}` | `OBTransaction*` — `TransactionId`, `CreditDebitIndicator`, `Status` (`Booked`/`Pending`), `BookingDateTime`, `ValueDateTime`, `Amount`, `TransactionInformation`; `Balance` sub-object optional | F | Every field has a source. `SETTLED` → `Booked`. `MovementRejected` events must be excluded from the feed (they are not transactions). |
| Idempotency | `PUT` to a client-generated movement UID; no header (§6.3, §7.1) | `x-idempotency-key` header on **POST** only, ≤40 chars, 24-hour replay window, same key + same body ⇒ `201` with current resource status; different body ⇒ `400` + `U029` (from 4.0.1 the ASPSP *may* answer `422` with a remediation `Url`) | C | Applies only to the consent POST — the AISP surface is read-only otherwise. Needs a *stored* key→resource map with a 24-hour TTL, which the v1 design deliberately avoided. Genuinely new state. |
| Pagination | Opaque keyset cursor, `links.next` only | `Links.Self` (required) + `Next`/`Prev`/`First`/`Last`; `Meta.TotalPages` optional; page size 25–1000 recommended. **The standard explicitly does not specify how pagination parameters are passed** | F | Our cursor is compliant as-is. `Meta.TotalPages` is uncomputable over a keyset cursor without a count query — it is optional, so omit it and say why. `Links.Prev`/`First`/`Last` need either a bidirectional cursor or honest omission. |
| Errors | RFC 7807 `ProblemDetail`, `type` = `/errors/...` (§6.5) | `OBErrorResponse1`: `{Id, Code (deprecated), Message (deprecated), Errors[1..n]{ErrorCode, Message ≤500, Path, Url}}`; `ErrorCode` from `OBExternalStatusReason1Code`, maintained in a **separate** code-set repository | F | Needs a hand-written catalogue mapping each §6.5 row to an OBIE code. Not all §6.5 conditions have a clean OBIE code (`/errors/version-conflict`, `/errors/not-available-in-standalone` have no obvious counterpart) — those become `U000`-class internal codes with an honest `Message`. |
| Headers | none | Request: `Authorization` mandatory; `x-fapi-auth-date`, `x-fapi-customer-ip-address`, `x-fapi-interaction-id` optional (RFC 4122 UUID). Response: `x-fapi-interaction-id` **mandatory** on success *and* error, played back when supplied; `Retry-After` on 429; `payload-version` optional | F (+S at the edges) | Cheap: one servlet filter, generate-or-echo, and wire it into the existing trace context (§6.6) so `x-fapi-interaction-id` and `traceId` correlate. |
| Identifiers | `<entity>Uid` UUIDs, `format: uuid` | `AccountId`/`ConsentId`/`TransactionId` are `Max128Text` strings, opaque, no format constraint | F | Our UUIDs satisfy it. No change needed, only a type widening at the facade. |
| Timestamps | `Instant`, `Z`-suffixed, server-assigned (§15.5) | `ISODateTime`, ISO 8601, **timezone mandatory** in responses; OBIE examples use `+00:00` | F | `Z` is valid ISO 8601 / RFC 3339 and satisfies "must include the timezone". No change; record the reading. |
| Consent | none — ownership is derived from `AccountOpened.owner` vs JWT subject (§6.4) | `account-access-consents` resource: `POST`/`GET`/`DELETE`; `Permissions[1..n]` (20-value code set, must include `ReadAccountsBasic` or `ReadAccountsDetail`), optional `ExpirationDateTime`, `TransactionFromDateTime`, `TransactionToDateTime`; status `AWAU → AUTH \| RJCT`, then `CANC` (revoked) or `EXPD` (expired) | **C** | The main event. See §4. |
| Permission enforcement | role + owner (§6.4) | Per-endpoint permission gating: `ReadBalances` → `/balances`; `ReadTransactionsBasic\|Detail` **plus** at least one of `ReadTransactionsCredits`/`ReadTransactionsDebits` → `/transactions`; Basic vs Detail changes which *fields* are returned | **C** | Field-level response shaping driven by consent state. Lands on the read ports. |
| Auth | Keycloak bearer JWT planned (Plan 3); `standalone` runs a fixed local principal | Two token types: TPP **client-credentials** token (scope `accounts`) for the consent POST, PSU **authorization-code** token (scope `accounts`) for every resource GET; FAPI 1.0 Advanced Final mandatory since 31 Dec 2024 | **S** | See §5. |
| Message signing | Skipped, explicitly (§7.1, §13) | `x-jws-signature` detached JWS with OB-specific JOSE claims (`http://openbanking.org.uk/iat`, `/iss`, `/tan`), keys resolved from an OB Trust Anchor | **S** | Recommend it stays a documented non-goal. The Trust Anchor is not obtainable. |

---

## 4. Architecture — the facade as an inbound adapter

### 4.1 What it is

A second **inbound adapter**, nothing more:

```
docs/api/openbanking-openapi.yaml     # second hand-written contract (§5's rule, applied twice)
  └─ generated OBIE DTOs + API interfaces
       └─ adapter/in/openbanking/     # controllers + hand-written static mappers (§4.6 rule 3)
            └─ existing use-case ports: QueryAccountsUseCase, QueryBalanceUseCase, QueryHistoryUseCase
```

This is exactly the shape §4.6 mandates: the OBIE DTOs are wire shapes owned by the adapter that
needs them, and every conversion is an explicit mapper in that adapter's package. Nothing about it
requires a new port on the read side — the facts (`accountUid`, `owner`, `Money`, transaction feed,
balance) are all already reachable.

**Untouched:** the `ledger` domain, the aggregate, the event store, the projector, the balance/
account projections, the outbox, the v1 controllers, the Cucumber suite, the CLI. If any of those
need changes, something has gone wrong — that is the review question for the eventual plan.

**Also untouched:** §4.5's composition root shape. The facade's controllers and mappers are wired in
one new `@Configuration` class alongside the existing ones, profile-gated the same way the auditor
pair is.

### 4.2 The consent module

A new Spring Modulith module, `consent`, event-sourced in the same style as `ledger` — which is the
point: it is the second aggregate that proves the event-sourcing machinery is not single-purpose.

*Aggregate:* `AccountAccessConsent`, identified by `ConsentId`, holding the permissions set, the
optional expiry and transaction window, the granted account set, and the status.
*Events:* `ConsentRequested` (permissions, window, TPP identity), `ConsentAuthorised` (PSU subject +
the account set the PSU selected), `ConsentRejected`, `ConsentRevoked`, `ConsentExpired` — the last
emitted by a scheduled sweep rather than inferred at read time, so the state transition is a fact in
the stream rather than a calculation, and the `StatusUpdateDateTime` OBIE requires has a real source.
*Inbound ports:* `RequestConsentUseCase`, `AuthoriseConsentUseCase`, `RejectConsentUseCase`,
`RevokeConsentUseCase`, `QueryConsentUseCase`.
*Outbound ports:* reuses `EventStorePort`, `ClockPort`, `IdGeneratorPort` from `platform`; adds
`IdempotencyKeyStorePort` for the 24-hour `x-idempotency-key` window.
*Published to the facade:* one read port, `ConsentAuthorityPort`, answering "for this access token,
what is the authorised consent — which permissions, which accounts, which transaction window?" That
is the only thing the OBIE adapter needs from the module, and keeping it to one port is what stops
the facade from becoming a second application layer.

The `ledger` module does not depend on `consent`, and `consent` does not depend on `ledger`. The
facade depends on both. Modulith verification enforces that for free.

### 4.3 The one impedance mismatch that is not free

**Consent-scoped authorisation has to reach the read ports, and today they cannot receive it.**

The project already knows about this seam. From Plan 1, Task 11, parked with a reviewer ruling:

> balance read ports (QueryBalance/QueryHistory) take no caller — no ownership enforcement point;
> getAccount scopes to owner and thus answers 404 where §6.5 mandates 403 for wrong owner.
> Unreachable in standalone (single principal, auth disabled), not fixable in the adapter —
> port-signature question for Plan 2/3.

The consent model lands on precisely that seam, and makes it strictly harder. Ownership is a boolean
over one principal; a consent is a *narrower and structured* caller identity — a permission set, an
account subset, and a time window — and it must be enforced *inside* the use case, because §6.4's
whole argument is that authorisation wraps the use case rather than the controller. An authorisation
decorator cannot decorate a signature that carries no caller.

The practical consequence for sequencing: **do not decide the read-port signature twice.** When
Plan 2/3 resolves the parked finding, resolve it as "read ports take an authorisation context", not
"read ports take a caller principal" — a context object that a plain JWT subject and a consent can
both populate. Deciding it narrowly in Plan 3 and then widening it in Plan 5 is two breaking changes
to the same signature, two rounds of test churn, and an ArchUnit-visible seam that changes shape
twice for no reason.

---

## 5. Security lift — FAPI versus Plan 3's Keycloak

**What OBL mandates.** FAPI 1.0 Advanced Final; migration from the earlier Implementer's Draft 2 was
required by 31 December 2024. FAPI 2.0 Security Profile reached Final on 22 February 2025 and
requires sender-constrained access tokens via mTLS (RFC 8705) *or* DPoP (RFC 9449), with resource
servers obliged to verify them. **I found no published OBL commitment to a FAPI 2.0 migration date**
— treat FAPI 2.0 as trajectory, not requirement, and say so in the spec rather than guessing.

**What Keycloak can provide.** More than one might assume. Keycloak has been certified for FAPI 1.0
Advanced, FAPI-CIBA, Australia CDR and Open Banking Brazil since 2022, and ships client profiles for
those; recent versions add FAPI 2.0 Security Profile and FAPI 2.0 Message Signing client profiles,
including DPoP variants, which pass the OIDF conformance suite. Concretely, Plan 3's Keycloak can
give us, by configuration: the `fapi-1-advanced-final` client policy, PAR, request-object signing
(JAR), PKCE, mTLS client authentication and certificate-bound tokens, JARM, and short-lived
sender-constrained tokens. That is a real and defensible chunk of FAPI.

**What Keycloak cannot provide.** Everything that is PKI and ecosystem rather than protocol:
membership of the Open Banking Directory; eIDAS QWAC/QSEAL certificates; validation of Software
Statement Assertions issued by the Directory; the OB Trust Anchor that `x-jws-signature` verification
resolves keys against; and the detached-JWS verification itself, which is application code on the
resource-server side, not an AS feature. There is also a live Keycloak limitation worth recording:
FAPI 2.0 conformance passes with mTLS client authentication but currently fails with
`private_key_jwt` (upstream issue). If a plan claims FAPI 2.0, that constrains the client
authentication method.

**A POC-honest subset.** Aim for *FAPI-baseline-shaped, with documented gaps*, in the same register
§7.1 already uses for Starling's request signing ("Skip — out of scope"):

*In:* Keycloak with the FAPI 1.0 Advanced client profile enabled in the compose stack; PAR + PKCE +
signed request objects; authorization-code flow for the PSU token, client-credentials for the TPP
token, both scoped `accounts`; consent-scoped authorisation enforced at the use case; `x-fapi-*`
headers required, validated and echoed; rate limiting and 429 + `Retry-After` reusing §6.1.

*Documented gaps (a table in the spec, not prose):* no OB Directory membership or SSA validation; no
eIDAS certificates — dev PKI only; mTLS and certificate-bound tokens configured between the gateway
and Keycloak in the compose stack but not against real TPP certificates; no `x-jws-signature`
verification; no OIDF or OBL conformance-suite run, therefore **no conformance claim of any kind**.
The last one matters most: an uncertified "OBIE-compliant" claim in a take-home is a liability, and
"OBIE-shaped, gaps enumerated" is a stronger signal than an overreach.

---

## 6. Effort and sequencing

| Work item | Size | Blocked on |
|---|---|---|
| Second OpenAPI contract (AISP subset, 5 paths) + generation wiring | M | — |
| `Data`/`Links`/`Meta`/`Risk` envelope + PascalCase DTO mapping | S | contract |
| Money `int64` ↔ ISO 4217-scaled decimal string | S | — (correctness hot-spot; needs its own property test) |
| `direction` → `CreditDebitIndicator`; balance `Type` code mapping | S | — |
| `OBErrorResponse1` + §6.5 → `OBExternalStatusReason1Code` catalogue | M | external code set |
| `x-fapi-*` filter, interaction-id echo + trace correlation | S | §6.6 (Plan 3) |
| `Links`/`Meta` over the existing keyset cursor | S–M | — (`Meta.TotalPages` omitted, documented) |
| **Consent module** — aggregate, 5 events, store, expiry sweep, `ConsentAuthorityPort` | **L** | **Plan 2 (Postgres)** |
| `x-idempotency-key` 24-hour store + replay semantics | M | **Plan 2 (Postgres)** |
| Read-port authorisation-context signature + decorator | M | Plan 2/3 — *decide once, there* |
| Keycloak: two token types, scopes, PSU authorisation journey, consent binding | L | Plan 3 |
| FAPI 1.0 Advanced hardening (PAR, JAR, mTLS, cert-bound tokens, dev PKI) | L | Plan 3 |
| `x-jws-signature` verification | L | **blocked — recommend non-goal** |
| Conformance-suite run / certification | L | **blocked — recommend non-goal** |

**Recommended slot: Plan 5**, after Plan 4, as its own plan with its own council review.

*What must not start before Plan 2's persistence exists.* Both stateful pieces — consent state and
the idempotency-key window — are **write-once, read-across-restart** resources. §15 assumption 6 says
`standalone` loses all state on restart, deliberately. A consent that evaporates on restart is not a
consent; building it against `InMemoryEventStore` would either produce a fake or quietly force an
exception to the two-run-modes rule that §4.5 and §9.2b exist to protect. Plan 2 also brings the
publication registry the consent module's events need in order to reach `audit` the same way ledger
events do.

*What must not start before Plan 3.* Everything with a token in it. The consent lifecycle is defined
in terms of PSU authentication and SCA; without Keycloak there is no PSU to authorise anything, and
the facade would be a shape with no semantics.

*What should move earlier.* Only the read-port signature decision (§4.3) — it is already parked for
Plan 2/3 and should simply be resolved with the wider requirement in view.

---

## 7. Process recommendation

The repository has an established path for a change of this size, and this should follow it rather
than arriving as a plan out of nowhere:

1. **Spec amendment first.** A new **§7.2 "Open Banking facade (OBIE AISP subset)"** recording the
   standard and version chosen and why (§2 above), the delta table (§3), and the consent module
   sketch; a **§13 non-goals** addition for `x-jws-signature`, OB Directory membership and
   conformance certification; **§10** gains rows for ISO 20022, ISO 8601 and ISO 3166 (§8 below);
   **§15** gains the assumptions the facade makes; **§14** gains the Plan 5 row. Plus the mandatory
   **Revision history** row and **Traceability** entries — §8.4's ISO 15289 section contract makes
   those a build-gating requirement, not a courtesy.
2. **Council review** of the amended spec through the `agent-council` repository, the same way the
   spec was reviewed on 2026-08-03 (`.superpowers/sdd/council-spec-review-2026-08-03.md`).
3. **Then** a dedicated implementation plan (Plan 5) via `writing-plans`, executed under the SDD
   protocol with per-task review gates.

**Why the facade must not bend the v1 API.** §7.1 is a table of recorded decisions, each with a
citation — that is its value as a document. Bending v1 toward OBIE would retract four of those
decisions at once: `{currency, minorUnits}` would become a decimal string and stop being "the domain
`Money` serialised without translation"; RFC 7807 would give way to `OBErrorResponse1` and lose the
machine-readable-code argument that justified diverging from Starling in the first place; the
`PUT`-plus-client-UID idempotency that *deleted a port* would gain the header machinery it was chosen
to avoid; and the whole CLI, Cucumber and e2e surface would churn for a standard the ledger is not
obliged to meet. The two surfaces should coexist as separate inbound adapters over one core — which
is the literal claim §4 makes about hexagonal architecture. A second adapter that speaks a foreign
dialect *without the core noticing* is the strongest available evidence for that claim; a single
hybrid contract would be evidence against it.

---

## 8. ISO standards actually in play

**In play:**

- **ISO 20022** — the data dictionary the OBIE v4.x line aligns to. OBIE states the payloads are
  designed using ISO 20022 message elements and components where available, *flattened* for
  developer ergonomics. Concretely, this facade would consume `ExternalBalanceType1Code`
  (`CLAV`/`ITAV`/`ITBD`/…) and `ExternalStatusReason1Code`, both republished by OBIE as external code
  sets. We consume codes; we do not implement ISO 20022 messaging.
- **ISO 4217** — currency codes. Already used (`^[A-Z]{3}$` in `openapi.yaml`). The facade adds a
  second, *load-bearing* dependency on it: the currency's minor-unit exponent determines the decimal
  scale of the `Amount` string.
- **ISO 8601** — all date-times, timezone mandatory in OBIE responses. The repo's `Z`-suffixed
  `Instant`s already satisfy this; worth recording as a verified reading rather than an assumption.
- **ISO 3166** — country codes, only if `ReadParty`/`ReadAccountsDetail` address data is
  implemented. Recommend it is not: staying on `ReadAccountsBasic` avoids it entirely.
- **ISO 9362 (BIC) / ISO 13616 (IBAN)** — only reached if account *identification* is returned
  (`Account.SchemeName` = `UK.OBIE.IBAN` or `UK.OBIE.SortCodeAccountNumber`). The ledger has no such
  identifiers and inventing them would be fabrication. Another reason to stop at `ReadAccountsBasic`.

**Explicitly not in play:**

- **ISO 8583** — card authorisation messaging. There is no card acquiring, issuing or scheme
  interaction anywhere in this system. Naming it in a compliance section would be padding.
- **ISO 20022 payment initiation messages** (`pain.001` and relatives) — the PISP half of OBIE is out
  of scope; this is an AISP-shaped facade over a ledger.

**Adjacent, not the same thing.** The repo vendors an `iso-compliance` skill and §10 already claims
ISO/IEC 25010 and ISO/IEC 27001:2022 Annex A, with §8.4 built on ISO/IEC 15289 for document sections.
Those govern *documentation and product quality*, and the governance test is real. *(Superseded
2026-08-06: the skill, the governance test and CI stage 6 are deleted and §10 is now an
out-of-scope statement — `spec.md` §8.4. The paragraph stands as written at the time.)* They are not
evidence about the standards conformance of an API, and the spec amendment should keep the two claims
in separate sections so nobody reads a green governance test as an OBIE conformance statement.

**Where I am uncertain, stated rather than papered over:**

- The OBIE code sets (`OB_Internal_CodeSet`, `ISO_External_CodeSet`) live in a separate GitHub
  repository and are versioned independently of the API specs; the error-code mapping table will need
  pinning to a specific commit or it will rot silently.
- ISO's own standards documents (4217, 8601, 3166, 20022 message repository) are paywalled. Everything
  above is derived from OBIE's freely published specs and the freely published external code lists,
  not from the ISO texts. Any claim needing the ISO text itself has not been verified.
- The consent lifecycle names in common circulation (`AwaitingAuthorisation`, `Authorised`,
  `Rejected`, `Revoked`) are **3.1.x-era**. v4.0 uses ISO-style four-letter codes:
  `AWAU`, `AUTH`, `RJCT`, `CANC`, `EXPD`. Documentation written against the old names will mislead.
- Pagination is explicitly left to the ASPSP ("this standard does not specify how the pagination
  parameters are passed"), so our cursor is compliant — but I could not find guidance on whether
  omitting `Links.Prev`/`First`/`Last` is acceptable for a keyset-paginated implementation. Treat it
  as an open question for the plan, not a settled one.
- Whether OBL will require FAPI 2.0, and by when, is not something I could establish from published
  sources.

---

## 9. Sources

All accessed **2026-08-04**.

| # | Source | URL |
|---|---|---|
| 1 | OBL Read-Write API Profile v4.0 (headers, idempotency, pagination, error structure, ISO 20022 design principle, message signing) | https://openbankinguk.github.io/read-write-api-site3/v4.0/profiles/read-write-data-api-profile.html |
| 2 | OBL Account and Transaction API Profile v4.0 (permission↔endpoint matrix, consent elements) | https://openbankinguk.github.io/read-write-api-site3/v4.0/profiles/account-and-transaction-api-profile.html |
| 3 | OBL Account Access Consents v4.0 (status code list AWAU/AUTH/RJCT/CANC/EXPD, permissions, data dictionary) | https://openbankinguk.github.io/read-write-api-site3/v4.0/resources-and-data-models/aisp/account-access-consents.html |
| 4 | OBL Read-Write API specifications knowledge base (idempotency 24h + U029, balance-type semantics, error-message guidance) | https://openbankinguk.github.io/knowledge-base-pub/standards/read-write.html |
| 5 | OBL published account-info OpenAPI document, `info.version: 4.0.1`, server `/open-banking/v4.0/aisp` — source of the `OBActiveCurrencyAndAmount_SimpleType` pattern, `OBReadBalance1`, `Links`, `Meta`, `OBErrorResponse1`, `OBReadConsent1`, `OBRisk2`, security schemes | https://github.com/OpenBankingUK/read-write-api-specs (`dist/openapi/account-info-openapi.json`) |
| 6 | OBIE external/internal code sets (`OBExternalStatusReason1Code`, `ExternalBalanceType1Code`) | https://github.com/OpenBankingUK/External_Internal_CodeSets |
| 7 | OBL — publication of Open Banking Standard v4.0 | https://www.openbanking.org.uk/news/obl-publishes-open-banking-standard-v4-0-to-assure-future-ecosystem-growth/ |
| 8 | OBL — publication of Open Banking Standard v4.0.1 | https://www.openbanking.org.uk/news/obl-publishes-open-banking-standard-v4-0-1/ |
| 9 | Ozone API — guide to OBL v4.0 (CMA9 Q1 2025 deadline, FAPI 1.0 Advanced Final by 31 Dec 2024, CHAPS 1 May 2025) — *secondary source, corroborated by 7/8* | https://ozoneapi.com/blog/the-essential-guide-to-obl-version-4-0/ |
| 10 | OBL Security Profiles hub | https://standards.openbanking.org.uk/security-profiles/ |
| 11 | OpenID Foundation — FAPI 2.0 Security Profile, Final, 22 February 2025 (sender-constrained tokens, RS requirements) | https://openid.net/specs/fapi-security-profile-2_0-final.html |
| 12 | Keycloak — FAPI and Open Banking Brazil certification announcement (2022) | https://www.keycloak.org/2022/01/fapi |
| 13 | Keycloak 26.4.0 release notes — FAPI 2.0 Final client profiles, DPoP profiles, `private_key_jwt` conformance limitation | https://www.keycloak.org/2025/09/keycloak-2640-released |
| 14 | Keycloak FAPI SIG — conformance status and supported profiles | https://github.com/keycloak/kc-sig-fapi |
| 15 | Berlin Group — openFinance API Framework downloads, licence terms (CC BY-ND), change management (page last updated 18 May 2026) | https://www.berlin-group.org/openfinance-downloads |
| 16 | Berlin Group — Open Finance programme overview / NextGenPSD2 status | https://www.berlin-group.org/open-finance |
| 17 | ISO 20022 standards repository — `BalanceType10Code` (referenced by OBIE for balance-type definitions) | https://www.iso20022.org/standardsrepository/type/BalanceType10Code |

**Local ground truth read for this assessment (read-only):** `docs/spec.md` §4.5, §4.6, §6.4, §6.5,
§7, §7.1, §10, §14, §15; `docs/api/openapi.yaml`; the parked Task 11 ruling in
`.superpowers/sdd/2026-08-03-standalone-core/progress.md`.
