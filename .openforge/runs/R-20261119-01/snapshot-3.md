# Snapshot 3, run R-20261119-01 (2026-11-20)

All figures come from `metrics.mjs report`, the verdict files and git. Nothing is taken from what agents said about their own work.

| Objective | State | Tokens | Review rounds | 1st review approved | Major+ in review | ACs | Prod lines |
|---|---|--:|--:|:-:|--:|--:|--:|
| F-01 referral pilot | gate PASS | 1,290,555 | 5 | no | 4 | 17/17 | 442 |
| F-03 B-1 client IP | gate PASS | 994,936 | 5 | no | 3 | 13/13 | 572 |
| F-04 O-04 Razorpay refunds | gate FAIL on size only (Owner) | 953,557 | 4 | yes | 0 | 12/12 | 733 |
| F-05 O-10 seller stock | gate PASS | 1,024,803 | 4 | no | 2 | 11/11 | 307 |
| F-06 O-01a auth limits/OTP | gate PASS | 564,617 | 2 | yes | 0 | 9/9 | 279 |
| F-07 O-01b phone enumeration | gate PASS | 429,832 | 1 | yes | 0 | 4/4 | 191 |
| F-08 O-01c token revocation | gate PASS | 425,354 | 1 | yes | 0 | 5/5 | 301 |

- **Done:** 6 of 7 objectives are gate-ready (86%). F-04 is blocked only on the Owner's size decision. Nothing is merged.
- **Tokens:** 5.68M in total, about 0.79M per finished objective. The class-M objectives split from O-01 cost about 0.43–0.56M each, about half the cost of the class-L objectives.
- **Register coverage:** still low. About 12 register items will close when these merge: TD-P8-21, TD-P8-13 (part), TD-P6-30, TD-P6-31, TD-P6-32 (narrowed), TD-P9-04, TD-P9-05, TD-P6-11, TD-P6-20, TD-ADM-02, plus B-1 and B-5. Against 100 in-scope items, that is about 12%.

## Quality signals
- **Escaped defects after merge:** 0 known. Nothing has been merged or deployed, so this proves nothing yet.
- **Review-escaped, QA-caught:** 1. In F-05, both reviewers approved a change that persisted `order_items.seller_id` as NULL. QA's added test exposed it and it was fixed. This is the first evidence that the QA step adds value beyond review.
- **Majors caught by independent review before merge:** 9. The new ones are F-05's two tests that could never fail. Both F-06 reviewers also independently found a lock-renewal DoS. It was minor in severity but real, and it was fixed.
- **Independent agreement:** code and security review found the same main weakness, without seeing each other's work, in F-03, F-04, F-06 and F-08.
- **Seeded-defect tests:** 7/7 caught across 3 runs, with no false positives (unchanged since snapshot 2).
- **Found by the CEO across objectives, missed by every per-objective review:** X-1. F-06 and F-08 give conflicting Redis eviction requirements for the same Redis instance.

## Process failures this period
1. **Undescribed env vars:** engineers added new env vars twice (F-06, F-07) without running the env-reference check. The CEO's verify caught both times.
2. **Lock queue overflow:** with 4 parallel objectives, gradle.sh gave up after its 30-minute lock wait (rc 75). The practical limit on one host is about 3 parallel builds.
3. **Unscoped verify run:** an engineer ran verify without `--changed`, so frontend checks ran for a backend-only change (G-7).
4. **Dependency workaround:** a frontend engineer used a symlinked node_modules, which produced a false tsc failure. The CEO replaced it with a real `npm ci`.
5. **Usage limit:** the 5-hour usage limit was hit for the 3rd time. Each window covers about 2–3 objectives.
6. **Duplicate ADR:** an engineer copied an OpenForge ADR into the repo's own ADR series (G-9).

## Owner decisions queued
1. **Pushes:** all branches are stacked and unmerged. Merge order:
   - F-01;
   - then F-03 and F-05 (both based on F-01);
   - then F-06, F-07 and F-08 (based on F-03). F-06 and F-08 will conflict in the auth config.
2. **F-04 size:** either add an Owner override line, or approve the G-8 gate fix.
3. **B-4 (ALB bypass):** fix it, or accept the risk. It is the release gate for F-03's consumers.
4. **X-1 Redis eviction policy:** either noeviction with alerts, or a separate Redis for the deny-list.
5. **Governance fixes G-5 to G-9.**
6. **Deferred product decisions:**
   - TD-P6-10: should unverified email block orders, and should we add a captcha?
   - TD-SEC-08: the account-linking UX.
7. **Scope:** about 31 portfolio objectives remain, at roughly 0.6–0.8M tokens each, which is about 20–25M tokens. Choose whether to continue the full portfolio or cut it to the security and money paths.

## Recommendation
- **Merge before starting new work.** Every new objective so far stacks on unmerged branches, and the merge and conflict risk grows with each one.
- **Next:** merge F-01, F-03, F-05, F-06, F-07 and F-08 (with F-04 once the size decision is made). Then rebase the next wave onto `dev`.
