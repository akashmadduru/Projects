---
description: Review Java/Spring code — layering, transactional boundaries, JPA/Hibernate query efficiency, migration safety, Spring configuration. Read-only.
argument-hint: <target module/service>
agent: java-spring-architect
pipeline: false
---

# /java-review

## Steps

1. Present Model Selection — recommend Sonnet by default (transactional boundary and
   persistence reasoning benefits from deeper analysis); Haiku is acceptable for a
   small, contained review.
2. Present Token Budget — default Medium; High for a whole-service or cross-module
   review.
3. Delegate to Java/Spring Architect (`agents/backend/java-spring-architect.md`) in
   review-only mode, scoped to `$ARGUMENTS`.
4. The specialist checks layering (controller/service/repository), `@Transactional`
   boundary correctness, N+1 query risk, Flyway/Liquibase migration safety, exception
   handling centralization, and secrets handling per its Workflow.

## Output

`templates/review-report.md` plus Standard Output Contract.
