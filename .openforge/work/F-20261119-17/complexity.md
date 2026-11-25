# Complexity: F-20261119-17 (TD-TEST-04 gateway route/authz contract test)

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 0 | gateway only (plus a register doc edit) |
| Data change | 0 | none |
| Public API / event contract | 0 | unchanged; test-only |
| Security or money path | 2 | the test asserts auth rules directly; production auth code is read, not changed |
| New dependency or infrastructure | 0 | none; SnakeYAML/Jackson and WebTestClient already on the classpath |
| Estimated changed lines | 1 | test ~150-300 lines; production ~0 |
| **Total** | **3** | |

Class: **M** (3-5). Required: requirements, tasks, design.md and an ADR, implement, test, review.

Production-line limit: 600 (class M); expected production lines 0. Any production change means the test found a real gap and needs CEO sign-off first.

Note on class: scored conservatively per the rubric. Security is scored "direct" because the test governs the auth rules. If the CEO considers a test-only change as "indirect" (1), the total is 2 and the class is S. I take the higher.

## Design review
Verdict: ACCEPT (2026-11-23), with conditions. Full text is in design.md "CTO decision".
- Test-only, no src/main diff, no new dependency, no new service/datastore/vendor; every AC maps to a test; no public contract changes.
- Introspection of Spring Security internals is accepted, fenced by a self-check, and requires an explicit fallback (reviewed expected-permit table plus behavioural probes) if the chain cannot be read.
- CEO Q1 ruling (/api/seller/** authenticated-only, flagged) is accepted as designed.
- ADR 0015 status set to Accepted.
