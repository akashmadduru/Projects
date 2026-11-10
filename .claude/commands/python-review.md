---
description: Review Python/FastAPI/SQLAlchemy/Alembic code — layering, query efficiency, migration safety, async correctness. Read-only.
argument-hint: <target router/service/module>
agent: python-fastapi-specialist
pipeline: false
---

# /python-review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet when reviewing across the
   full router→service→repository→model chain or a non-trivial migration.
2. Present Token Budget — default Medium; High for a whole-service review.
3. Delegate to Python/FastAPI Specialist (`agents/backend/python-fastapi-specialist.md`)
   in review-only mode, scoped to `$ARGUMENTS`.
4. Specialist checks layering, Pydantic/ORM boundary discipline, N+1 query patterns,
   migration safety, and async correctness per its Workflow.

## Output

`templates/review-report.md` plus Standard Output Contract.
