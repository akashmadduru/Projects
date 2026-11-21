# CLAUDE.md

This repository runs **OpenForge**: Claude Code organised as a small engineering organisation. The full
rules are in `.openforge/constitution.md`; read it before running an objective.

## Non-negotiable rules
- The human Owner approves: L/XL designs, every push, merge and release, and any change to
  `CLAUDE.md`, `.openforge/constitution.md`, `.claude/settings.json`, `.claude/hooks/`, `.github/workflows/`.
- Whoever writes a change never reviews or approves it. Reviews are valid only for the commit SHA they name.
- Work only on `forge/<id>-<slug>` branches in a worktree. Never commit to `main`/`master`/`dev`,
  never force-push, never push without the Owner.
- "Done" means `node .claude/hooks/forge-verify.mjs --changed` passed and its output is recorded.
- Keep changes minimal: no new service, datastore or vendor without an ADR and Owner approval.

## How to run an objective
Use the `/forge <objective>` skill. The main session acts as CEO (`forge-ceo`) and delegates each step
to the role agents in `.claude/agents/forge/`. Each objective gets `.openforge/work/<id>/`.

## Project facts
- Monorepo: Spring Boot 4 / Java 25 / Gradle services in `services/`, shared libs in `libs/`,
  React storefront `apps/product-catalog`, Vue admin `apps/admin-product-catalog`.
- Java builds and tests run in docker (see `.openforge/knowledge/project.md`); run one build at a time.
- Tech-debt register: `docs/techdebt/README.md`; plan: `docs/techdebt/remediation-plan.md`.
- More detail for agents: `.openforge/knowledge/project.md`.
