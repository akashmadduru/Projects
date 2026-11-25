# 0015 Gateway route/authorization contract: the real chain, a reviewed public list, and parsed routes

## Status
Accepted (2026-11-23, CTO; with conditions, see work/F-20261119-17/design.md "CTO decision"). Recorded by the CEO because the CTO role cannot write ADRs. Class M, F-20261119-17 (TD-TEST-04). Test-only, so no Owner approval is needed.

## Context
- The gateway is deny-by-default: `anyExchange().authenticated()` in `SecurityConfig`.
- The route table in `application.yml` and the `authorizeExchange` rules are two halves of one statement. Nothing asserts them against each other, although both files claim a `RouteAuthorizationContractTest`.
- The routes `/api/users`, `/api/cart`, `/api/wishlist` and `/api/orders` rely on the catch-all with no test.
- When the two halves drift, nothing fails loudly: a route quietly stops being protected.

## Decision
`RouteAuthorizationContractTest` is the guard for the edge's authorization contract. It works in four parts.

**1. Routes are parsed, not listed.**
- The test parses the routes from `application.yml` with Boot's YAML loader and `Binder`, into Spring Cloud Gateway's `RouteDefinition`.
- The parsed route ids are cross-checked against the live route ids.
- A route with no `Path` predicate, or with any other predicate type, fails the test.

**2. Rules are read from the running `SecurityWebFilterChain`.**
- The test reflects over the `DelegatingReactiveAuthorizationManager` mappings.
- Each rule's decision class (PERMIT, DENY, AUTHENTICATED, ROLE) is found by invoking its manager with an anonymous, a customer and a role-holding authentication.
- A self-check pins known anchors, so a Spring upgrade that breaks the introspection fails the test instead of passing it vacuously.

**3. The test holds two reviewed tables, each entry with a reason.**
- The public list (every permit) must equal the chain's permits.
- The role-rule table (`/api/admin/**` to its four authorities) must equal the chain's role rules.
- So a new `permitAll` or a new or changed role rule fails until someone deliberately reviews it.

**4. Behaviour is proven through real Netty.** The test uses real RS256 tokens and an unreachable downstream:
- every route pattern × method gives 401 with no token unless it is reviewed-public;
- `/api/admin/**` gives 403 to a customer and passes for each admin role;
- `/actuator/**` (other than health) is closed;
- unmapped paths give 401.

Every public entry must match a route, unless it is in a named allow-list with a reason. Stale allow-list entries fail.

## Consequences
- **Opening a path takes two edits.** It needs `SecurityConfig` plus the test's reviewed table, which is the intended second key.
- **Spring Security upgrades may need `EdgeRules` updated** (it reflects on private fields). The failure is loud and confined to one test helper.
- **`/api/seller/**` remains authenticated-only at the edge.** The test asserts that and flags it. Adding a seller role rule is a separate production change that needs CEO sign-off, and the role-table check forces the test to be updated when it lands.
- **Rollback.** Delete the test files. There is no runtime effect.

## Work id
F-20261119-17. Design: `.openforge/work/F-20261119-17/design.md`.
