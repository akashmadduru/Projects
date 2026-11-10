---
description: Generate unit, integration, and (where warranted) E2E tests for a given file, feature, or recent change set, including edge cases and regression guards.
argument-hint: <target file/feature, or "recent" for the last change set in this session>
agent: polymath
pipeline: false
---

# /generate-tests

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet if the test surface spans
   frontend and backend or covers a complex state machine.
2. Present Token Budget — default Medium.
3. Delegate to Test Planner (`agents/testing/test-planner.md`) with the target from
   `$ARGUMENTS` (or the most recent Feature Implementer output in this session's
   context if "recent" is specified).
4. Test Planner populates `templates/test-plan.md` before writing test code, then writes
   unit/integration/E2E tests matching the project's existing framework and conventions.
5. Test Planner runs the suite via Bash if the environment allows and reports actual
   results.

## Output

New/updated test files, populated test plan matrix, and Standard Output Contract with
Testing section reflecting actual (not assumed) run results.
