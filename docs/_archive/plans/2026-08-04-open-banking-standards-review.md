# Open Banking standards — compliance review and adoption posture

**Status:** review only — and the four §6 "from birth" items were APPROVED by the product owner
2026-08-04 for application in Plans 3–4 (interaction-id filter, FAPI-2 Keycloak client policy, DPoP
end to end, §7.2 alignment table — the table lands in the same spec revision pass as v3.8
admin-on-behalf-of). Nothing else in this document changes `docs/spec.md` or any code. It exists so
the council can decide, with citations rather than taste, what "compliant with the Open Banking
standards from birth" would actually cost this ledger.

**Question asked:** the product owner wants the ledger to be Open Banking-compliant from birth.
**Short answer:** full compliance is not available to a POC — it is an ecosystem membership, not a code
change — but four of its structural conventions are cheap enough to take now, and one of them (an
interaction-id header) is nearly free. Two OB conventions collide head-on with council-closed decisions
and must be decided, not drifted into.

---

## 1. Scope and sources

Reviewed 2026-08-04. Versions verified against the publishers' current pages on that date, not from
memory.

| Standard | Version reviewed | Source |
|---|---|---|
| UK Open Banking Read/Write API Standard | **v4.0.1** (current release) | <https://standards.openbanking.org.uk/api-specifications/latest/> · <https://openbankinguk.github.io/read-write-api-site3/v4.0.1/> |
| — Read/Write Data API Profile (common conventions) | v4.0.1 | <https://openbankinguk.github.io/read-write-api-site3/v4.0.1/profiles/read-write-data-api-profile.html> |
| — Account and Transaction API (AISP): Accounts, Balances, Transactions | v4.0.1 | `…/resources-and-data-models/aisp/{Accounts,Balances,Transactions}.html` |
| — Account Access Consents (consent model) | v4.0 | `…/resources-and-data-models/aisp/account-access-consents.html` |
| FAPI 1.0 Advanced — Security Profile Part 2 | **Final** (2021); mandated by OBL R/W | <https://openid.net/specs/openid-financial-api-part-2-1_0-final.html> |
| FAPI 2.0 Security Profile | **Final, approved February 2025**; Message Signing Final August 2025 | <https://openid.net/specs/fapi-security-profile-2_0-final.html> · <https://openid.net/fapi-2-security-profile-attacker-model-final-specifications-approved/> |
| Berlin Group | NextGenPSD2 XS2A IG subrelease **v1.3.16**, now carried forward as the openFinance API Framework | <https://www.berlin-group.org/openfinance-downloads> |
| ISO 20022 | Referenced only as the semantic root of the OB data dictionary (`OBActiveOrHistoricCurrencyAndAmount`, `ExternalBalanceType1Code`, `BankTransactionCode`) | OB data dictionaries above; code sets at <https://github.com/OpenBankingUK/External_internal_CodeSets> |
| Keycloak FAPI support | **26.4** — FAPI 2.0 Final client profiles, DPoP officially supported | <https://www.keycloak.org/2025/09/keycloak-2640-released> · <https://www.keycloak.org/2025/10/dpop-support-26-4> |
| Spring Security DPoP resource-server support | **6.5+** — DPoP proofs auto-validated under `oauth2ResourceServer` | <https://docs.spring.io/spring-security/reference/6.5/whats-new.html> |

Contract read on the other side: `docs/spec.md` §6.3 (idempotency), §6.4 (security), §6.5 (errors), §7
and §7.1 (API and the Starling adopt/adapt/skip table), and `docs/api/openapi.yaml`.

---

## 2. Why UK OBIE is the reference, not Berlin Group

The spec models its API on Starling Bank, a UK ASPSP whose public API is shaped by the UK ecosystem it
must expose — `<entity>Uid` identifiers, `direction` with unsigned amounts, wrapped list responses,
cursor feeds. Berlin Group's NextGenPSD2/openFinance framework is the continental XS2A answer to the
same regulation; it is broader in adoption (≈3,600 banks) but structurally further from what this
codebase already looks like — it is PSD2-shaped, with `consentId`-centric flows, SCA status
sub-resources and a different money encoding. Reviewing tiny-ledger against Berlin Group would mean
comparing it to a standard neither its reference bank nor its API shape has anything to do with, and
every finding would be "differs" without being informative. UK OBIE is the closer fit, and it is also
the stricter one on the dimensions that matter here (FAPI, interaction ids, data dictionary), so a gap
analysis against OBIE bounds the Berlin Group gap as well.

