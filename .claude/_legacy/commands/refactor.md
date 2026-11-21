---
description: Refactor a specific file, component, module, or pattern without changing external behavior. Includes a before-you-start risk check.
argument-hint: <target file/module/pattern to refactor>
agent: polymath
pipeline: true
---

# /refactor

Behavior-preserving structural improvement. Distinct from `/optimize` (which targets
performance) and `/plan-feature` (which changes behavior) — a refactor's success
criterion is "external behavior unchanged, internal quality improved."

## Steps

1. Present Model Selection — recommend Sonnet for refactors touching more than ~10
   files or a core abstraction; Haiku for small, contained refactors.
2. Present Token Budget — recommend High if the refactor touches a widely-depended-on
   abstraction (need to see all call sites).
3. Polymath (or the relevant specialist — Vue/TS, Pinia, Python/FastAPI,
   Microservices) enumerates every call site of the code being refactored before
   touching anything.
4. Produce a short before/after plan: what changes structurally, explicit confirmation
   that no external behavior/contract changes (or, if it must, flag that this is
   actually a breaking change and should go through `/plan-feature` instead).
5. Apply the refactor via Feature Implementer, respecting `prompts/coding-standards.md`.
6. Run Technical Debt Analyzer against the refactored area to confirm the refactor
   actually reduced debt rather than moving it.
7. Run Code Review Agent.
8. Run Test Planner to confirm existing tests still hold and add coverage for any
   previously-untested code newly exposed by the refactor.

## Output

Standard Output Contract, with Architecture Decisions explaining the refactor's
rationale and confirming behavior preservation.
