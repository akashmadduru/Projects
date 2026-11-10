---
description: Review TailwindCSS usage — design token consistency, utility duplication, responsive/accessible styling. Read-only.
argument-hint: <target component(s)/styles>
agent: tailwind-specialist
pipeline: false
---

# /tailwind-review

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet when auditing design token
   consistency across the whole application.
2. Present Token Budget — default Medium; High for a full design-system audit.
3. Delegate to Tailwind Specialist (`agents/frontend/tailwind-specialist.md`) in
   review-only mode, scoped to `$ARGUMENTS`.
4. Tailwind Specialist checks token usage, utility cluster duplication, responsive
   variants, and accessibility-relevant styling per its Workflow.

## Output

`templates/review-report.md` plus Standard Output Contract.
