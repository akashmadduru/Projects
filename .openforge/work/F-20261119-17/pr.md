# Gateway route/authorization contract test (TD-TEST-04)

**Stacked on F-20261119-08** (`forge/F-20261119-08-token-revocation`, head 48847f8). Merge after F-03 and F-08.
- Range: `48847f8..57e9c94`.
- Size: 0 production lines. The change is tests plus `docs/techdebt` only, with no diff in `services/gateway/src/main` or `build.gradle` and no new dependency.

## Why
- `SecurityConfig` and `application.yml` both say a `RouteAuthorizationContractTest` asserts the route table against the edge's `authorizeExchange` rules. That test never existed (TD-TEST-04).
- When the two drift nothing breaks loudly: a route keeps working and simply stops being protected. The new `/api/users`, `/api/cart`, `/api/wishlist` and `/api/orders` routes relied on deny-by-default with no test.

## What changed
- **`support/RouteTable`:** binds the gateway's real `application.yml` routes into Spring Cloud Gateway `RouteDefinition`s. It cross-checks them against the live route ids and fails on zero routes, a route with no `Path=` predicate, or any other predicate type.
- **`support/EdgeRules`:** reads the ordered rules from the `AuthorizationWebFilter` that actually runs, on Spring Security 7.0.6 (ADR 0015, MODE-A), and asks each rule's own manager for its decision. It is fenced by a self-check that fails with "EdgeRules could not read the chain" if the framework internals change, if a rule has an unsupported matcher before the catch-all, or if an expected anchor is missing.
- **`RouteAuthorizationContractTest`:** 1,277 rows through a real Netty port with real RS256 tokens.
  - **Login required:** every non-public route answers 401 without a token, and every public entry passes.
  - **Method-bound permits:** a method-bound public path refuses the other methods.
  - **Admin:** `/api/admin/**` admits only ADMIN, SUPPORT, FINANCE and CATALOG_MANAGER.
  - **Closed paths:** `/actuator/**` other than health is closed, and unmapped paths such as `/internal/x` are denied by default.
  - **Public paths are routed:** every `PUBLIC_PATHS` entry has a route.
  - **Reviewed permits:** every permit appears in a reviewed list of 44 entries, each with a reason.
  - **Pinned rules:** the set of deny and role rules is pinned exactly.
  - **Edge cases:** traversal from public prefixes (for example `/api/products/%2e%2e/admin/theme`) must get 400, and method-override and HEAD probes must get 401.
- **Register:** TD-TEST-04 is removed from `testing.md` and the README index, the `phase9.md` and README references are reworded, and a fixed row is added to `remediation-plan.md`.

## Evidence
- **Tests:** `RouteAuthorizationContractTest` 1,277, `EdgeRulesTest` 7 and `RouteTableTest` 6, all passing. The full gateway suite passes.
- **`forge-verify --changed`:** PASS. The engineer ran it, and the CEO re-ran it on a detached copy of the head.
- **QA:** mapped AC-1..AC-16 to passing tests; every generated test set asserts it is non-empty.
- **Guard demos** (temporary `SecurityConfig` edits, each reverted):
  - a `permitAll` on `/api/orders/**` fails `permitsAreReviewed`;
  - an unrouted `PUBLIC_PATHS` entry fails `everyPermitHasARoute`;
  - dropping `ROLE_SUPPORT` from the admin rule fails `rolePrefixesAreKnown` and 51 AC-8 rows.

## Review
- Independent code and security review, 2 rounds. Both approve 57e9c94.
- Round 1: security found that the path-variant probes only started from protected prefixes. Round 2 added traversal probes from public prefixes, which all get 400, so the firewall default is now guarded by the build.
- No live exposure was found. Security checked every reviewed permit against the controllers it opens.

## Known gaps (on the backlog)
- **`/api/seller/**` has no role rule at the gateway**, only a login check. A CUSTOMER token reaches seller APIs, which enforce the SELLER role themselves. The test pins today's behaviour. A gateway role rule is a production change that needs sign-off.
- **Rate limiting is not asserted.** The test runs with the gateway limiter off. `/api/auth/**` is skipped by the gateway limiter because auth-service keeps its own; a reason's wording says "see backlog" rather than naming that control (security nit).
- **Customer-only rules are not caught.** A future rule that admits CUSTOMER plus other roles would be read as plain authenticated, which is more restrictive, so this is not an exposure (reviewer minor).
- **Optional tidy-ups:** use `ReflectionUtils.findField`, reuse the reflected `PathPattern`, and drop the per-probe `println`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
