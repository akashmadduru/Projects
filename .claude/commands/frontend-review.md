---
description: Full frontend review — Vue/TypeScript, Pinia, and Tailwind specialists combined — for a component, feature, or module. Read-only.
argument-hint: <target component/feature/module>
agent: polymath
pipeline: false
---

# /frontend-review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet for a large or
   cross-cutting frontend review.
2. Present Token Budget — default Medium; High for a whole-app frontend audit.
3. Delegate in parallel to:
   - Vue/TypeScript Specialist (`agents/frontend/vue-typescript-specialist.md`)
   - Pinia Specialist (`agents/frontend/pinia-specialist.md`)
   - Tailwind Specialist (`agents/frontend/tailwind-specialist.md`)
   each in review-only mode, scoped to `$ARGUMENTS`.
4. Merge the three review reports, de-duplicating any overlapping findings (e.g. a
   component-decomposition finding that both Vue/TS and Pinia specialists surface from
   different angles).

## Output

One merged `templates/review-report.md` plus Standard Output Contract.
