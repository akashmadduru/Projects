---
description: Analyze the repository or a specific area of it and report architecture, conventions, and risks — no changes made.
argument-hint: [optional: path or module to focus on]
agent: polymath
pipeline: false
---

# /analyze

Read-only repository analysis. Use this to understand an unfamiliar codebase or module
before planning work against it.

## Steps

1. Present Model Selection (`prompts/model-selection.md`) — default Haiku; recommend
   Sonnet if the repository is medium+ size or `$ARGUMENTS` names a broad scope (e.g.
   "the whole backend").
2. Present Token Budget (`prompts/token-budget.md`) — default Medium; recommend High for
   whole-repository or cross-service analysis.
3. Polymath triages the target scope (`$ARGUMENTS`, or the whole repo if none given):
   - Frontend: folder structure, routing map, Pinia store inventory, component
     conventions, build config.
   - Backend: service/module inventory, router→service→repository→model layering
     check, migration history shape, event/queue usage.
   - Cross-cutting: auth mechanism, RBAC model, testing setup, CI config if present.
4. Delegate targeted deep-reads to the relevant specialist agent(s) only where surface-
   level triage isn't sufficient (e.g. Pinia Specialist for store architecture
   specifics).
5. Produce a findings report: what exists, what conventions are established, what looks
   inconsistent or fragile, and what a newcomer should know before changing this area.

## Output

Standard Output Contract (`prompts/output-format.md`), with "Files Changed" marked N/A
(read-only command) and "Reasoning" carrying the bulk of the analysis narrative.
