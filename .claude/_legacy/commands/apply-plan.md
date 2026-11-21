---
description: Apply a previously produced plan (from /plan-feature or /plan-debug) as production-ready code, then run optimization, documentation, review, and tests.
argument-hint: [optional: reference to which plan, if multiple are in context]
agent: polymath
pipeline: true
---

# /apply-plan

Implementation-forward command for when planning already happened (in this session or
a prior one) and the user is ready to ship it.

## Steps

1. Present Model Selection — recommend Sonnet if the plan spans frontend and backend or
   touches shared/core code.
2. Present Token Budget — default Medium; High if the plan's impacted-files list is
   large.
3. Confirm the plan being applied (re-state its scope from context) before proceeding —
   if no plan is found in context, halt and direct the user to `/plan-feature` or
   `/plan-debug` first rather than guessing scope.
4. Run the post-approval portion of the Standard Pipeline:
   ```
   Feature Implementer
      ↓
   Technical Debt Analyzer + Performance Optimizer (as applicable)
      ↓
   Documentation Writer
      ↓
   Code Review Agent + Security Review Agent (if applicable)
      ↓
   Test Planner
      ↓
   Summary
   ```

## Output

Merged Standard Output Contract covering implementation through tests.
