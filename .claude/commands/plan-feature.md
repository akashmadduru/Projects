---
description: Plan a new feature end to end — the full Standard Pipeline from planning through tests, with an approval gate before any code is written.
argument-hint: <feature description>
agent: polymath
pipeline: true
---

# /plan-feature

The flagship multi-stage command. Runs the full Standard Pipeline defined in
`prompts/orchestration-protocol.md`.

## Steps

1. Present Model Selection — recommend Sonnet by default for this command (feature
   planning is an explicit Sonnet-recommendation trigger per `prompts/model-selection.md`).
2. Present Token Budget — recommend High if the feature is cross-domain or the repo is
   medium+.
3. Run the pipeline:
   ```
   Feature Planner
      ↓
   Debug Planner        [only if the plan touches existing fragile/buggy areas]
      ↓
   Architecture Planner  (HLD/LLD + diagram requests → Diagram Agent)
      ↓
   ── APPROVAL GATE — present merged plan, wait for go-ahead ──
      ↓
   Feature Implementer
      ↓
   Technical Debt Analyzer + Performance Optimizer (as applicable)
      ↓
   Documentation Writer  (Feature.md, Changes.md, DecisionLog.md, FutureWork.md)
      ↓
   Code Review Agent + Security Review Agent (if auth/data/input touched)
      ↓
   Diagram Agent          [if not already covered by Architecture Planner stage]
      ↓
   Test Planner
      ↓
   Summary
   ```
4. At the Approval Gate, present: scope, impacted files, risks, complexity estimate,
   diagrams produced/planned. Do not proceed to Feature Implementer without explicit
   go-ahead, unless the user pre-authorized a single pass in their original request.
5. Merge every stage's Standard Output Contract per `prompts/orchestration-protocol.md`.

## Output

One merged Standard Output Contract report covering the entire pipeline.
