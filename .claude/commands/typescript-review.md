---
description: Review TypeScript typing strictness and correctness across the frontend — implicit any, unsafe casts, discriminated unions for state. Read-only.
argument-hint: <target files/module>
agent: vue-typescript-specialist
pipeline: false
---

# /typescript-review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet when reviewing typing
   across a large shared type surface (e.g. all API response types).
2. Present Token Budget — default Medium.
3. Delegate to Vue/TypeScript Specialist (`agents/frontend/vue-typescript-specialist.md`)
   in review-only mode, scoped to `$ARGUMENTS`, with emphasis specifically on the
   TypeScript checklist items: implicit `any`, unsafe `as` casts, discriminated unions
   for state machines, generic reuse in composables/components.

## Output

`templates/review-report.md` plus Standard Output Contract.
