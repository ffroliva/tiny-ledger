# Council review — docs/spec.md · 2026-08-03

Artefact: `docs/spec.md`, reviewed from v3.0 (`1e9cd71`) to closure at v3.3 (`b6ddca2`).
Tool: `agent-council` (C:\development\github\agent-council), first production run.
Probed: codex ok (codex-cli 0.146.0, openai) · agy ok (1.1.10, google) · claude-cold ok (2.1.220, anthropic).

## Consensus at closure: 🟢 GREEN

| Reviewer | Family | R1 | R2 verify | Final |
|---|---|---|---|---|
| D1 contradiction (internal) | anthropic | RED, 9 must-fix | YELLOW — 2 residue + 1 new | closed in v3.2 |
| D2 completeness (internal) | anthropic | YELLOW, 7 | YELLOW — 1 residue + 2 new | closed in v3.2 |
| D3 cross-ref drift (internal) | anthropic | YELLOW, 5 | YELLOW — 1 residue + 4 new | closed in v3.2 |
| D4 security (internal) | anthropic | RED, 5 | **GREEN** + 1 minor new | closed in v3.2 |
| D5 over-engineering (internal) | anthropic | YELLOW, 5 | YELLOW — 1 residue | closed in v3.2 |
| claude-cold | anthropic (internal-cold) | YELLOW, 8 | **GREEN 8/8** | **GREEN 10/10** (v3.3 closure check) |
| codex | openai (external) | r1 unparsable → retry: 9 findings | deep pass: 5 critical + 5 important | closed in v3.3 |
| agy | google (external) | **failed at dispatch** (known non-interactive failure) — dropped, disclosed | — | — |

**Disclosure:** only one external family (openai) delivered; the google seat failed exactly as its
registry note predicted. The council was internal + cold-context + one external family — stated
here rather than claimed otherwise. The family-quorum rule did its job by making this visible.

## Numbers

- 39 round-1 findings → 11 deduplicated clusters, all fixed (v3.1).
- Codex retry: 9 findings, 5 confirming v3.1 fixes cross-vendor, **4 unique** (transaction
  boundary, governance-baseline gate, N2 retry-to-terminal, global idempotency lookup) (v3.1→7ef592c).
- Round-2 verification: 6 residue/new items (v3.2) + security's N12 listing-scope find.
- Codex deep pass: 5 critical + 5 important, all fixed (v3.3) — headline: authorise-before-idempotency
  ordering, Modulith registry guarantees configured-not-assumed, framework annotations evicted from
  pure layers, honest brief-compliance framing.
- Final cold verification at v3.3: **10/10 RESOLVED, no new defects, GREEN.**
- Rounds: 3 (the cap). Escalations: 0.

## False positives (kept for auditability)

- Synthesiser's own spot-check flagged an apparent backslash path in §7 — a rendering artifact of
  the search tooling, not file content. Recorded because the false positive was *mine*, not a
  reviewer's.
- Initial spot-check of "reads through the aggregate" failed to match only because the phrase spans
  a line break; the finding itself was CONFIRMED.

## Spec versions produced

| Version | Commit | Content |
|---|---|---|
| 3.1 | `6cadae4` | 11 round-1 clusters + mechanical batch |
| 3.1+ | `7ef592c` | codex's 4 unique findings |
| 3.1+ | `4a692e8` | keyset-over-`Pageable`, no-`Specification` (owner question, recorded) |
| 3.2 | `4d6c6e8`/`b06646a`/`0e8d1a0` | round-2/3 residue closure |
| 3.3 | `b6ddca2` | codex closure gate, verified GREEN by cold instance |

## What the run demonstrated

Every class of reviewer earned its seat: the internal dimensions found the volume (39), the
cold-context instance independently confirmed the four biggest finds *and* was the only reviewer to
catch `@WebMvcTest`-vs-unit-definition drift, and the external family found four defects no
anthropic-family reviewer saw — including the §4.1 authorisation-ordering security bug. Fixes
introduced their own drift twice (publication residue, ambiguous strong-read mapping), which is the
argument for re-verification rounds being reviewer-scoped rather than trust-based.

COUNCIL_VERDICT: GREEN | MUST_FIX: 0 open | ESCALATED: 0 | ROUNDS: 3
