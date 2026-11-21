# Session report: tech debt, OpenForge build, OpenForge pilot

Date: 2026-11-19. Author: CEO (main session). Sources: agent completion notices (tokens, tool calls,
duration), `.openforge/work/F-20261119-01/log.md`, register and plan diffs. Nothing was committed or
pushed by agents; the Owner committed the tech-debt work (`22cbc87`, `778d102`).

**Measurement limits.** Token figures are subagent tokens as reported per run (for resumed agents, per
resume segment). The main session's own tokens are not reported by the harness and are not included.
"Duration" is agent wall time; runs were mostly sequential.

---

## 1. Action log (condensed)

### 1a. Tech debt (Owner prompt: "go-ahead and solve all tech debts, I will be afk take decisions on your own")
| # | Actor | Action | Result |
|---|---|---|---|
| 1 | CEO | Purge job for one-time tokens; payment re-reporter alert; JobLock on account deletion | TD-P6-19, TD-P6-13 closed |
| 2 | CEO | UUID-subject JWT validator in 12 services + gateway (instead of 69 call sites) | TD-SEC-12 closed |
| 3 | CEO | Audited admin review delete; customer endpoints author-only | TD-ADM-05 closed |
| 4 | CEO | HMAC for OTP codes (key derived from AUTH_DATA_KEY); first-order coupon check | TD-P6-09, TD-P6-16 closed |
| 5 | CEO | Same-origin token rule (storefront), API-origin guard (admin), Node 26 storage fix | TD-SEC-11, TD-ADM-11, TD-AI-09 closed |
| 6 | CEO | Closed stale items (formatting, payment build) | TD-P6-29, TD-TEST-07, TD-AI-06, TD-ADM-12 |
| 7 | Agent (fork), batch 1 | 23 items closed, 1 narrowed | register 163 → 129 |
| 8 | Agent, batch 2 | 9 items closed, 1 narrowed; first payment-service Testcontainers suite | register → 120 |
| 9 | Agent, batch 3 | Coins and seller-ledger test bases + 9 money-path tests | 0 items closed (test coverage) |
| 10 | Agent, batch 4 | Stopped by the 5-hour usage limit | paused |

### 1b. OpenForge build (Owner prompt: the OpenForge assignment, then "now re-run everything use ceo agent from top")
| Milestone | Delivered | Verified by |
|---|---|---|
| M1 | Constitution, CLAUDE.md, permissions, legacy config moved to `_legacy` | approval prompt on settings.json |
| M2 | 10 role agents (CEO, CPO, CTO, Architect, EM, 2 engineers, QA, Reviewer, Security) | used in the pilot |
| M3 | `/forge` + 8 step skills | loaded live |
| M4 | 5 hook/check scripts (guard, protect, subagent-stop, verify, gate) | 5/5 regression tests; 1 live bypass found and fixed |
| M5 | Worktree flow, `forge-gate.yml`, PR template, branch-protection runbook | YAML parse, gate fixtures |
| M6 | Pilot F-20261119-01 (below) | stopped at the review gate |

### 1c. Pilot F-20261119-01: referral abuse checks (TD-P8-13)
| Step | Role | Result |
|---|---|---|
| Discover | CEO | objective + 4 assumptions; **assumption 3 later proved wrong** |
| Requirements | CPO | 17 ACs, PASS first time |
| Complexity | CTO | class M (5) |
| Design | Architect, then CTO | design + ADR; ACCEPT with 5 conditions |
| Plan | EM | 13 tasks, all ACs covered |
| Implement | Backend engineer ×2 runs | 66 tests pass; CEO verify PASS |
| Test | QA | 17/17 ACs evidenced, +2 tests |
| Review r1 | Reviewer / Security | approve / **request_changes**: leftmost X-Forwarded-For is forgeable |
| Fix r2 + refactor | Engineer ×2 runs | right-hand XFF, `InetAddress.ofLiteral`, 20-row cap, fewer queries |
| Review r2 | Reviewer / Security | approve / **request_changes**: Kubernetes NATIVE header rewrite |
| Fix r3 | Engineer | strategy `none`, embedded-Tomcat test that fails without the fix |
| Review r3 | Reviewer / Security | approve / **request_changes**: Gateway 5.0.2 strips X-Forwarded-* |
| Gate | CEO | FAIL (security verdict; 1,911 lines > 600). **Stopped for the Owner** (3-round rule) |

---

## 2. Efficiency benchmarks

