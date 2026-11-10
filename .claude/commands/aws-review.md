---
description: Review AWS/Docker/Kubernetes infrastructure against the Well-Architected Framework. Read-only unless explicitly asked to apply fixes.
argument-hint: [optional: specific service/manifest to focus on]
agent: aws-architect
pipeline: false
---

# /aws-review

## Steps

1. Present Model Selection — recommend Sonnet (infrastructure review benefits from
   deeper cross-pillar reasoning).
2. Present Token Budget — default Medium; recommend High for a full deployment-topology
   review.
3. Delegate to AWS Architect (`agents/aws/aws-architect.md`), scoped to `$ARGUMENTS` or
   the whole infrastructure config if unspecified.
4. AWS Architect evaluates against all six Well-Architected pillars and checks
   Dockerfile/K8s manifest/IAM specifics per its Workflow.

## Output

`templates/review-report.md` plus Standard Output Contract with a Well-Architected
pillar breakdown in Reasoning.
