---
name: forge-test
description: OpenForge step 7. QA maps acceptance criteria to tests, adds missing tests, runs verification and writes evidence.md.
---
# Step 7: Test (QA)
Delegate to `forge-qa` with the worktree, work folder and verification commands. If QA added commits,
update `head_sha`. Gate: evidence.md marks every AC with a passing test and the final verification
lines (`gradle_rc=0`, vitest summary) are recorded. A defect report sends the work back to step 6.