---

## 3. Dimension-by-dimension mapping

"Cost" is engineering cost at this POC's scale, assuming the change lands in Plan 3 or 4.

| # | Dimension | What OB v4.0.1 requires | Spec today | Gap | Verdict + cost |
|---|---|---|---|---|---|
| 1 | **URI structure** | `[prefix]/open-banking/[version]/[resource-group]/[resource]/[id]/[sub-resource]`; `resource-group` ∈ `aisp`/`pisp`/`cbpii`; version as `/v[major].[minor]/` (Profile §Basics → Resource URI Path Structure) | `/api/v1/accounts/{accountUid}/…` | Constant segment `open-banking`, a PSD2 role group the ledger has no roles for, minor version in path | **Never** as literal OB paths — the `aisp` segment asserts a regulatory role this app does not hold. Cost if forced: 1 day (paths, OpenAPI, CLI, Cucumber). Value: zero without ecosystem membership |
| 2 | **Response envelope** | Every response is `{ "Data": {…}, "Links": {…}, "Meta": {…} }`; `Links.Self` mandatory and absolute (Profile §Data Model → Links) | `{ "transactions": [ … ], "links": { "next": "…" } }` | Named key instead of `Data`; no `Self`; no `Meta` | **Adapt, later** — `links.self` is a 1-hour addition and is good REST regardless. Full `Data`/`Meta` wrapping: 1 day, and it makes the payloads worse for a non-OB consumer |
| 3 | **Money on the wire** | `OBActiveOrHistoricCurrencyAndAmount`: `Amount` is a **string**, pattern `^\d{1,13}$\|^\d{1,13}\.\d{1,5}$`; `Currency` is ISO 4217 `^[A-Z]{3}$` (Balances/Transactions data dictionaries) | `{ "currency": "GBP", "minorUnits": 10000 }`, `minorUnits` an `int64` | **Direct conflict.** OB is unsigned decimal-string with up to 5 decimal places; spec is integer minor units | **Council decision — see §4.1.** Cost to align: 2–3 days plus a permanent lossy edge (5-dp OB amounts do not fit minor units of every currency) |
| 4 | **Sign convention** | `CreditDebitIndicator` ∈ `Credit`/`Debit`, amount always positive (Transactions §Data Model) | `direction` ∈ `IN`/`OUT`, amount always positive | Vocabulary only — the idea is identical | **Adapt, now (free).** Rename or document the mapping in §7.1. Cost: minutes. This is the one place the spec is already OB-shaped by accident, via Starling |
| 5 | **Transaction timestamps** | `BookingDateTime` (1..1) and `ValueDateTime` (0..1); `Status` from `OBInternalStatus1Code` (`BOOK`) | `transactionTime` / `settlementTime`, `status: SETTLED` | Naming, and OB's status codes are ISO-derived four-letter codes | **Adapt, later.** Document the field mapping; renaming is a breaking change with no consumer to serve. Cost: 2 hours to document, 1 day to rename |
| 6 | **Pagination** | `Links.Next`/`Links.Prev` MUST be present when further pages exist; `First`/`Last`/`Meta.TotalPages` MAY; 25–1000 records per page SHOULD. **"This standard does not specify how the pagination parameters are passed"** (Profile §Basics → Pagination) | Keyset cursor, `links.next` only | Missing `Prev`; `TotalPages` unavailable by construction | **Adopt-compatible already.** OB explicitly leaves the mechanism to the ASPSP, so keyset cursors are conformant. `Prev` on a keyset feed is real work (2 days) for no consumer. Cost of the honest fix — recording in §7.1 that OB permits this — is minutes |
| 7 | **Idempotency** | `x-idempotency-key` header, ≤40 chars, **POST only**, 24-hour replay window, unchanged body required, replay answers `201` (Profile §Basics → Idempotency) | Client-generated UID in the path + `PUT`; no header; no expiry; `200` on replay, `409` on changed payload | **Direct conflict** on mechanism, verb, replay status code and window | **Council decision — see §4.2.** Cost to add the header alongside: 1–2 days and a second dedup mechanism to keep honest — exactly the "dual-mechanism disease" §4.3 names |
| 8 | **Error model** | `OBErrorResponse1`: `{ Id, Errors: [{ ErrorCode, Message, Path, Url }] }`, `ErrorCode` drawn from `OB_Internal_CodeSet` (e.g. `AC17`); top-level `Code`/`Message` deprecated in v4 (Profile §Data Model → Error Response Structure) | RFC 7807 `ProblemDetail` with `type` URIs and `traceId` (§6.5) | **Direct conflict** on media type and shape — though both are machine-readable, unlike Starling's | **Council decision — see §4.3.** Cost to switch: 2 days plus adopting an external code set the ledger's failures do not map onto |
| 9 | **Status-code semantics** | Unknown `resource-id` → **400**, not 404; empty result → 200 with empty array; unimplemented endpoint → **404**; 403 for scope violations (Profile §Basics → HTTP Status Codes, "400 v/s 404") | 404 `/errors/account-not-found`; 403 for wrong owner *and* wrong role; 501 for auditor ops in `standalone` | OB's 400-for-unknown-id is unusual; OB has no 501 convention | **Diverge, documented.** 404 for an unknown UUID is better engineering; 501 is more honest than 404 for "this mode does not run that". Cost of documenting: minutes |
| 10 | **Security profile** | ASPSPs must implement **FAPI 1.0 Advanced** (hybrid flow) over OIDC | Keycloak OIDC, plain bearer JWT resource server (§6.4) | Sender-constrained tokens absent; PAR/PKCE/`private_key_jwt` not configured | **Partially adopt now — see §5.** The resource-server half of FAPI is days, not weeks, and Keycloak 26.4 + Spring Security 6.5 already ship it |
| 11 | **Consent model** | `POST /account-access-consents` → intent id → PSU authorises → access token bound to one PSU + one intent; `Permissions` array (`ReadBalances`, `ReadTransactionsDetail`, …) | Roles (`ledger:reader`/`writer`/`auditor`) + ownership check against the JWT subject | Entire intent/consent lifecycle absent | **Skip.** There is no TPP, no PSU-vs-TPP split, no delegated access. Cost to build: 2+ weeks and a consent aggregate the domain does not want. Building it would be inventing a third party to protect a user from |
| 12 | **Interaction headers** | `x-fapi-interaction-id` response header **mandatory** — echo the client's value or mint an RFC 4122 UUID, on success **and** error. `x-fapi-auth-date` / `x-fapi-customer-ip-address` optional on requests (Profile §Basics → Headers) | `traceId` inside the problem body only (§6.5); no correlation header on success responses | One response header, one filter | **Adopt now.** Cheapest OB conformance available: a servlet filter that echoes-or-mints and puts the value on the MDC/span. Cost: ~2 hours including a Cucumber scenario. Value beyond OB: correlation on 2xx, which the spec currently lacks |
| 13 | **Message signing** | Detached JWS in `x-jws-signature`, `PS256`, key lodged with a Trust Anchor, `http://openbanking.org.uk/iss` claim (Profile §Basics → Message Signing) | None; §7.1 already skips Starling's request signing as out of scope (§13) | Total | **Skip.** Requires a trust anchor that does not exist for a laptop deployment. Cost: ≥1 week and a key-management story |
| 14 | **Directory / eIDAS / OBWAC-OBSEAL** | TPP onboarding, OB Directory registration, QWAC/QSEAL or OBWAC/OBSEAL certificates | None | Total | **Skip, permanently.** This is ecosystem membership, not software. It cannot be "done" in a repo |
| 15 | **Operational conformance** | Availability/performance MI reporting, published dashboards, conformance suite certification | Not applicable | Total | **Skip.** Regulated-entity obligations |

