# F-20261119-17 Gateway route/authorization contract test (TD-TEST-04)

## Objective (CEO-originated from the tech-debt register, security scope)
Add the `RouteAuthorizationContractTest` that `SecurityConfig` and `application.yml` both claim exists:
parse every route in the gateway's `application.yml` route table and assert each route's path predicates
against the `authorizeExchange` rules in `SecurityConfig`, so a route that silently becomes unprotected
(or a public path with no route, or a role rule that drifts) fails the build. Test-only; production code
changes only if the test exposes a real gap, which then needs CEO sign-off.

## Context
- TD-TEST-04 (docs/techdebt/testing.md): Med / M. The new routes /api/users, /api/cart, /api/wishlist, /api/orders rely on deny-by-default with no test.
- Base: F-08 head 48847f8 (`forge/F-20261119-08-token-revocation`), which changes SecurityConfig and stacks on F-03. Both are Owner-approved for push. Merge after F-08.
- Gateway module: services/gateway (Spring Cloud Gateway, reactive). Existing tests: GatewayEdgeIntegrationTest, GatewayDenyListEdgeIntegrationTest, filter/, security/, support/.
- Branch `forge/F-20261119-17-gateway-route-authz`, worktree `.openforge/worktrees/F-20261119-17`.
- On fixing an item, delete it from the register and the index in the same PR (register rule).

## Blocking questions
None. Default: a test that proves the contract today; if it finds a real exposure, stop and report to the CEO before changing production code.