### 2a. Pilot agent runs
| Run | Role | Tokens | Tool calls | Time | First-pass outcome |
|---|---|---:|---:|---:|---|
| 1 | CPO | 15,696 | 6 | 27 s | pass |
| 2 | CTO (score) | 15,810 | 6 | 15 s | pass |
| 3 | Architect | 67,525 | 47 | 3.1 min | pass |
| 4 | CTO (design) | 23,334 | 8 | 27 s | accept + conditions |
| 5 | EM | 28,039 | 6 | 60 s | pass |
| 6 | Engineer, tasks 1–5 | 87,183 | 52 | 20.1 min | pass (env issue fixed by CEO) |
| 7 | Engineer, tasks 7–11 | 93,188 | 34 | 10.8 min | pass |
| 8 | QA | 49,161 | 17 | 5.6 min | pass, +2 tests |
| 9 | Reviewer r1 | 76,741 | 9 | 1.5 min | approve |
| 10 | Security r1 | 76,425 | 12 | 1.9 min | request_changes (major) |
| 11 | Engineer r2 | 90,078 | 43 | 22.6 min | pass; broke the one-build rule |
| 12 | Engineer refactor | 38,112 | 15 | 8.4 min | pass |
| 13 | QA r2 | 34,405 | 9 | 32 s | pass |
| 14 | Reviewer r2 | 82,363 | 14 | 1.4 min | approve |
| 15 | Security r2 | 84,077 | 18 | 2.3 min | request_changes (major) |
| 16 | Engineer r3 | 68,356 | 45 | 19.1 min | pass; test proven to fail without fix |
| 17 | Reviewer r3 | 55,549 | 13 | 1.7 min | approve |
| 18 | Security r3 | 64,718 | 27 | 4.5 min | request_changes (major) |
| | **Total** | **1,050,760** | **381** | **105.7 min** | |

### 2b. Where the tokens went
| Phase | Tokens | Share |
|---|---:|---:|
| Planning (CPO, CTO ×2, Architect, EM) | 150,404 | 14% |
| Implementation (5 engineer runs) | 376,917 | 36% |
| QA (2 runs) | 83,566 | 8% |
| Review (6 runs) | 439,873 | 42% |
| **Rework** (everything after review round 1) | **517,658** | **49%** |

### 2c. Unit costs (pilot)
| Measure | Value |
|---|---:|
| Tokens per acceptance criterion (17) | 61,800 |
| Tokens per production line (~426 changed) | 2,470 |
| Tokens per test (77) | 13,650 |
| Test lines : production lines | 3.3 : 1 |
| Planning first-pass rate | 5 of 5 (100%) |
| Implementation verify first-pass rate | 5 of 5 (100%) |
| Security review first-pass rate | 0 of 3 (each round found a new major) |
| Reviewer (code) approvals | 3 of 3 |
| Majors found by independent review | 3 (all one root cause: client-IP provenance) |

### 2d. Tech-debt agent runs (same session, Owner-delegated)
| Run | Tokens | Tool calls | Time | Items closed | Tokens / item |
|---|---:|---:|---:|---:|---:|
| Batch 1 (fork, inherited context) | 415,881 | 175 | 51 min | 23 (+1 narrowed) | 18,100 |
| Batch 2 | 517,106 | 100 | 25 min | 9 (+1 narrowed) | 57,500 |
| Batch 3 | 541,197 | 122 | 34 min | 0 (9 money-path tests, 2 test bases) | n/a |
| Batch 4 | n/a | n/a | n/a | stopped by usage limit | n/a |

Batch 1 was the cheapest per item because it took the small items first and inherited the full
context. Cost per item rose as items got larger. Batch 3 spent its budget on tests, not register items.

---

## 3. Your prompts vs the agents' prompts

### 3a. What each looked like
| | Your prompts (Owner) | Agent prompts (CEO briefs to role agents) |
|---|---|---|
| Count | ~27 across the session | 18 in the pilot (plus 6 tech-debt briefs) |
| Typical length | 1–12 words ("continue", "go ahead", "okay"); one ~520-word spec | 150–300 words each |
| Structure | free text; context lived in the conversation | fixed: work id, absolute paths, files to read, deliverable, gate, constraints, reply format |
| Decision authority | stated explicitly when you were away ("take decisions on your own") | each brief states what the agent may and may not decide |
| Acceptance criteria | only in the OpenForge spec ("Success Criteria") | every brief names the gate it must pass |

### 3b. Scorecard (1 = weak, 5 = strong)
| Criterion | Your short prompts | Your OpenForge spec | CEO briefs |
|---|:---:|:---:|:---:|
| Clear goal | 2 | 5 | 5 |
| Constraints stated | 1 | 5 | 5 |
| Testable "done" | 1 | 4 | 4 |
| Scope boundaries | 2 | 4 | 5 |
| Decision authority | 4 | 5 | 4 |
| Cost to write | 5 | 2 | 3 |

### 3c. What the numbers show
- **Your short prompts were efficient because the context carried them.** "go-ahead and solve all tech
  debts…" (10 words) produced 42+ register closures across the session, because the plan file and the
  register already held the spec. On their own they would not work.
- **Your OpenForge spec was the best prompt of the session.** It had constraints, milestones, success
  criteria and "present the plan first". It needed no clarification round, and every later decision
  could be checked against it.
