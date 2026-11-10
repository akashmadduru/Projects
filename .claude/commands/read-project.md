---
description: Build and report a full inventory of the project's structure, stack, and conventions. Lighter-weight than /analyze — inventory, not judgment.
argument-hint: (none)
agent: polymath
pipeline: false
---

# /read-project

Fast repository inventory. Use this at the start of a session against an unfamiliar
project, or whenever context has been lost and needs rebuilding cheaply.

## Steps

1. Present Model Selection — default Haiku (this is inventory work, Sonnet rarely
   needed even on large repos).
2. Present Token Budget — default Medium; High only if the repo has many independent
   top-level packages/services worth cataloguing individually.
3. Polymath walks the repository top-down (respecting `$TOKEN_BUDGET`) and reports:
   - Detected stack versions (Vue, TypeScript, Pinia, Tailwind, Vite; Python, FastAPI,
     SQLAlchemy, Alembic versions from lockfiles/manifests).
   - Top-level folder structure with a one-line purpose per folder.
   - Entry points (main router, main store, app root component, service bootstrap).
   - Detected conventions (naming, layering) worth noting for future agents to follow.
   - Anything that deviates from this workspace's assumed enterprise stack — flag
     explicitly rather than forcing the assumption.

## Output

A concise inventory report (Standard Output Contract, Files Changed N/A). This report
is intended to be reusable context for subsequent commands in the same session.