---

## 4. Conflicts with council-closed decisions

Three OB conventions contradict decisions the council closed and recorded in the revision history. None
of them should be reversed by a review document. Each is stated as an either/or with the trade-off, for
the council to rule on.

### 4.1 Money: OB's decimal string `Amount` vs the spec's integer `minorUnits`

**Closed as:** `{currency, minorUnits}`, adopted from Starling, recorded in §7.1 and v3.1 of the revision
history; §7 states "no float anywhere, and no decimal-string parsing ambiguity either".

**OB requires:** `"Amount": { "Amount": "10.00", "Currency": "GBP" }` — a string matching
`^\d{1,13}$|^\d{1,13}\.\d{1,5}$`, i.e. unsigned, up to 13 integer digits and up to **5** decimal places.

| Option | For | Against |
|---|---|---|
| **A — keep `minorUnits`** (status quo) | The domain `Money` serialises without translation (§4.6). No parsing ambiguity, no scale inference, no rounding decision at the boundary. An `int64` of pence is exactly what a ledger's arithmetic is | Diverges from the OB data dictionary, which is ISO 20022-rooted; an OB-native consumer needs a translation layer |
| **B — adopt OB's `Amount` string** | Conformant on the single most visible data element; ISO 20022 lineage; interoperable with any OB tooling | Reintroduces the exact hazard §7 rejected: every read is a `BigDecimal` parse with a scale the currency implies rather than states. OB's 5-decimal-place allowance does not round-trip through minor units for any currency — the ledger would either reject legal OB amounts or silently lose precision. Both are worse for a ledger than being unconventional |
| **C — keep `minorUnits`, document the divergence in §7.1** | Costs an hour. The council's reasoning survives in writing next to the standard it departs from, which is what §7.1 exists to do | Still not conformant — this option is honesty, not compliance |

