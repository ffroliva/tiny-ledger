# Vendored agent skills

Skills are vendored (copied, not submoduled) so the repository is self-contained and the exact
version in use is reviewable in-tree. Record any upgrade here.

| Skill | Source | Version | Licence | Vendored |
|---|---|---|---|---|
| `dr-jskill` | https://github.com/jdubois/dr-jskill | main @ 2026-07-30 | Apache-2.0 | 2026-08-03 |
| superpowers (14 skills) | https://github.com/obra/superpowers | 6.2.0 | MIT | 2026-08-03 |
| `iso-compliance` | https://github.com/ffroliva/iso-compliance | 1.0.0 | MIT | consumed as a plugin, not vendored |

## superpowers skills in use

`brainstorming` · `writing-plans` · `executing-plans` · `subagent-driven-development` ·
`dispatching-parallel-agents` · `requesting-code-review` · `receiving-code-review` ·
`test-driven-development` · `systematic-debugging` · `verification-before-completion` ·
`finishing-a-development-branch` · `using-git-worktrees` · `using-superpowers` · `writing-skills`

The SDD pipeline in `docs/agentic-workflow.md` §2 is these skills in sequence.

## Upgrading

Re-clone the upstream repo, copy `skills/*` over the vendored copies, update the table above, and
re-run the full test suite. Vendoring is deliberate: an agent skill that changes underneath a
compliance run invalidates the evidence trail.
