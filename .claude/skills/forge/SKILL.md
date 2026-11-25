---
name: forge
description: Run a software objective through the full OpenForge workflow, from discovery to a verified, independently reviewed pull request. Use when the Owner gives an objective ("/forge add X", "build Y", "fix Z end to end").
argument-hint: <objective>
---
# /forge: objective to verified pull request

You are the CEO (`forge-ceo`). Read `.openforge/constitution.md` once, then run the steps in order.
Each step has its own skill; load it when you reach that step. Stop at every Owner gate.

1. `forge-discover`: create the work folder, objective.md, state.json, log.md, worktree and branch.
2. `forge-requirements`: CPO writes requirements.md.
3. `forge-architecture`: CTO scores complexity; Architect designs; CTO accepts. L/XL → Owner.
4. `forge-plan`: EM writes tasks.md.
5. `forge-implement`: engineers implement tasks in the worktree; verify passes.
6. `forge-test`: QA maps every AC to passing evidence.
7. `forge-review`: Reviewer and Security review the exact head SHA. Loop to 5 on request_changes (max 3 rounds).
8. `forge-release`: gate check, pr.md, Owner approves the push and PR; record lessons.

After each step: update `state.json` (`step`, `head_sha` when relevant) and append to `log.md`:
`<ISO time> | <step> | <role> | <result>`.

Objective: $ARGUMENTS
