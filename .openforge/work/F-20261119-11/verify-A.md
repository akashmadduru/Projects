# verify-A (PR-A gate)
Command (from main checkout): FORGE_GRADLE_LOCK_WAIT=5400 node .claude/hooks/forge-verify.mjs --changed --base 820e5a8d13872308017dfafbc81eaa048af59a10 --dir .openforge/worktrees/F-20261119-11
Output:
forge-verify: 7 changed file(s) ... (base 820e5a8d1387)
  PASS gradle :services:cart-service:cart-service-app:test :services:order-service:order-service-app:test (exit 0)
      BUILD SUCCESSFUL in 15m 20s
      gradle_rc=0
forge-verify: PASS (1 checks)
Tests: order-service 122 run, 0 failures; cart-service 8 run, 0 failures.
Run at commit 42007cf; the PR-A head after it differs only by a comment re-wrap in CartResponseContractTest.
Deploy note: PR-A must be live before PR-C.

## CEO verify at a9265001af1848fe6a0808acb48273385c08f811 (PR-A head, 2026-11-21)
`FORGE_GRADLE_LOCK_WAIT=5400 node .claude/hooks/forge-verify.mjs --changed --base 820e5a8d13872308017dfafbc81eaa048af59a10 --dir .openforge/worktrees/F-20261119-11`
```
forge-verify: 7 changed file(s) in C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-11 (base 820e5a8d1387)
  PASS gradle :services:cart-service:cart-service-app:test :services:order-service:order-service-app:test (exit 0)
      gradle.sh: waiting for another build (pid 17189) to finish
      BUILD SUCCESSFUL in 1m 11s
      gradle_rc=0
forge-verify: PASS (1 checks)
rc=0
```
- Gradle reused results from the engineer's run at the same SHA (same inputs). Production lines 820e5a8..a926500: 67 (CEO recount). Worktree clean.