**Reviewer's read:** the precision argument is stronger than the conformance argument for a system whose
entire purpose is arithmetic on money, and OB's own amount type is a *presentation* format for
institutions that hold the authoritative balance in minor units internally. C. But this is the council's
call, not the reviewer's — and if the answer is B, it must be B everywhere, not a second money shape.

### 4.2 Idempotency: OB's `x-idempotency-key` header vs the spec's `PUT` + path UID

**Closed as:** "Starling's mechanism, adopted whole… no header machinery, and no expiry window — an
identity does not expire" (§6.3); §7.1 records that adopting it *deleted a port*.

**OB requires:** an `x-idempotency-key` request header (≤40 chars) on designated **POST** endpoints, a
**24-hour** dedup window, and `201` on replay. The spec's replay answers `200`; a changed payload under
the same UID answers `409` rather than OB's "must not modify the end resource".

| Option | For | Against |
|---|---|---|
| **A — keep `PUT` + path UID** (status quo) | The event store's unique index *is* the dedup store; there is nothing to drift. Idempotency survives forever, which is what an identity means. Deleted a port and a whole class of race handling | The mechanism is not OB's, and a header-driven TPP client would need adapting |
| **B — accept `x-idempotency-key` as well** | Conformant for clients that speak OB | Two dedup mechanisms for one concern — the dual-mechanism disease §4.3 names by name — plus a 24-hour expiry window whose semantics contradict "an identity does not expire". Also needs `POST` variants of both movement endpoints, undoing §6.3 |
| **C — keep the mechanism, add the header as an accepted alias** (header value must equal the path UID, else `400`) | One dedup store, one identity, and OB-shaped clients are not rejected outright | Half-conformant, and the equality rule is a rule nobody asked for. YAGNI until an OB client exists |

**Reviewer's read:** A, with the conflict recorded in the OB alignment table. The spec's mechanism is
strictly stronger than OB's — no window, no separate store — and OB's is a workaround for POST's
non-idempotency that the spec sidesteps by not using POST.

### 4.3 Errors: OB's `OBErrorResponse1` vs RFC 7807 `problem+json`

**Closed as:** RFC 7807 throughout (§6.5), recorded in §7.1 as a deliberate **divergence from Starling**
because "Starling's errors carry no machine-readable code; ours must".

**OB requires:** `{ "Id", "Errors": [{ "ErrorCode", "Message", "Path", "Url" }] }` with `ErrorCode` from
the OB internal code set (e.g. `AC17`). Unlike Starling's, OB's errors *are* machine-readable — so the
reason §7.1 gives for diverging from Starling does not apply to OB.

| Option | For | Against |
|---|---|---|
| **A — keep RFC 7807** (status quo) | An IETF standard, native to Spring (`spring.mvc.problemdetails.enabled`), already carries `traceId`, already implemented across eleven catalogue rows | Not the OB shape; an OB client parses `Errors[].ErrorCode`, not `type` |
| **B — switch to `OBErrorResponse1`** | Conformant; `Id` maps naturally onto `traceId` | Requires mapping eleven domain failures onto an external code set built for payment-scheme reason codes — `AC17` and friends have no row for "insufficient funds in a toy ledger". Loses `application/problem+json` content negotiation and Spring's built-in support |
| **C — RFC 7807 as the wire shape, OB `ErrorCode` as an extension member** | Both machine-readable keys present; costs about half a day | An OB client still cannot parse it — the envelope is wrong, not just the code. Buys correctness signalling, not conformance |

**Reviewer's read:** A. The §7.1 rationale needs one sentence of amendment, though: RFC 7807 was chosen
over *Starling's* errors for machine-readability, and that argument does not carry against OB. The
honest reason to keep 7807 against OB is that it is an IETF standard with framework support and OB's
code set does not describe this domain.

