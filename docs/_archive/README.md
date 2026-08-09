# docs/_archive/

**Delivered working artifacts. Not documentation, and not contract.**

`plans/` holds the implementation plans this repository was built from — agent execution scripts, with
"Step 1: write the failing test", per-task verification steps and commit heredocs. `specs/` holds the
design doc behind one of them. They are archived rather than deleted because the record of *why* a
change was made is worth keeping, and because `../agentic-workflow.md` describes how this project was
built: these are the instructions that account describes.

`reviews/` holds the independent review output those plans were checked against — the `llm-council`
reports on the spec and on Plan 3, and two standalone assessments. They are the evidence behind the
review claims in [`../agentic-workflow.md`](../agentic-workflow.md) §7, kept so those claims can be
checked against their sources rather than believed.

Where a plan and [`../spec.md`](../spec.md) disagree, **`spec.md` wins.** A plan states what was true
when it was written and is never retro-edited.

**These particular files** lived at `docs/superpowers/plans/` and `docs/superpowers/specs/` until
2026-08-06, and the `reviews/` files at `.claude/audits/` and `.superpowers/` until the agent tooling
was un-vendored.

> **`docs/superpowers/` is not gone, and this README implied it was** (corrected 2026-08-08). The
> directory was recreated after the move and holds the **five most recent** plans — step 9's probes
> and tracing, step 10's e2e runtime, the buildpack image, and the TLS/Traefik pass — all delivered,
> none archived here yet. So "read `docs/superpowers/` as `docs/_archive/`" below applies to paths
> written *inside these archived files*, and **not** to a `docs/superpowers/` path you meet anywhere
> else: today that names a real directory with different contents. `../INDEX.md` lists both.

Paths of either form appearing *inside* these files — including inside recorded `git` commands — are left as they were
written rather than rewritten, because falsifying a record of what an agent was told to run is a worse
defect than a stale path. Read `docs/superpowers/` as `docs/_archive/` when you meet one.

The same applies to **CI stage 6 and `scripts/ci/check_docs_governance.py`**, which several of these
plans build, run and cite — one of them carries the script's full source. Both were deleted on
2026-08-06 because the check scanned the wrong directory tree and could not fail; `spec.md` §8.4 has
the reasoning. Nothing here has been back-edited to hide that it once existed.

[`../INDEX.md`](../INDEX.md) lists every plan here newest-first, with what it delivered.
