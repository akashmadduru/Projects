# Snapshot 2, run R-20261119-01 (2026-11-19)

All figures come from `metrics.mjs report`, based on harness usage records, verdict files and git. None are agent self-reports.

| Objective | State | Tokens | Review rounds | Approved at 1st review | Major+ found by review | ACs | Prod lines |
|---|---|--:|--:|:-:|--:|--:|--:|
| F-01 referral pilot | gate PASS | 1,290,555 | 5 | no | 4 | 17/17 | 442 |
| F-03 B-1 client IP | gate PASS | ~1,007,000 | 5 | no | 3 | 13/13 | 572 |
| F-04 O-04 Razorpay refunds | gate FAIL on size only (Owner decision) | 953,557 | 4 | yes | 0 | 12/12 | 733 |
| F-05 O-10 seller stock | slice 1 approved, fix round + slice 2 running | 489,373 | 1 | n/a | 0 | 3/11 | 165 |

- **Run spend since start, excluding the pilot:** about 2.45M tokens. Total including the pilot is about 3.73M.
- **Where tokens go (F-03/F-04):** planning 22%, implementation 27%, QA 5%, review 46%. Review is the largest cost because each fix round triggers a reviewer re-review and a security re-review.

## Honest projection
- My snapshot-1 projection of 0.45–0.5M tokens per objective was wrong by about 2x. Class-L objectives cost about 1M each. A class-M objective is tracking at about 0.6–0.7M.
- 38 portfolio objectives therefore project to about **25–30M tokens**, against the portfolio's 6–15M estimate. At the observed rate of one 5-hour usage window per 2–3 objectives, that is about 15 windows of wall time.
- The 90% target (90 of 100 register items) is not reachable in this run without a scope cut. Register items closed so far by gate-passed objectives: F-01 1, F-03 1 (B-1 plus TD-P8-21 and TD-P8-13 partial). F-04, once merged, closes TD-P6-30 and TD-P6-31 and narrows TD-P6-32.

## Quality signals
- **Escaped defects:** 0 known. No merge has happened yet, so nothing has been tested in production.
- **Majors caught by independent review before merge:** 7.
  - F-01: 4.
  - F-03: 3, including a real XFF multi-line bypass and two auth fallback spoofing paths.
- **Seeded-defect tests:** 7/7 caught across 3 tests.
  - F-03 and F-04: 5/5, but the tests were the oracle.
  - F-05: 2/2, with no test oracle. One seed contradicted the design; the other was found by reasoning across services. The F-05 test is the most credible of the three.
- **Base defect found:** order-service tests did not compile at the run base (B-5). Every earlier "verify" that skipped order-service was blind to it.

## Process failures (OpenForge weaknesses)
1. **G-7:** `forge-verify --changed` defaults its base to origin/dev. The CEO ran it without `--base`, which checked 10k files and gave a false FAIL.
2. **G-8:** forge-gate cannot check stacked PRs. A class-L objective split under 600 lines per part, as the constitution asks, still fails the gate (F-04).
3. **QA marked ACs "covered" by reading.** The gate correctly refused this, and the ACs were re-verified against JUnit XML. The gate worked as designed; the QA brief was too loose.
4. **Read-only reviewers were told to write files**, which wasted a turn (CEO brief error).
5. **Write-only roles cannot append.** The CTO tried to "append" by overwriting design.md with nothing. The permission system blocked it, so there was no damage.
6. **Gradle cache.** Two verify runs were satisfied from the Gradle cache. The test outcome is valid for the same SHA, but timing tests were re-run uncached to be sure (F-04: PASS).
7. **Recoveries: 8 total.** These include the restart clean-up, the build lock, an engineer writing outside its worktree, and the items above.

## Owner decisions queued
1. F-04 size gate: write an `Owner override` line, or approve the G-8 gate fix.
2. B-4 (ALB bypass): this is a release gate for F-03's consumers. Fix it, or accept the risk.
3. Governance fixes G-5, G-6, G-7, G-8.
4. Pushes: F-01 and F-03 are gate-ready. They are stacked: F-03 sits on F-01.
5. Scope: continue all 38 objectives (about 25–30M tokens), or cut to the security and money paths (about 12 objectives, about 10M).