---

## 5. FAPI implications for the Plan 3 Keycloak setup

**Which profile.** OBL R/W v4.0.x mandates **FAPI 1.0 Advanced** (hybrid flow, JWS request objects,
JARM). FAPI 1.0 is the compliance target if the goal is UK OB conformance; **FAPI 2.0 Security Profile**
(Final, February 2025) is the better engineering target and is where new deployments are going — it
replaces JAR with PAR, drops the hybrid flow to `response_type=code` only, and replaces `s_hash` with
PKCE (FAPI 2.0 §5.5, "Main differences to FAPI 1.0"). For a POC with no TPP ecosystem, targeting 1.0's
hybrid flow to match OBL would be conforming to the older spec for the sake of a certification nobody
will run.

**What tiny-ledger actually is.** A resource server. FAPI 2.0 §5.3.4 puts four obligations on resource
servers, and only the fourth is new work here:

1. accept access tokens in the `Authorization` header only — already true;
2. **not** accept tokens in query parameters — already true, worth an explicit test;
3. verify validity, integrity, expiry and revocation — validity/integrity/expiry already; revocation is
   not checked (introspection or short TTLs);
4. **support and verify sender-constrained access tokens** via mTLS (RFC 8705) **or** DPoP (RFC 9449) —
   absent today. This is the one substantive gap between §6.4 and FAPI.

**Concrete Plan 3 settings.** Keycloak 26.4 ships FAPI 2.0 Final client profiles
(`fapi-2-security-profile`, `fapi-2-dpop-security-profile`) and official DPoP support; Spring Security
6.5+ auto-validates DPoP proofs under `oauth2ResourceServer`, so both halves are configuration rather
than code.

| Setting | Where | Cost | Verdict |
|---|---|---|---|
| Attach the `fapi-2-dpop-security-profile` client policy to the `tiny-ledger` realm clients | `docker/keycloak/realm-tiny-ledger.json` | ~half a day incl. re-testing the CLI's client-credentials flow | **Do it** — it is realm JSON, and it makes the fixture realm demonstrate something |
| DPoP-bound access tokens end to end (Keycloak issues, Spring validates, Python CLI proves) | realm JSON + `oauth2ResourceServer` + §11 CLI | 1–2 days, mostly in the CLI's token handling | **Do it** if the council wants one visible FAPI item. This is "sender-constrained tokens", the headline FAPI control |
| PAR + PKCE `S256` required, `private_key_jwt` client auth | realm JSON | ~half a day | **Do it** — free correctness for the authorization-code clients; no effect on the resource server |
| mTLS certificate-bound tokens (RFC 8705) instead of DPoP | compose stack, cert plumbing | 3–5 days, and a certificate lifecycle in a laptop compose file | **Skip.** FAPI 2.0 permits either; DPoP is the one that costs a config flag rather than a PKI |
| FAPI 1.0 hybrid flow + JWS request objects + JARM | realm JSON | 2–3 days | **Skip.** Conformance to the older profile, for an ecosystem this app is not in |
| Message signing (`x-jws-signature`, trust anchor) | app + key management | ≥1 week | **Skip** (§3 row 13) |

**What FAPI does not fix.** Nothing in FAPI addresses the ownership check — `mallory` (§6.4) fails on
ownership, not on token binding. FAPI hardens *who holds the token*; §6.4's decorator answers *whose
account it is*. Both are needed and the spec already has the harder one.

---

## 6. Recommended adoption posture

Same shape as §7.1, so the council reviews a familiar table.

### Proposed §7.2 — Open Banking alignment

Reference: UK Open Banking Read/Write API Standard v4.0.1 and FAPI 2.0 Security Profile (Final,
Feb 2025), reviewed 2026-08-04.

