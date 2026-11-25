---
description: Full backend review — Python/FastAPI and Microservices specialists combined — for a service, router, or module. Read-only.
argument-hint: <target service/router/module>
agent: polymath
pipeline: false
---

# /backend-review

## Steps

1. Present Model Selection — recommend Sonnet by default (backend layering and
   cross-service reasoning benefit from deeper analysis).
2. Present Token Budget — default Medium; High for a whole-service or cross-service
   review.
3. Delegate in parallel to:
   - Python/FastAPI Specialist (`agents/backend/python-fastapi-specialist.md`)
   - Microservices Specialist (`agents/backend/microservices-specialist.md`), only if
     the target involves cross-service communication or event contracts
   each in review-only mode, scoped to `$ARGUMENTS`.
4. Merge review reports, de-duplicating overlapping findings.

## Output

One merged `templates/review-report.md` plus Standard Output Contract.
