# F-20261119-07 Complexity: add-phone enumeration and reset side effect (AC-9..AC-12)

| Factor | Score | Reason |
|---|---|---|
| Services or apps touched | 1 | auth-service plus the existing notification path for the owner notice (AC-10); 2 services. |
| Data change | 0 | Redis counter for the one-notice-per-day limit. AC-12 option (a) or (b) adds no migration. |
| Public API / event contract | 2 | Add, resend and verify stop returning 409 `AUTH_PHONE_TAKEN`; clients that handle that code change behaviour. Unsure between 1 and 2, so the higher score. The design must say so. |
| Security or money path | 2 | Direct: enumeration defence, PII (phone numbers), credential creation on reset. |
| New dependency or infrastructure | 0 | None. |
| Estimated changed lines | 1 | Generic response on three paths, owner notice with daily limit, reset decision: roughly 200-450 production lines. |
| **Total** | **6** | |

## Class: L

Required: design.md and an ADR; the Owner approves the design before implementation; PRs under 600 production lines.

Owner approval: standing (R-20261119-01 charter, class-L designs accepted by the CTO)

Design decisions to confirm: AC-12 option (a) or (b), and an explicit statement of the removed 409 and its frontend impact.
