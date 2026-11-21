---
name: forge-release
description: OpenForge steps 9–11. Run the PR gate, write pr.md, get the Owner's approval to push and open the PR, and record lessons and decisions.
---
# Steps 9–11: PR, release, learn
0. Commit the work record onto the branch so CI can check it: copy `.openforge/work/<id>/` into the
   worktree at the same path and commit it alone as `forge(<id>): work record`. (The gate accepts
   commits after the reviewed SHA only if they touch nothing but this folder.)
1. Run `node .claude/hooks/forge-gate.mjs <id>`. It checks: branch name, reviews approve the current
   head SHA, reviewer ≠ implementer, evidence covers every AC, and the diff is within the class limit.
   Fix what it reports; never bypass it.
2. Write `pr.md`: title `forge(<id>): <summary>`; sections Summary, Acceptance criteria (with evidence),
   Design (link), Complexity class, Review verdicts (reviewer, SHA), Risks and rollback, Test plan.
3. Ask the Owner to approve the push. Only after an explicit yes: `git -C <worktree> push -u origin <branch>`
   and give the Owner the PR text (or open it if a GitHub tool is available). The merge is the Owner's.
4. Learn: add 1–3 entries to `.openforge/knowledge/lessons.md` (what went wrong or right, and the rule
   now followed), and note any decision in `.openforge/knowledge/decisions/`. Set state.json step `done`.
