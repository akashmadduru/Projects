# F-20261119-03 Complexity

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 2 | gateway, payment, auth, user-service plus the common-web lib: 4+ |
| Data change | 0 | none |
| Public API / event contract | 1 | additive internal header X-Internal-Client-Ip; no breaking change |
| Security or money path | 2 | audit trail, wallet limit (payments), auth device context, referral abuse check |
| New dependency or infrastructure | 0 | none allowed (non-goal); shared code goes in an existing lib |
| Estimated changed lines | 1 | 150-600 with the AC-10 integration test; near the upper bound, so watch it |

**Total: 6. Class: L.**

Required for L: design.md and an ADR; the Owner approves the design before implementation; split into PRs under 600 changed lines each. A natural split if the diff nears the limit: (1) shared resolver plus gateway header; (2) reader migrations, docs and the AC-10 test.

## Design review
(pending step 4)
Owner approval: standing (R-20261119-01 charter, class-L designs accepted by the CTO), applied 2026-11-19 by forge-ceo.
