---
description: Scan a module, service, or the whole repo for technical debt — dead code, duplication, anti-patterns, code smells — and report findings ranked by severity. Read-only.
argument-hint: [optional: path/module to scope the scan; defaults to whole repo]
agent: polymath
pipeline: false
---

# /find-tech-debt

## Steps

1. Present Model Selection — default Haiku; recommend Sonnet only if scanning the
   whole repository of a medium+ codebase.
2. Present Token Budget — default Medium (scoped scan); recommend High for a full-repo
   scan.
3. Delegate to Technical Debt Analyzer with the scope from `$ARGUMENTS` (or "whole
   repository" if none given).
4. Technical Debt Analyzer runs its full category checklist
   (`agents/optimization/technical-debt-analyzer.md`) and returns a ranked report.
5. Polymath separates findings into "quick wins" and "strategic debt" in the final
   summary, per the analyzer's Best Practices.

## Output

Standard Output Contract with Technical Debt as the primary section; Files Changed
marked N/A (read-only command). Findings are structured for direct hand-off to
`/refactor` or `/optimize` in a follow-up command.
