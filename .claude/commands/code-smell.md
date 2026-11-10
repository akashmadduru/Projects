---
description: Scan for code smells specifically — dead code, duplication, long functions, deep nesting, magic values, inconsistent error handling. Read-only.
argument-hint: [optional: path/module to scope the scan]
agent: technical-debt-analyzer
pipeline: false
---

# /code-smell

## Steps

1. Present Model Selection — default Haiku.
2. Present Token Budget — default Medium; High for a full-repo pass.
3. Delegate to Technical Debt Analyzer, scoped to the "General code smells" and
   "Duplicated logic" / "Dead code" categories specifically (a narrower slice than the
   full `/find-tech-debt` scan).
4. Report findings with concrete evidence and remediation per that agent's Workflow.

## Output

Code-smell findings report (Standard Output Contract, Files Changed N/A).
