---
description: Apply targeted performance optimizations to a specific area, grounded in an identified mechanism (not speculative tuning).
argument-hint: <target area, or "auto" to let Technical Debt Analyzer identify targets first>
agent: polymath
pipeline: true
---

# /optimize

## Steps

1. Present Model Selection — recommend Sonnet if the target spans frontend and backend
   or is a hot path (high traffic/large data volume).
2. Present Token Budget — default Medium; High for full-system performance passes.
3. If `$ARGUMENTS` is a specific target: delegate directly to Performance Optimizer.
   If `$ARGUMENTS` is "auto" or unspecified: run Technical Debt Analyzer first, scoped
   to performance-category findings, then hand its ranked findings to Performance
   Optimizer.
4. Performance Optimizer implements the smallest sufficient fix per finding, per its own
   Workflow (`agents/optimization/performance-optimizer.md`).
5. Run Test Planner to confirm no regression was introduced by the optimization.
6. Run Code Review Agent.

## Output

Standard Output Contract with a fully populated Performance Notes section quantifying
expected improvement per change.
