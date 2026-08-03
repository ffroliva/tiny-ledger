---
name: iso-compliance
description: Use when assessing or bringing any codebase to ISO compliance (ISO/IEC/IEEE 15289 documentation, ISO/IEC 27001 A.8 security, ISO/IEC 25010 quality) — grades the repo on three evidence-backed axes, generates the compliance artefact set, installs a machine-checkable governance test, and drives the audit → grade → spec → plan → council → execute → review → close pipeline. Stack-agnostic: works for Java/Maven, Python, Node, Go or polyglot repos via a stack adapter.
---

# ISO Compliance (15289 / 27001 A.8 / 25010)

Take a repo from "good code that cannot prove it" to an evidence-backed compliance grade in one
focused run.

**The governing idea:** compliance is not code quality — it is the *ability to demonstrate* code
quality to someone who does not trust you. A codebase can be A− on merit and D+ on compliance at the
same time, and the gap is entirely artefacts and enforcement. Every grade below is therefore
evidence-backed: **every claim greppable in the repo, or cited from an audit.** No vibes.

**Provenance:** this methodology was executed end to end on a Java/Spring backend (D+ → A− in a
day). This is the stack-agnostic port of that run — the standards, rubric, artefact set and pipeline
are the same; everything site-specific has been replaced by §1's adapter.

---

## 1. Stack adapter — fill this in first

The rest of the skill refers only to these symbols. Nothing below hard-codes a language or build
tool. Record the filled-in table in the assessment doc.

| Symbol | Meaning | Java/Maven | Python | Node | Go |
|---|---|---|---|---|---|
| `$BUILD_VERIFY` | Full build + test, the evidence command | `mvn clean verify` | `uv run pytest` | `npm run ci` | `go test ./...` |
| `$SOURCE_GLOBS` | Main sources, for marker scans | `src/main/**/*.java` | `src/**/*.py` | `src/**/*.ts` | `**/*.go` |
| `$QUALITY_GATE` | Build-failing static analysis | PMD / SpotBugs | `ruff` + `pyright` | `eslint` + `tsc` | `golangci-lint` |
| `$COMPLEXITY_BUDGET` | Enforced thresholds | PMD 25 method / 100 class | `ruff C901` | `complexity` rule | `gocyclo` |
| `$AGENT_DOC` | Agent instruction file the repo uses | `AGENTS.md`, `CLAUDE.md` or `.github/copilot-instructions.md` — whichever exists | | | |
| `$VERSION_SOURCE` | Canonical version, and the string that must never appear in docs | `pom.xml`, never `-SNAPSHOT` | `pyproject.toml`, never `.dev` | `package.json` | tag |
| `$APP_NAME` | Used in `docs/threat-models/<app>.md` | | | | |

Two rules that survive every stack:

- **The quality gate must fail the build.** Culture-enforced quality grades C — see §3.
- **The governance test must run in `$BUILD_VERIFY`.** A check that only runs when someone remembers
  is not a control.

---

## 2. Artefact set (the checklist)

Eighteen artefacts plus machinery. Every one must exist at the end; §4's governance test enforces
presence mechanically.

**Root:**

| Artefact | Standard | Notes |
|---|---|---|
| `CHANGELOG.md` | 15289 | Keep-a-Changelog. Mandatory-per-change. Earlier history stays in git — never retro-document |
| `$AGENT_DOC` | all three | The authoritative detail. Keep any secondary agent file lean and pointing here |

**`docs/`:**

