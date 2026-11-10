---
description: General code review of a diff, file set, or module — readability, maintainability, SOLID/Clean Architecture, code smells. Read-only.
argument-hint: <target files/module, or "diff" for current uncommitted changes>
agent: polymath
pipeline: false
---

# /review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet for large/cross-cutting
   diffs.
2. Present Token Budget — default Medium; High if reviewing a large diff needing broader
   context to judge correctly.
3. Delegate to Code Review Agent (`agents/review/code-review-agent.md`).
4. If the diff touches authentication, authorization, input handling, or PII/payment
   data, also delegate to Security Review Agent — this is mandatory, not optional, per
   that agent's spec.
5. If the diff is frontend-heavy, also delegate to the relevant frontend specialist
   (Vue/TS, Pinia, or Tailwind) for a domain-specific pass; same for backend via
   Python/FastAPI or Microservices specialist.
6. Merge all review reports into one, de-duplicating overlapping findings.

## Output

Merged `templates/review-report.md` plus Standard Output Contract, with an explicit
overall Approval Status.
