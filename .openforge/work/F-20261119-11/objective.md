# F-20261119-11 Objective (portfolio O-06, run R-20261119-01)

Checkout integrity and resilience in order-service: stop concurrent checkouts with different idempotency keys from double-ordering one cart (TD-BE-15), add circuit breakers to the Feign clients (TD-BE-07), batch the sequential product lookups at checkout (TD-BE-06), and make the cart clear after an order reliable (TD-BE-03).

Scope (Owner): security and money paths only. Size limits count production lines. No new service, datastore or vendor without an ADR. Resilience4j is acceptable only if it is already on the classpath through Spring Cloud. Otherwise the architect must say so and keep it minimal.

Base: the head of F-20261119-10 (820e5a8). The order-service chain (F-01, F-04, F-09, F-10) is not merged, so this objective is stacked on it.

Register entries: docs/techdebt/backend.md (td-be-03, td-be-06, td-be-07, td-be-15). Some may be partly fixed already; for example, TD-BE-03 says "re-order refused since TD-BE-15". Verify against the code.

## Blocking questions
None.