| Artefact | Notes |
|---|---|
| `ISO-COMPLIANCE.md` | The hub: standards in scope, artefact map (path → standard → update trigger), **nine-characteristic 25010 coverage table** (evidence, or an honestly registered gap, per characteristic; N/A requires a reason), security and quality rules summary, diagram standard (§5), dated acceptance records |
| `compliance-gaps.md` | The bridge register. **Stable `## <ID>` anchors**, append-only, entries closed only by a commit link. Seeded mechanically from the audit findings |
| `security-policy.md` | 27001 A.8 |
| `secure-coding-standards.md` | Including the repo's authorisation convention |
| `security-testing.md` | Mandated pattern, per endpoint: unauthenticated → 401/403, insufficient role → 403, happy path |
| `risk-assessment.md` | Likelihood × impact scheme |
| `risk-register.md` | Seeded from real current facts — open findings, single-maintainer risk, items closed today recorded as mitigated |
| `statement-of-applicability.md` | **All 34 A.8 controls** dispositioned implemented / partial / gap / N-A-with-reason. Opens with a scope note (A.5–A.7 organisational, people and physical controls are handled above the repo). "Implemented" seeded from the audit's verified positives. **Every Gap/Partial row links a `compliance-gaps.md` anchor** — no blanks in the register-link column |
| `incident-response.md` | At least one concrete runbook drawn from a real incident class in *this* repo |
| `vulnerability-management.md` | Must name a **real recurring mechanism** with a trigger and an owner. No paper controls; automation gaps get a register anchor |
| `threat-models/$APP_NAME.md` | Mermaid trust-boundary diagram + STRIDE per boundary; mitigations cite SoA controls and finding IDs |
| `api.md` | Full contract with `REQ-NNN` IDs per functional area. Verify every route/handler in `$SOURCE_GLOBS` appears |
| `database.md` | Entity inventory, Mermaid ER with real class names, real dialect/profile list (verify, never assume), fields with privacy implications |
| `deployment.md` | Topology, container build, runtime config (**variable names only, never values**), CI stages, backup posture — if none exists, say so and register it |
| `testing.md` | Test levels, structure, the security-test pattern, how to run |
| `traceability-matrix.md` | REQ-NNN ↔ code ↔ test ↔ doc. Missing coverage appears as an explicit "no test — gap" row, never as an omission |

**Machinery:**

- `.claude/documentation-templates.md` — the metadata block every governed doc uses
- `.claude/agents/compliance-reviewer.md` — review agent, checklist pointed at this repo's paths
- **`$QUALITY_GATE` bound to the build** with failure on violation, plus a baseline/exclusion file
  where **every exclusion carries a register row** — never a silent exclusion
- `scripts/test_docs_governance.py` — §4

---

## 3. Grading rubric (three axes, D–A)

Grade each axis independently, then overall. Split verdicts are normal and informative.

**Documentation (15289).** Artefact presence (§2) + per-doc metadata (version, date, ToC, revision
history, traceability) + management (routing index, archive discipline, no stubs, no forbidden
version strings). *A doc scrapyard — one-off fix logs mixed with living documents — caps this axis at
D regardless of volume.*

**Security (27001 A.8).** Artefact coverage (SoA and siblings) + open findings (any HIGH open = no A)
+ technical controls actually in the code: parameterised queries, non-leaky errors, validated input,
authentication on every surface, no secrets in the tree. *"Code C+, artefacts 0%" grades **D+
overall**, because compliance is the ability to prove it.*

**Quality (25010).** Measured and build-enforced, versus culture-enforced. An excellent codebase with
no failing static-analysis gate is one careless merge from eroding, and grades **C**. Look for:
enforced complexity and length budgets, REQ IDs, a gaps register, a `TODO(25010)` convention, and
per-characteristic evidence.

Record grades in an assessment doc with a **before / projected** table. It becomes the plan header
and the end-of-run re-grade target.

---

## 4. Required-section cross-check (the enforcement core)

Every governed doc carries seven literal markers. `Not applicable — [reason]` under a heading is
allowed; **omitting the heading is not.**

```
**Version:**                    (title line with version + date)
## Table of contents
## Scope & purpose
## Glossary & acronyms
## Traceability
## Open issues / known gaps
## Revision history
```

Running this skill on a repo **MUST** install this check. Python stdlib `unittest`, no dependencies,
so it works in any repo regardless of primary language. Adapt only the constants at the top.