| OB convention | Verdict | Why |
|---|---|---|
| `x-fapi-interaction-id` request/response correlation header | **Adopt** | One filter; gives 2xx responses the correlation `traceId` only gives errors |
| Unsigned amount + direction indicator (`CreditDebitIndicator`) | **Adopt** (already, as `direction` `IN`/`OUT`) | Same idea, arrived at via Starling; record the mapping |
| Keyset cursor pagination | **Adopt** (already conformant) | OB leaves the pagination mechanism to the ASPSP |
| Sender-constrained tokens (FAPI 2.0 §5.3.4) via DPoP | **Adopt** | Keycloak 26.4 client profile + Spring Security 6.5 auto-validation; the one real §6.4 gap |
| `Links.Self` on list and item responses | **Adapt** | One field, good REST; the rest of the `Data`/`Meta` envelope is not worth the churn |
| `Data`/`Meta` response envelope | **Diverge** | Named list keys (`{"transactions": […]}`) already extensible; wrapping buys nothing without an OB client |
| `{Amount: "10.00", Currency}` decimal-string money | **Diverge, documented** | §4.1 — precision safety over conformance; the divergence is recorded, not accidental |
| `x-idempotency-key` header, 24-hour window | **Diverge, documented** | §4.2 — the path-UID mechanism has no window and no second store to drift |
| `OBErrorResponse1` error shape | **Diverge, documented** | §4.3 — RFC 7807 is an IETF standard with framework support; OB's code set does not describe this domain |
| Unknown resource-id → 400 rather than 404 | **Diverge** | 404 on an unguessable UUID is better engineering (§6.5) |
| `/open-banking/v4.0/aisp/…` URI structure | **Skip** | The `aisp` segment asserts a PSD2 role this app does not hold |
| Account-access-consent / intent lifecycle, `Permissions` | **Skip** | No TPP, no PSU/TPP split, no delegated access to consent to |
| Detached JWS message signing, trust anchor | **Skip** | Already out of scope (§13); needs a trust anchor a laptop does not have |
| OB Directory, eIDAS/OBWAC/OBSEAL certificates, TPP onboarding | **Skip** | Ecosystem membership, not software |
| FAPI 1.0 Advanced hybrid flow, JAR, JARM | **Skip** | Superseded by FAPI 2.0 for new work; conformance value is zero outside the OB ecosystem |
| MI / availability / performance reporting | **Skip** | Regulated-entity obligation |

### The "from birth" items worth doing, if the council agrees

Four, ordered by cost-to-value. All fit Plans 3–4; none touch the domain.

1. **`x-fapi-interaction-id` filter** (~2 hours). Echo the client's RFC 4122 value or mint one; return it
   on every response, success and error; bind it to the MDC and the current span so it lands in the
   existing OTel picture (§6.6). Add one Cucumber scenario asserting echo-and-mint. Highest
   conformance-per-hour item in this review, and it fixes a real gap: today only failures carry a
   correlation id.
2. **FAPI-aligned Keycloak client policy** (~half a day). Attach `fapi-2-dpop-security-profile`, require
   PAR and PKCE `S256`, `private_key_jwt` client auth for the confidential clients. Realm JSON only.
3. **DPoP sender-constrained tokens end to end** (1–2 days). Keycloak issues, Spring Security 6.5
   validates automatically, the §11 Python CLI proves it. Closes the one substantive §6.4-vs-FAPI gap and
   is demonstrable in the e2e suite.
4. **A §7.2 alignment table in the spec** (~1 hour). The table above, adopted verbatim or amended. This is
   the item that makes "we considered Open Banking" a decision with a citation rather than an omission —
   the same job §7.1 does for Starling.

Explicitly **not** recommended: rewriting money, paths, the error model, or idempotency. Each is a
council-closed decision, each costs days, and none of them buys anything until a real OB consumer exists.

---

## 7. Open questions for the council

1. **Money format.** Keep `{currency, minorUnits}` and record the OB divergence in §7.1/§7.2 (option
   §4.1-C), **or** adopt OB's decimal-string `Amount` on the wire and translate at the boundary
   (§4.1-B)? A decision either way; the reviewer's read is C, but the precision-vs-conformance trade is
   the council's to weigh.
2. **FAPI target.** FAPI 2.0 (the better engineering target, and what Keycloak 26.4 ships as a profile)
   **or** FAPI 1.0 Advanced (what OBL v4.0.1 actually mandates, so the only one that is "OB compliant")?
   They are mutually exclusive at the realm-config level.
3. **How far to take FAPI.** Client policy only (~half a day, no runtime change), **or** DPoP-bound
   tokens proven end to end through the CLI and e2e suite (1–2 days, and the CLI's token handling
   changes)?
4. **Where the OB verdict lives.** A new §7.2 alignment table alongside §7.1, **or** extra rows in §7.1
   with a source column? The first keeps the Starling table pure; the second keeps one table to maintain.

---

*Reviewed against the sources and versions in §1 on 2026-08-04. No spec or code was modified.*
