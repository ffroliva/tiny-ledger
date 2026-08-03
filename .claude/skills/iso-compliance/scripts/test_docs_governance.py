"""ISO documentation governance.

Enforces the ISO/IEC/IEEE 15289 required-section set, artefact presence, and the
registration of quality debt. Python stdlib `unittest` only — no third-party
dependencies — so it runs in any repository regardless of primary language.

Wire this into your build (`$BUILD_VERIFY` in the skill's §1 adapter). A check
that only runs when someone remembers is not a control.

    python -m unittest scripts.test_docs_governance -v

Adapt only the CONFIGURATION block below.
"""

from __future__ import annotations

import unittest
from pathlib import Path

# --------------------------------------------------------------------------
# CONFIGURATION — the only part that changes per repository (skill §1 adapter)
# --------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[1]

APP_NAME = "<app>"                          # names docs/threat-models/<app>.md
SOURCE_ROOTS = ["src"]                      # roots scanned for TODO(25010)
SOURCE_SUFFIXES = {".java", ".py", ".ts", ".tsx", ".go", ".kt", ".cs", ".rb"}
FORBIDDEN_VERSION_STRINGS = ("-SNAPSHOT",)  # e.g. ".dev" for Python, "-alpha"

ISO_ARTEFACTS = [
    "CHANGELOG.md",
    "docs/ISO-COMPLIANCE.md",
    "docs/compliance-gaps.md",
    "docs/security-policy.md",
    "docs/secure-coding-standards.md",
    "docs/security-testing.md",
    "docs/risk-assessment.md",
    "docs/risk-register.md",
    "docs/statement-of-applicability.md",
    "docs/incident-response.md",
    "docs/vulnerability-management.md",
    f"docs/threat-models/{APP_NAME}.md",
    "docs/api.md",
    "docs/database.md",
    "docs/deployment.md",
    "docs/testing.md",
    "docs/traceability-matrix.md",
]

# Working papers, routers and registries are exempt from the 15289 section set.
UNGOVERNED_PARTS = {
    "superpowers", "_archive", "profiling", "diagrams", "generated", "source", "adr",
}
UNGOVERNED_NAMES = {"INDEX.md", "README.md"}

ISO_REQUIRED_SECTIONS = (
    "**Version:**",
    "## Table of contents",
    "## Scope & purpose",
    "## Glossary & acronyms",
    "## Traceability",
    "## Open issues / known gaps",
    "## Revision history",
)

# --------------------------------------------------------------------------


def read(rel: str | Path) -> str:
    return (REPO_ROOT / rel).read_text(encoding="utf-8")


def governed_docs():
    """Every docs/**/*.md except working papers, routers and registries."""
    docs = REPO_ROOT / "docs"
    if not docs.is_dir():
        return
    for path in sorted(docs.rglob("*.md")):
        rel = path.relative_to(REPO_ROOT)
        if UNGOVERNED_PARTS.intersection(rel.parts) or rel.name in UNGOVERNED_NAMES:
            continue
        yield rel


def main_sources():
    for root in SOURCE_ROOTS:
        base = REPO_ROOT / root
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            if path.suffix in SOURCE_SUFFIXES and "test" not in path.parts:
                yield path


class DocsGovernance(unittest.TestCase):
    """ISO 15289 / 27001 / 25010 documentation governance."""

    def test_iso_artefacts_exist(self):
        """Every artefact in the skill's §2 checklist exists.

        CHANGELOG.md is existence-checked only: it follows Keep a Changelog,
        not the 15289 section block.
        """
        for name in ISO_ARTEFACTS:
            with self.subTest(artefact=name):
                self.assertTrue(
                    (REPO_ROOT / name).is_file(),
                    f"ISO artefact {name} must exist (see docs/ISO-COMPLIANCE.md).",
                )

    def test_governed_docs_carry_required_sections(self):
        """Every governed doc carries the full 15289 section set.

        'Not applicable — [reason]' under a heading is fine; a missing heading
        is not, because silence is indistinguishable from an oversight.
        """
        for rel in governed_docs():
            content = read(rel)
            for marker in ISO_REQUIRED_SECTIONS:
                with self.subTest(doc=str(rel), marker=marker):
                    self.assertIn(
                        marker, content,
                        f"{rel} is missing required section marker {marker!r}.",
                    )

    def test_no_forbidden_version_strings(self):
        """Governed docs and the changelog cite no pre-release versions."""
        targets = list(governed_docs())
        changelog = Path("CHANGELOG.md")
        if (REPO_ROOT / changelog).is_file():
            targets.append(changelog)
        for rel in targets:
            for bad in FORBIDDEN_VERSION_STRINGS:
                with self.subTest(doc=str(rel), string=bad):
                    self.assertNotIn(
                        bad, read(rel),
                        f"{rel} must not reference {bad} versions.",
                    )

    def test_quality_todos_are_registered(self):
        """Every TODO(25010) in main sources appears in the gaps register."""
        gaps_path = REPO_ROOT / "docs" / "compliance-gaps.md"
        if not gaps_path.is_file():
            self.skipTest("docs/compliance-gaps.md not yet created")
        gaps = gaps_path.read_text(encoding="utf-8")
        for src in main_sources():
            if "TODO(25010)" in src.read_text(encoding="utf-8", errors="ignore"):
                with self.subTest(source=src.name):
                    self.assertIn(
                        src.stem, gaps,
                        f"{src.name} carries a TODO(25010) but is not registered "
                        "in docs/compliance-gaps.md.",
                    )

    def test_soa_gap_rows_link_the_register(self):
        """No Gap/Partial control leaves its register-link column blank."""
        soa = REPO_ROOT / "docs" / "statement-of-applicability.md"
        if not soa.is_file():
            self.skipTest("docs/statement-of-applicability.md not yet created")
        for line in soa.read_text(encoding="utf-8").splitlines():
            if line.startswith("|") and ("Gap" in line or "Partial" in line):
                with self.subTest(row=line[:60]):
                    self.assertIn(
                        "#", line,
                        "Every Gap/Partial control must link a compliance-gaps anchor.",
                    )


if __name__ == "__main__":
    unittest.main()