```python
"""ISO documentation governance. Runs in $BUILD_VERIFY. No third-party deps."""
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

APP_NAME = "<app>"                      # §1 adapter
SOURCE_ROOTS = ["src"]                  # §1 $SOURCE_GLOBS roots
SOURCE_SUFFIXES = {".java", ".py", ".ts", ".go", ".kt"}
FORBIDDEN_VERSION_STRINGS = ("-SNAPSHOT",)   # §1 $VERSION_SOURCE

ISO_ARTEFACTS = [
    "CHANGELOG.md",
    "docs/ISO-COMPLIANCE.md", "docs/security-policy.md",
    "docs/secure-coding-standards.md", "docs/security-testing.md",
    "docs/risk-assessment.md", "docs/risk-register.md",
    "docs/statement-of-applicability.md", "docs/incident-response.md",
    "docs/vulnerability-management.md", f"docs/threat-models/{APP_NAME}.md",
    "docs/compliance-gaps.md", "docs/api.md", "docs/database.md",
    "docs/deployment.md", "docs/testing.md", "docs/traceability-matrix.md",
]

# Working papers, routers and registries are exempt from the 15289 section set.
UNGOVERNED_PARTS = {"superpowers", "_archive", "profiling", "diagrams", "generated", "source"}
UNGOVERNED_NAMES = {"INDEX.md", "README.md"}

ISO_REQUIRED_SECTIONS = (
    "**Version:**", "## Table of contents", "## Scope & purpose",
    "## Glossary & acronyms", "## Traceability",
    "## Open issues / known gaps", "## Revision history",
)


def read(rel: str) -> str:
    return (REPO_ROOT / rel).read_text(encoding="utf-8")


def governed_docs():
    for path in sorted((REPO_ROOT / "docs").rglob("*.md")):
        rel = path.relative_to(REPO_ROOT)
        if UNGOVERNED_PARTS.intersection(rel.parts) or rel.name in UNGOVERNED_NAMES:
            continue
        yield rel


def main_sources():
    for root in SOURCE_ROOTS:
        for path in sorted((REPO_ROOT / root).rglob("*")):
            if path.suffix in SOURCE_SUFFIXES and "test" not in path.parts:
                yield path


class DocsGovernance(unittest.TestCase):

    def test_iso_artefacts_exist(self):
        """Every artefact in §2 exists. CHANGELOG follows its own format."""
        for name in ISO_ARTEFACTS:
            with self.subTest(artefact=name):
                self.assertTrue((REPO_ROOT / name).is_file(),
                                f"ISO artefact {name} must exist (see docs/ISO-COMPLIANCE.md).")

    def test_governed_docs_carry_required_sections(self):
        for rel in governed_docs():
            content = read(str(rel))
            for marker in ISO_REQUIRED_SECTIONS:
                with self.subTest(doc=str(rel), marker=marker):
                    self.assertIn(marker, content,
                                  f"{rel} is missing required section marker {marker!r}.")

    def test_no_forbidden_version_strings(self):
        for rel in list(governed_docs()) + [Path("CHANGELOG.md")]:
            for bad in FORBIDDEN_VERSION_STRINGS:
                with self.subTest(doc=str(rel), string=bad):
                    self.assertNotIn(bad, read(str(rel)),
                                     f"{rel} must not reference {bad} versions.")

    def test_quality_todos_are_registered(self):
        """Every TODO(25010) in main sources is registered in the gaps register."""
        gaps = read("docs/compliance-gaps.md")
        for src in main_sources():
            if "TODO(25010)" in src.read_text(encoding="utf-8"):
                with self.subTest(source=src.name):
                    self.assertIn(src.stem, gaps,
                                  f"{src.name} carries a TODO(25010) but is not "
                                  "registered in docs/compliance-gaps.md.")

    def test_soa_gap_rows_link_the_register(self):
        """No Gap/Partial row in the SoA leaves the register-link column blank."""
        for line in read("docs/statement-of-applicability.md").splitlines():
            if line.startswith("|") and ("Gap" in line or "Partial" in line):
                with self.subTest(row=line[:60]):
                    self.assertIn("#", line,
                                  "Every Gap/Partial control must link a compliance-gaps anchor.")


if __name__ == "__main__":
    unittest.main()
```

---

## 5. Diagram standard

- **Mermaid embedded in the doc** for every flow, call chain and ER — real class and method names,
  placed *after* the prose it illustrates.
- **draw.io** for richer multi-angle drawings: editable XML in `docs/diagrams/src/<name>.drawio`
  (git-diffable) **plus a committed export** plus a registry row in `docs/diagrams/README.md`
  (diagram · source · export · referenced-by · last updated). Reuse an existing export script before
  mandating a new one.
- Legacy images with no editable source get a registry row flagged `source: missing (legacy)` and a
  **redraw-on-next-touch rule**. No big-bang redraw.
- One tool per job, consistently. Automated source↔export↔registry enforcement is a **registered
  gap** (`## DIAG-enforcement`), not a day-one criterion.

---

## 6. Pipeline

**(a) Audit.** Obtain or run a code audit producing finding IDs and dimension grades. Without one you
are inventing the `compliance-gaps.md` seed — the audit *is* the gap analysis.

