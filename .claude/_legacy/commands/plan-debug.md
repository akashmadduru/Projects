---
description: Plan the investigation of a bug — root cause, dependency, state flow, component, API, and Pinia analysis — without applying a fix yet.
argument-hint: <bug description or symptom>
agent: polymath
pipeline: true
---

# /plan-debug

Diagnosis-first debugging pipeline. Produces a confirmed root cause before any fix is
proposed or applied.

## Steps

1. Present Model Selection — recommend Sonnet if the symptom plausibly spans more than
   one layer (UI → store → API → DB), else Haiku is acceptable for a narrow, obviously
   localized bug.
2. Present Token Budget — default Medium; High if the bug is intermittent or
   cross-service.
3. Run the pipeline:
   ```
   Debug Planner (root cause, dependency, state flow, component, API, Pinia analysis)
      ↓
   Debug Specialist    [only if Debug Planner's confidence is not High — needs
                         interactive reproduction/instrumentation]
      ↓
   ── report diagnosis, confidence level, and proposed fix location ──
   ```
4. This command stops at diagnosis. To apply the fix, follow up with `/apply-plan`
   (passing the diagnosis as the plan input) or let Polymath continue directly into
   Feature Implementer if the user confirms they want the fix applied now.

## Output

A root-cause diagnosis report (Standard Output Contract + Root Cause narrative from
Debug Planner/Debug Specialist), explicit confidence level, and a precise proposed fix
location.