- **Ambiguous short prompts cost interpretation, not rework.** "now re-run everything use ceo agent
  from top" (9 words) needed 5 default decisions from me. They held, but they were my guesses.
- **The costliest defect was in an agent prompt, not yours.** My discovery brief carried an unverified
  assumption ("the gateway sets X-Forwarded-For"). It passed through four roles unchallenged, and fixing
  it took 3 review rounds, about **518k tokens (49% of the pilot)**.
- **Structured agent briefs work well for bounded roles.** CPO, CTO, EM and QA ran 16–49k tokens and
  passed first time. Open-ended roles cost more: the Architect at 68k, and engineers at 38–93k.
- **Review is the most expensive role per run** (55–84k), because each reviewer reads the full
  ~2,000-line diff. It was also the only role that found real defects.
- **Instructions in prompts are not enforcement.** An engineer broke "one build at a time" even though
  both its brief and project.md stated it.

### 3d. Recommendations
| For | Change | Expected effect |
|---|---|---|
| Your prompts | For a new objective, use the OpenForge-spec style: goal, constraints, "done", what I may decide. Keep "continue" for in-flight work. | Fewer default decisions to guess |
| Agent prompts | The discovery step must list every runtime assumption with proof (file or source); unproven means a blocking question | Would have avoided most of the 49% rework |
| Agent prompts | Engineers must add a test through the real hop (gateway → service) for request-metadata features | Catches what MockMvc cannot |
| Agent prompts | Re-reviews read only the delta since the last reviewed SHA, plus the prior findings | Roughly halves review cost per round |
| Enforcement | Put the one-build lock in `gradle.sh` (a host-level lock) | Removes a rule agents broke |
| Gate rule | Apply the size limit to production lines only (Owner decision) | Stops punishing well-tested changes |

---

## 4. Decisions waiting for you
1. **The pilot (F-20261119-01) is stopped after 3 review rounds.** Options:
   - (a) Fix the gateway hop: forward a gateway-computed `X-Internal-Client-Ip`, plus a through-gateway
     test. This folds in backlog B-1, so the class likely becomes M+ across services.
   - (b) Ship with the IP check off (`USER_REFERRAL_IP_LOOKBACK=0`), keeping the code-age check, and do
     B-1 next.
   - (c) Abandon the branch.

   Recommendation: (b) then B-1. The age check alone stops instant self-referral, and B-1 fixes the
   forgeable audit-log IP as well.
2. **Size rule:** count production lines only (constitution edit).
3. **Push:** nothing is pushed. The branch is local: `forge/F-20261119-01-referral-abuse-checks`, head `78a7b4f6`.
4. **Tech-debt agent:** resume it (its usage limit has reset), or keep it paused.

---

## 5. Continuation: "complete pending works without my intervention"

### Action log
| # | Role | Action | Tokens | Result |
|---|---|---|---:|---|
| 1 | CEO | Host-wide build lock in gradle.sh (one-build rule now enforced in the script) | — | done |
| 2 | CEO | Skill edits (discover: proof of runtime assumptions; review: delta-first) | — | **blocked by the protect hook** (governance), filed as G-1/G-2 |
| 3 | Engineer | Pilot r4: IP check off by default, nothing recorded while off, TD-SEC-15 filed | 52,569 | verify PASS (78) |
| 4 | QA | r4 evidence | 18,957 | pass |
| 5 | Reviewer / Security | r4 on a 291-line delta | 46,707 / 40,137 | request_changes (stale generated doc, CI would fail) / approve |
| 6 | Engineer | r5: regenerated env docs, `ipCheckOn()`, startup WARN when enabled | 30,746 | verify PASS (79), env check OK |
| 7 | Reviewer / Security | r5 on a 192-line delta | 27,072 / 23,607 | **approve / approve** |
| 8 | CEO | Gate | — | 34/35 ok; size only (442 production of 2,028) |
| 9 | CEO | B-1 discovery with proof (gateway 5.0.2 bytecode) | — | objective F-20261119-03 |
| 10 | CPO / CTO / Architect / CTO | B-1 requirements (13 ACs), class L (6), design + ADR 0002, accept with 6 conditions | 20,328 / 13,996 / 85,931 / 28,462 | **waiting for Owner design approval** (class L) |

### Benchmarks: delta-first re-review (applied in the briefs)
| | Full-diff rounds (r1–r3) | Delta rounds (r4–r5) |
|---|---:|---:|
| Tokens per review run (avg) | 73,312 | 34,381 (−53%) |
| Diff lines read in full | ~2,000 | 291 / 192 |
| Real defects found | 3 majors | 1 major (CI-breaking stale doc) |

Pilot total now 1,050,760 + 239,795 = **1,290,555 tokens** (r4–r5 = 19%). B-1 planning to the design gate: 148,717 tokens, about the same as the pilot planning (150,404). Discovery proved
the runtime facts first this time (lesson applied); whether that avoids rework will show in its review rounds.