**(b) Grade.** Apply §3. Write the assessment doc with the before/projected table and a phased
roadmap.

**(c) Spec.** Machine-checkable acceptance criteria in three families:

- `AC-D*` documentation — artefact existence, governance-test-enforced metadata, index routing,
  forbidden version strings, required-section set, diagram standard.
- `AC-S*` security — artefact set, all-controls SoA, HIGHs fixed **with regression tests**, every
  MED/LOW fixed-or-registered with owner and target date, build green.
- `AC-Q*` quality — `$QUALITY_GATE` failing CI, documented targets, `TODO(25010)` convention,
  nine-characteristic coverage table.

Every AC maps to a runnable command. Name the constraints explicitly: untouchable legacy chains,
test profiles, no real secrets, doc version derived from `$VERSION_SOURCE`.

**(d) Plan.** Task shapes that are proven to work:

- **Gaps register first.** `compliance-gaps.md` runs before the doc wave, creating every `## <ID>`
  anchor the wave will link. Anchors are append-only; closure only by commit link.
- **Parallel doc wave under the shared-file protocol.** A doc subagent writes **only its own doc
  file** — no index edit, no changelog edit, no commits. Gap entries append under existing anchors
  only. The parent runs the governance test and commits serially as each task returns. This is the
  rule that makes parallel documentation safe.
- **Doc Task Protocol** on every doc: metadata block at top (title, version · date · status, ToC,
  scope & purpose), closing block at bottom (glossary, traceability, open issues, revision history).
  Verified names in diagrams. No secrets, no forbidden version strings, **no invented facts**.
- **Index rebuilt once at the end**, with the metadata retrofit of surviving docs and the archive
  sweep — then the governance test extension, then the quality gate.
- **TDD for security fixes, one commit per finding**: failing test → fix → green → commit. HIGHs
  first.
- **Background verify bracketing.** Start a full `$BUILD_VERIFY` baseline in the background at task
  1, and another immediately after the last code task. The baseline is the evidence for any
  "pre-existing failure" claim at close.

**(e) Council.** Dispatch **three parallel reviewers** on spec + plan: an **ISO auditor**, a
**security engineer**, and a **delivery pragmatist**. Incorporate every blocking finding *before*
executing. On the reference run the council caught a wrong expected status code, missing chains in a
security fix, three additional secret locations, a missing operations document, and descoped
day-one gold-plating. This stage repeatedly pays for itself.

**(f) Execute.** Subagent-driven: parallel doc tasks, serial code tasks, commit per task, parent owns
shared files.

**(g) Review.** Full branch diff, baseline → HEAD. Apply confirmed findings, commit fixes.

**(h) Close.** Acceptance sweep written as a dated `## Acceptance record <date>` section **in the ISO
hub**, with an evidence command or commit per AC row — so an auditor traces grade → evidence without
reading the plan. Then re-grade with a new column.

---

## 7. Gotchas that generalise

- **Trust the config, not the prose.** A reference implementation's stated thresholds disagreed with
  its actual ruleset XML. Read the machine-readable file.
- **Secret sweeps must include example and template files.** A real password was found in a
  `.env.example` on the reference run. Sweep by grepping for the literal values and expect zero hits
  — including README examples and fixture files.
- **Write regression tests against observed behaviour, not assumed behaviour.** A legacy auth filter
  returned 401 where the framework's documented entry point returns 403. The test that assumes loses.
- **Owner-gated or calculation-heavy packages go straight to the exclusion baseline** with a register
  row — never refactored during a compliance run. They are out of scope by definition, and touching
  them turns a one-day run into a quarter.
- **Static-analysis tooling lags new runtimes.** Pin the analyser runtime explicitly when building on
  a recent language version; bundled defaults crash on newer bytecode.

---

## 8. Adapting this skill to a new repo

1. Fill in §1. Commit it into the assessment doc.
2. Copy §4's test to `scripts/test_docs_governance.py`; set the constants; wire it into
   `$BUILD_VERIFY`.
3. Run §6(a)–(b) to get a grade before writing a single artefact — **the before-grade is the
   evidence that the run worked.**
4. Where a mandated section genuinely does not apply, keep the heading and write
   `Not applicable — [reason]`. Silence is indistinguishable from an omission, which is the whole
   point of the check.
