# Proposal — unify the error catalogue behind `TinyLedgerException` + `ErrorCode`

**Status:** proposed, approved in principle by the user 2026-08-04 · **Target:** Plan 3, folded into
the parked CR12 (`/errors/invalid-amount` catalogue mismatch) and the approved Open Banking
"machine-readable ErrorCode" item · **Spec impact:** §6.5 gains the catalogue table as the generated
authority; no §7 contract change

## The problem

§6.5 declares itself the single authority for errors. In code the catalogue lives in five places:

| Where | What it holds |
|---|---|
| `platform/ErrorHandlingAdvice.java:49-84` | six `@ExceptionHandler` methods, each hard-coding status + `type` + title |
| `ledger/adapter/in/web/LedgerApiMapper.java:73-88` | the 422 refusals — `type` built by **string concatenation** |
| `audit/adapter/in/web/AuditController.java:157-161` | the 501 `not-available-in-standalone` |
| `balance/adapter/in/web/BalanceController.java:177-181` | a 404 `account-not-found` that **also** exists as `AccountNotFoundException` |
| `docs/spec.md` §6.5 | the table that is supposed to govern the four above |

Two concrete defects follow from it:

1. **A published contract is stringly typed.** `MovementResult.rejectionReason` is a bare `String`,
   and `LedgerApiMapper:76` does `"/errors/" + result.rejectionReason()` with a `switch` on the same
   string for the title and `default -> "Movement rejected"`. A domain typo — `"insuficient-funds"` —
   ships `/errors/insuficient-funds` titled "Movement rejected". Nothing fails.
2. **`IllegalArgumentException` is catalogued as a bad amount.** `ErrorHandlingAdvice:55` maps every
   IAE to `/errors/invalid-amount` "Invalid amount", so a malformed pagination cursor (where
   `UUID.fromString` throws) tells the caller their *amount* is wrong. This is CR12.

Six sibling exceptions extending `RuntimeException` directly is the symptom; the smeared catalogue is
the cause.

## Design

**`ErrorCode`** — an enum in `shared`, framework-free, carrying `status` (int), `type` (the
`/errors/…` URI) and `messageKey`. This is the catalogue, once.

**`TinyLedgerException`** — an abstract `RuntimeException` in `shared`, carrying an `ErrorCode` and
message arguments. The six existing exceptions extend it. Named for the system, not the `ledger`
*module* — `com.flaviooliva.ledger.ledger` already exists, so `LedgerException` would read as that
module's exception when it is the supertype for `audit`, `balance` and `notification` too.

**One translation point** — a single `@ExceptionHandler(TinyLedgerException.class)` in `platform`
builds the `ProblemDetail` from the code and resolves `detail` from a `MessageSource`.

### Why the exception stays framework-free

The tempting alternative is `implements org.springframework.web.ErrorResponse`, which would let
`ErrorHandlingAdvice`'s existing `instanceof ErrorResponse` branch (`:95`) serve these exceptions
with no new handler, and would supply `getDetailMessageCode()` for i18n for free. Rejected: the
supertype belongs in `shared`, which is the open kernel `domain` compiles against (`Money`,
`AccountId`), so implementing a spring-web interface there puts spring-web on the domain's transitive
compile path. `HexagonalRulesTest.domainIsFrameworkFree` would **not** catch it — ArchUnit's
`dependOnClassesThat` is direct-only, so `Account → Money → spring-web` passes — which makes it worse,
not better: the fence would be defeated silently. The cost of staying clean is roughly ten lines of
`ProblemDetail` assembly in the handler.

### No domain exception hierarchy

The domain has no catalogued business errors and needs no supertype:

- **Expected refusals** are return values, not exceptions — `MovementResult` with an outcome. This is
  already the design and it is the right one; "you cannot afford this" is an answer, not an error.
- **Broken invariants** throw plain JDK exceptions (`Account.java:30,76,95`) and are 500s. They are
  bug signals with nothing to catalogue.
- **Errors the caller must be told about** (not found, conflicts, ownership, currency mismatch) already
  live outside `..domain..`, in `ledger/application/error/` and `shared`.

## The five moves

