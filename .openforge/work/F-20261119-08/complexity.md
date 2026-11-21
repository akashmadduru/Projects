# F-20261119-08 Complexity: access-token deny-list on disable and demote (AC-13..AC-17)

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 1 | auth-service (write) plus the gateway and any other point that accepts access tokens (check); 2-3 services. Re-score if the check must be in 4+ services. |
| Data change | 0 | Redis keys only (`sub` cutoff, TTL about 10 m). No DB migration. |
| Public API / event contract | 0 | Unchanged; a revoked token gets the existing 401. |
| Security or money path | 2 | Direct: token revocation, admin disable and demote. |
| New dependency or infrastructure | 0 | Existing Redis (auth and gateway already use it). |
| Estimated changed lines | 1 | Deny-list write on two admin paths, check at the acceptance points, fail-safe, metric, config: roughly 200-400 production lines. |
| **Total** | **4** | |

## Class: M

Required: requirements, design.md and an ADR, tasks, implement, test, review. No Owner design approval needed.

Design must settle: the AC-17 fail-open, fail-closed or admin-only choice; every token acceptance point covered; the 5 s bound given any gateway local cache; shared Redis and key prefix across gateway and auth.
