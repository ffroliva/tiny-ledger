# Governance baseline — 2026-08-03 (spec §14 step 0)
The registered backlog. Stage 6 fails only on regressions against this list.

**Known-inert (Task 13):** the vendored `.claude/skills/iso-compliance/scripts/test_docs_governance.py`
computes `REPO_ROOT` one directory too shallow for this repo's vendoring depth, so its checks never
actually scan this repo's real `docs/` tree — Stage 6 will report "17 known, 0 new" regardless of
what changes in `docs/`. A green Stage 6 does not currently mean anything; fix is `REPO_ROOT` path
depth in the vendored skill (out of scope here — see the compliance-run roadmap item).

## Failing checks at baseline
SUBFAILED(artefact='CHANGELOG.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/ISO-COMPLIANCE.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/compliance-gaps.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/security-policy.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/secure-coding-standards.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/security-testing.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/risk-assessment.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/risk-register.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/statement-of-applicability.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/incident-response.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/vulnerability-management.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/threat-models/<app>.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/api.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/database.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/deployment.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/testing.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
SUBFAILED(artefact='docs/traceability-matrix.md') .claude/skills/iso-compliance/scripts/test_docs_governance.py::DocsGovernance::test_iso_artefacts_exist
17 failed, 3 passed, 2 skipped in 0.20s
