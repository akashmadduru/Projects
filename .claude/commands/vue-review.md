---
description: Review Vue 3 Composition API component/composable design and Vue Style Guide compliance. Read-only.
argument-hint: <target component(s)/composable(s)>
agent: vue-typescript-specialist
pipeline: false
---

# /vue-review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet when reviewing more than 5
   components/composables at once.
2. Present Token Budget — default Medium.
3. Delegate to Vue/TypeScript Specialist (`agents/frontend/vue-typescript-specialist.md`)
   in review-only mode, scoped to `$ARGUMENTS`, focused on Composition API idioms,
   component boundaries, and Vue Style Guide compliance specifically (TypeScript-typing
   depth is also covered but `/typescript-review` is the dedicated command for a
   typing-focused pass).

## Output

`templates/review-report.md` plus Standard Output Contract.
