---
name: forge-implement
description: OpenForge step 6. Delegate tasks to the backend or frontend engineer agents in the objective's worktree, then verify independently.
---
# Step 6: Implement (Engineers)
1. For each task in order (group adjacent tasks for the same role into one brief), delegate to
   `forge-backend-engineer` or `forge-frontend-engineer` with: worktree path (absolute), branch, work
   folder path, the task numbers, and the verification command. Run one engineer at a time.
2. Record `"implementer":"<agent name>"` in state.json (a list if both roles worked).
3. Verify yourself, do not trust the report: in the worktree run
   `node .claude/hooks/forge-verify.mjs --changed --base <base_sha>` and append its output to
   evidence.md under "Implementation verify".
4. Record `head_sha` (`git -C <worktree> rev-parse HEAD`).
Gate: verify exit code 0; all tasks reported done; commits exist on the branch.
