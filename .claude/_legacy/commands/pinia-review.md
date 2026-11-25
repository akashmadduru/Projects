---
description: Review Pinia store design and usage — boundaries, reactivity pitfalls, cross-store coupling. Read-only.
argument-hint: <target store(s) or feature>
agent: pinia-specialist
pipeline: false
---

# /pinia-review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet when reviewing a "god
   store" refactor or cross-store communication redesign.
2. Present Token Budget — default Medium.
3. Delegate to Pinia Specialist (`agents/frontend/pinia-specialist.md`) in review-only
   mode, scoped to `$ARGUMENTS`.
4. Pinia Specialist checks store boundaries, mutation discipline, `storeToRefs` usage,
   circular dependencies, and async state shape consistency per its Workflow.

## Output

`templates/review-report.md` plus Standard Output Contract.