1. `ErrorCode` enum in `shared` — the catalogue.
2. `TinyLedgerException` in `shared`; the six exceptions extend it and lose their individual handlers.
3. **Replace `MovementResult.rejectionReason: String` with `ErrorCode`.** Do this one first — it turns a
   published contract from stringly typed into compiler-checked and deletes both the concatenation and
   the `switch`.
4. One `@ExceptionHandler(TinyLedgerException.class)`; delete the six specific handlers and the three
   hand-built `ErrorResponseException` sites. **Leave `unexpected(Exception)` unchanged** — it already
   logs, returns an empty 500 body, and honours declared `ErrorResponse` headers.
5. Drop `IllegalArgumentException` from the 400 handler. Type malformed input where it is parsed (a bad
   cursor gets its own code) and let unknown IAEs be 500s, because they are bugs.

Net effect is a deletion: four sources of truth collapse into one.

## The part that makes it hold

One test pinning the enum to the spec: parse §6.5's markdown rows and assert a bijection with
`ErrorCode`. Adding an error to the spec without the enum, or the enum without the spec, then fails the
build. Every other item above is a one-time tidy; this is the only mechanism that keeps the catalogue
honest once nobody remembers writing it. The repo already has the habit —
`scripts/ci/check_docs_governance.py`, CI stage 6 — so it belongs there rather than in a unit test.

## Internationalisation

Carry `messageKey` + arguments from the start; that is free. Resolve through a `MessageSource` only when
a human-facing surface exists (Plan 4's CLI is the first candidate). Localise `detail` only: RFC 7807
says `title` "SHOULD NOT change from occurrence to occurrence", and `type` is the identifier clients
match on, so both stay fixed. This keeps the machine-readable contract stable while the human-readable
field varies — which is also the direction the approved Open Banking item points.

## Explicitly not in scope

A domain exception hierarchy; `ErrorResponse` on the `shared` parent; per-module hierarchies; any i18n
resolution machinery before there is a human reading the output.

## Companion decision — root package rename to `com.flaviooliva.tinyledger`

Decided by the user 2026-08-04, in the same discussion: the root package becomes
`com.flaviooliva.tinyledger`, so the module package stops doubling the word
(`com.flaviooliva.ledger.ledger` → `com.flaviooliva.tinyledger.ledger`). `tledger` was considered and
rejected as less readable — the abbreviation saves four characters in a string that is autocompleted,
at the cost of a prefix needing explanation forever.

**Do it as its own commit, separate from any behaviour change.** It touches 110 of 110 Java files, so
mixing it with real work makes the diff unreviewable and invalidates any outstanding review that cites
`file:line`. Alone, "both pipelines green, zero behaviour delta" is a complete proof of it.

Touchpoints beyond the Java files:

- `pom.xml`: `<apiPackage>`, `<modelPackage>` (OpenAPI generator) and JaCoCo's
  `<include>com.flaviooliva.ledger.*.domain*</include>` domain-coverage rule.
- `docs/spec.md` §2 source tree, and the plan documents.
- Nothing in `*.properties` — verified clean.

**The trap:** several references are *string literals*, not symbols, so an IDE "rename package" will
not finish the job — `HexagonalRulesTest`'s `@AnalyzeClasses(packages = "com.flaviooliva.ledger")`, the
`generatedDtosStayInWebAdapters` rule's `"com.flaviooliva.ledger.api.generated.."`, and the JaCoCo
include above. Missed, the architecture fence analyses nothing and still reports green. Run with
`clean` so the generator re-emits into the new packages.

## Open questions for Plan 3 planning

1. Does `ErrorCode` also absorb the 429 rate-limit and 501 standalone rows, or do those stay adapter
   concerns? (Leaning: absorb — they are catalogue rows in §6.5 like any other.)
2. Should the `messageKey` default from the enum constant name (`ACCOUNT_NOT_FOUND` →
   `problem.account-not-found`) rather than being declared, to remove a second thing to keep in sync?
3. Does `LedgerApplication` become `TinyLedgerApplication` in the same naming pass? (Leaning: yes —
   once the package is `tinyledger` and the supertype is `TinyLedgerException`, the bootstrap class is
   the last holdout.)
