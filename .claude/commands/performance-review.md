---
description: Review a target for performance issues without applying fixes — a read-only precursor to /optimize.
argument-hint: <target file/module/endpoint>
agent: technical-debt-analyzer
pipeline: false
---

# /performance-review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet if the target is a hot
   path or spans frontend and backend.
2. Present Token Budget — default Medium; High for a full-system performance audit.
3. Delegate to Technical Debt Analyzer, scoped specifically to performance-category
   findings (rendering, bundle size, query performance, caching, scalability).
4. Present findings ranked by severity × blast radius, each with the concrete mechanism
   identified (not vague "could be slow" claims), per that agent's Best Practices.

## Output

Performance-focused findings report (Standard Output Contract, Files Changed N/A). To
apply fixes, follow up with `/optimize` referencing this report.
