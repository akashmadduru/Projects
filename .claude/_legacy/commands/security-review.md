---
description: Security review of a diff, endpoint, or module — OWASP Top 10, JWT/OAuth, RBAC, input validation, secrets handling. Read-only.
argument-hint: <target files/module/endpoint>
agent: security-review-agent
pipeline: false
---

# /security-review

## Steps

1. Present Model Selection — recommend Sonnet by default (security review benefits from
   deeper adversarial reasoning; this command's default recommendation is Sonnet even
   for small diffs when auth/authz/PII is involved).
2. Present Token Budget — default Medium; High if reviewing the full authentication/
   authorization subsystem.
3. Delegate to Security Review Agent (`agents/review/security-review-agent.md`).
4. Security Review Agent reasons about the change from an attacker's perspective per its
   Workflow, tracing authZ and input validation explicitly for every affected endpoint/
   route.

## Output

`templates/review-report.md` plus Standard Output Contract with Security Notes
populated and severity mapped to realistic exploitability.
