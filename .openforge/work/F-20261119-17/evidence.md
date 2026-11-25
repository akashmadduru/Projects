# F-20261119-17 evidence

## Task 1 spike outcome
**MODE-A: introspection of the real chain works on Spring Security 7.0.6.** No fallback adopted.

Real names (recorded from reflection against the booted context):
- `SecurityWebFilterChain.getWebFilters()` (Flux) contains `org.springframework.security.web.server.authorization.AuthorizationWebFilter`; its private field is `authorizationManager`.
- That value is `org.springframework.security.authorization.ObservationReactiveAuthorizationManager` (fields `registry`, `delegate`, `convention`, `handler`); `delegate` is `org.springframework.security.web.server.authorization.DelegatingReactiveAuthorizationManager` with private field `mappings : List<ServerWebExchangeMatcherEntry>`.
- `ServerWebExchangeMatcherEntry` fields: `matcher` (an `OrServerWebExchangeMatcher` with field `matchers : List`, or the anonymous `anyExchange` matcher for the last entry) and `entry` (the `ReactiveAuthorizationManager`: lambda `AuthorizeExchangeSpec$Access$$Lambda` for permitAll/denyAll, `AuthenticatedReactiveAuthorizationManager`, `AuthorityReactiveAuthorizationManager`).
- Each inner matcher is `PathPatternParserServerWebExchangeMatcher` with fields `pattern` (`PathPattern`, `getPatternString()`) and `method` (`HttpMethod`, null = any).
- Manager generic type is `ReactiveAuthorizationManager<AuthorizationContext>` (NOT `ServerWebExchange`): the argument must be `new AuthorizationContext(exchange)`. Method used: `authorize(Mono<Authentication>, AuthorizationContext)` returning `Mono<AuthorizationResult>` (`isGranted()`); `check` is the deprecated 6.x form.
- 19 mappings, expanded to 48 ordered (method|ANY, pattern) entries. Printed table matches `SecurityConfig.java:105-201` by eye: 2 health PERMIT, `/actuator/**` DENY, 15 PUBLIC_PATHS ANY PERMIT, method-specific PERMITs, `GET /api/sellers/me` AUTHENTICATED before `GET /api/sellers/*` PERMIT, `/api/admin/**` ROLE[ADMIN, CATALOG_MANAGER, FINANCE, SUPPORT], catch-all AUTHENTICATED.
- Spike test (`EdgeRulesSpikeTest`) was throwaway: gradle_rc=0 and the table above. It is NOT committed (promoted into `EdgeRules` in Task 3; the probe test deleted). Task 1 therefore has no commit of its own.

## Task 2 gate
VERIFY-GW (`--tests '*RouteAuthorizationContractTest' --tests '*RouteTableTest'`): BUILD SUCCESSFUL, gradle_rc=0; RouteAuthorizationContractTest 1 test, RouteTableTest 6 tests, 0 failures. Deviation: the Spring boot setup landed in Task 2 (not Task 4) because AC-1's cross-check needs the live `org.springframework.cloud.gateway.config.GatewayProperties` ids.

## Task 3 gate
`*RouteAuthorizationContractTest *EdgeRulesTest`: gradle_rc=0; contract 2 tests, EdgeRulesTest 6 tests, 0 failures. EdgeRules (MODE-A) promoted; spike deleted (never committed).

## Task 4 gate
contract test (4 tests: routesAreParsed, edgeRulesReadTheRealChain, classificationTable, permitsAreReviewed): gradle_rc=0, 0 failures. Real permits == REVIEWED_PERMITS (44 entries).

## Task 5 gate
contract test 5 tests, gradle_rc=0. Every PUBLIC_PATHS entry and method-specific permit matches a route; only /actuator/health and /actuator/health/** are in UNROUTED_ALLOWED.

## Task 6 gate
contract test 921 tests (3 factories + 5 plain... dynamic rows named route/pattern/method/actor), 0 failures, gradle_rc=0, class runtime ~57s (under the ~1 min limit, so no role-row limiting needed).

## Task 7 gate
contract test 1222 tests, 0 failures, gradle_rc=0. All ten admin-contributing routes (auth-secured, product, inventory, order, rating, payment, promotion, notification, seller, support) contribute /api/admin/ patterns.

## Task 8 gate
contract test 1224 tests, 0 failures, gradle_rc=0 (actuator 401/403, unmapped 401 anon and 404 with CUSTOMER).

## Task 9 gate
contract test 1266 tests, 0 failures, gradle_rc=0. No path variant passed through (all in {400,401}); preflight 200 and follow-up GET 401; Q1: /api/seller/* passes with CUSTOMER and SELLER tokens (authenticated-only at the edge, flagged, not fixed).

## Task 10
Register edits done (testing.md section and anchor removed, README row removed and line reworded, phase9.md reference replaced, remediation-plan.md row 57 added). `grep -rn 'testing.md#td-test-04' docs/` returns nothing; remaining plain-text TD-TEST-04 mentions are README.md:191 and remediation-plan.md:111 only. Full verify runs in Task 12.

## Task 11 guard demo
Note: a full-class run under mutation (a) hung well past 10 min (cold docker after restart; killed), so each demo runs the relevant test methods only.

(a) added `.pathMatchers("/api/orders/**").permitAll()` before `anyExchange()` in SecurityConfig: gradle_rc=1.
- `RouteAuthorizationContractTest > AC-12: the permits in the chain equal the reviewed permit list FAILED`: `[unreviewed permit (add to REVIEWED_PERMITS with a reason, or remove the permitAll)] Expecting empty but was: ["ANY /api/orders/**"]`.
- `AC-3: non-public routes need a token ... > initializationError FAILED`: the order route generated no non-public rows: `Expecting TreeSet [...] to contain: ["/api/users/**", "/api/cart/**", "/api/wishlist/**", "/api/orders/**"] but could not find the following element(s): ["/api/orders/**"]`.

(b) added `"/api/auth/zz-no-route"` to PUBLIC_PATHS: gradle_rc=1.
- `AC-12 ... FAILED`: `[unreviewed permit ...] Expecting empty but was: ["ANY /api/auth/zz-no-route"]`.
- `AC-6, AC-7: every permitted pattern matches at least one route ... FAILED`: `[PUBLIC_PATHS entry /api/auth/zz-no-route matches no route (probe /api/auth/zz-no-route)] Expecting value to be true but was false`.

(c) dropped `ROLE_SUPPORT` from the `/api/admin/**` hasAnyAuthority: gradle_rc=1, 301 tests run, 51 failed.
- `AC-9: the role-restricted prefixes are exactly the known ones FAILED`: `[A role rule was added or changed at the edge. Add a role assertion for it here and update EXPECTED_ROLE_RULES] expected: {"/api/admin/**"=["ROLE_SUPPORT", "ROLE_CATALOG_MANAGER", "ROLE_FINANCE", "ROLE_ADMIN"]} but was: {"/api/admin/**"=["ROLE_ADMIN", "ROLE_CATALOG_MANAGER", "ROLE_FINANCE"]}`.
- AC-8 rows, e.g. `support /api/admin/audit/** POST SUPPORT FAILED`, `auth-secured /api/admin/users/** GET SUPPORT`: `[SUPPORT on GET /api/admin/users/contract-probe] Expecting actual: 403 to match given predicate`.

(all three mutations reverted; `git diff --exit-code -- services/gateway/src/main` exits 0. The clean green run is the Task 12 verify below.)

## Task 12 final verification
`node .claude/hooks/forge-verify.mjs --changed --base 48847f8f759572b273ded0411d22c7425e484ea9` (run with CLAUDE_PROJECT_DIR set to the main checkout so the gradle script resolves; the first attempt without it failed with exit 127 because the script path was resolved inside the worktree):
```
forge-verify: 9 changed file(s) in .../worktrees/F-20261119-17 (base 48847f8f7595)
  PASS gradle :services:gateway:test (exit 0)
      BUILD SUCCESSFUL in 2m 8s
      gradle_rc=0
forge-verify: PASS (1 checks)
```
Whole gateway suite passes with the contract test (no network, Redis or Eureka: Redis template is a mock, JWKS is a local stub). `git diff --exit-code 48847f8 -- services/gateway/src/main services/gateway/build.gradle` exits 0. The javadoc references in SecurityConfig.java:39 and application.yml:11 now resolve to the existing class.
Mode: A. Q1 flag: `/api/seller/**` is authenticated-only at the edge (CUSTOMER and SELLER tokens both pass); not fixed. Exposures found: none.

## QA
Reviewed at head e87cc80 (worktree clean for `services/gateway`). Gradle was NOT run by QA (CEO holds the docker build). Evidence is the engineer's JUnit XML `services/gateway/build/test-results/test/TEST-com.acme.ecommerce.gateway.RouteAuthorizationContractTest.xml`: `tests="1266" skipped="0" failures="0" errors="0"`, written 2026-11-25 13:47 IST, after the e87cc80 commit (12:35 IST). The count equals the Task 9 gate (1266), so the XML reflects the final test code. All 17 gateway XML files have no failure, error or skipped element. Dynamic row names are `<route> <pattern> <METHOD> <actor>`.

| AC | Test (method / dynamic rows) | Result and evidence |
|---|---|---|
| AC-1 | `AC-1: routes are parsed from application.yml and match the live route ids` | PASS. Asserts routes non-empty and every route has patterns. Cross-checks ids against the live `GatewayProperties` and requires anchor ids. |
| AC-2 | `AC-2: EdgeRules reads the real chain...`, `AC-2: every route pattern x method gets one of the five documented classes` | PASS. Chain introspected (MODE-A), entries non-empty, classification table non-empty. |
| AC-3 | `@TestFactory` AC-3, rows like `user /api/users/** GET anonymous`, `order ... CUSTOMER` (cart 80, user 50, order 125 and others in the XML) | PASS. `expectProblem` asserts 401, problem+json, `$.code=UNAUTHENTICATED`. Anchors assert `/api/users/**`, `/api/cart/**`, `/api/wishlist/**`, `/api/orders/**`. |
| AC-4 | `@TestFactory` AC-4, rows `public <pattern> <METHOD> anonymous` (59 rows incl. `public /actuator/health GET anonymous`) | PASS. Count assertion is `isNotEmpty`. |
| AC-5 | `@TestFactory` AC-5, `method-bound <pattern> <METHOD> anonymous` (120 rows, incl. `/api/ratings/products/* POST`, `/api/products PUT`) | PASS. Count assertion is `isNotEmpty`. |
| AC-6 | `AC-6, AC-7: every permitted pattern matches at least one route` | PASS. Covers every PUBLIC_PATHS entry. `UNROUTED_ALLOWED` has reasons and a stale-entry check (a matching route fails the test). Only `/actuator/health` and `/actuator/health/**` are allow-listed. |
| AC-7 | same method as AC-6 (covers method-specific permits) | PASS. |
| AC-8 | `@TestFactory` AC-8, rows `<route> /api/admin/... <GET/POST> anonymous/CUSTOMER/ADMIN/SUPPORT/FINANCE/CATALOG_MANAGER` (50 per actor, 175 anonymous incl. variants) | PASS. CUSTOMER 403 FORBIDDEN problem, anonymous 401, four roles pass. The factory asserts all ten routes contribute `/api/admin/` patterns. XML rows exist for all ten (auth-secured 17, product 51, inventory 51, order 51, rating 17, payment 17, promotion 68, notification 34, seller 68, support 51). |
| AC-9 | `AC-9: the role-restricted prefixes are exactly the known ones` | PASS. Compares the chain's role rules to `EXPECTED_ROLE_RULES`. Mutation (c) showed it fail. |
| AC-10 | `AC-10: /actuator is closed (401 anonymous, 403 ADMIN) except health` | PASS. Covers env, shutdown, anything (401/403) and health, health/liveness (pass). |
| AC-11 | `AC-11: a path no route and no rule names is denied by default` | PASS. `/api/zz-unmapped/x` and `/internal/x`: 401 anonymous, 404 with CUSTOMER. |
| AC-12 | `AC-12: the permits in the chain equal the reviewed permit list` (plus AC-6/7 and AC-3 anchors) | PASS. Permits are compared with `REVIEWED_PERMITS` (44), so a new permitAll fails. Unrouted PUBLIC_PATHS fails via AC-6. Unprotected new routes get anonymous 401 rows from AC-3. |
| AC-13 | evidence.md "Task 11 guard demo" | EVIDENCED. (a) permitAll on `/api/orders/**`, rc=1: AC-12 test fails with `unreviewed permit ... ["ANY /api/orders/**"]`. (b) PUBLIC_PATHS `/api/auth/zz-no-route`, rc=1: AC-6/7 fails with `matches no route`. (c) `ROLE_SUPPORT` dropped, rc=1: AC-9 fails and 51 AC-8 rows fail. All reverted; the final green run is the Task 12 verify. Caveat: the engineer's own record, not re-run by QA. |
| AC-14 | evidence.md "Task 12" | EVIDENCED. `forge-verify --changed --base 48847f8...`: `BUILD SUCCESSFUL in 2m 8s`, `gradle_rc=0`, `forge-verify: PASS (1 checks)`. XML matches: 1266/0/0/0 plus the existing gateway suites. No network, Redis or Eureka (mock Redis template, local JWKS stub). The CEO's current verify run is the independent confirmation. |
| AC-15 | `grep -rn TD-TEST-04 docs` (QA run) | PASS. Only two plain-text mentions remain: `docs/techdebt/README.md:191` (reworded) and `docs/techdebt/remediation-plan.md:111` (row 57, Fixed 2026-11-25). The engineer recorded that no `testing.md#td-test-04` anchor remains. |
| AC-16 | class `com.acme.ecommerce.gateway.RouteAuthorizationContractTest` exists; `git diff --name-only 48847f8f759572b273ded0411d22c7425e484ea9 e87cc80 -- services/gateway/src/main services/gateway/build.gradle` | PASS. The diff output is empty (QA ran it, rc=0). The XML suite name matches the class name. |

### Vacuity review
- No row is vacuous. Every `@TestFactory` asserts `isNotEmpty` on its generated tests. AC-3 and AC-8 also assert specific anchors (AC-3: user/cart/order routes and four patterns; AC-8: all ten admin routes). AC-1 asserts anchor route ids. The XML shows non-zero rows in every category.
- Weakness (minor, not a gap): AC-4 and AC-5 assert only `isNotEmpty`, with no named anchor patterns. Coverage is still guarded indirectly: AC-12 pins the full permit list (44), and every permit is a source of rows. The XML has 59 public and 120 method-bound rows.
- AC-11 and AC-10 are single `@Test` methods with fixed path lists, so they cannot be empty.
- The AC-13 mutation runs were limited to the relevant test methods because a full-class run hung in cold docker. Each mutation failed the intended guard test.
- Q1 flag stands: `/api/seller/**` is authenticated-only at the edge (a CUSTOMER token passes). It is asserted as today's behaviour and was not fixed.

Verdict: all ACs evidenced: yes. No missing tests. No defects found.

## CEO verify (detached copy F-20261119-17V at e87cc80, 2026-11-25T08:20:15Z)
```
forge-verify: 9 changed file(s) in c:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-17V (base 48847f8f7595)
  PASS gradle :services:gateway:test (exit 0)
      BUILD SUCCESSFUL in 22s
      gradle_rc=0
forge-verify: PASS (1 checks)
rc=0
```
Note: 22 s wall time (gradle build cache from the engineer's run); exit 0.

## Round 2 (review round 1 fixes, commit 57e9c94)
Anonymous probe statuses (real Netty, URIs sent raw):
- GET /api/products/%2e%2e/admin/theme -> 400
- GET /api/products/..;/seller/offers -> 400
- GET /api/products/%2E%2E/%2E%2E/api/orders/1 -> 400
- GET /api/sellers/..;/admin/payouts -> 400
- GET /api/sellers/me%2f -> 400
- POST /api/auth/login/..;/me -> 400
- GET /api/products/1%2f..%2f..%2fadmin/theme -> 400
- GET /api/categories//..//admin/theme -> 400
- GET /api/sellers/me/ -> 401 (allowed 400 or 401)
- GET /api/sellers/me;x=1 -> 400 (allowed 400 or 401)
- POST /api/products/1?_method=DELETE -> 401
- POST /api/products/1 + X-HTTP-Method-Override: GET -> 401
- HEAD /api/sellers/me -> 401
No exposure. Fixes: rolePrefixesAreKnown pins {ANY /actuator/** -> DENY, ANY /api/admin/** -> ROLE[4]}; EdgeRules PRIVILEGED from Roles.ROLE_*; non-Or matcher only at last index (unit test added); tokens minted with a 6 h lifetime (TestKeys.accessTokenValidFor); reasons updated (gateway RateLimitWebFilter covers chat, ads, views per client IP/user; /api/auth/** incl. otp/request and password/forgot is exempt from it, stated as "abuse control: see backlog"); phase9.md stale phrase removed.
Tests: contract class 1277 passed, EdgeRulesTest 7 passed, 0 failures. forge-verify --changed --base 48847f8: PASS gradle :services:gateway:test (exit 0), BUILD SUCCESSFUL in 2m 20s, gradle_rc=0, forge-verify: PASS. `git diff --exit-code 48847f8 -- services/gateway/src/main services/gateway/build.gradle` exits 0.

## CEO verify round 2 (detached copy F-20261119-17V at 57e9c94, 2026-11-25T08:30:50Z)
```
forge-verify: 10 changed file(s) in c:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-17V (base 48847f8f7595)
  PASS gradle :services:gateway:test (exit 0)
      BUILD SUCCESSFUL in 20s
      gradle_rc=0
forge-verify: PASS (1 checks)
rc=0
```

## CEO AC-13 summary
- AC-13 guard demo: passed. Each of the three mutations (permitAll on /api/orders/**, an unrouted PUBLIC_PATHS entry, ROLE_SUPPORT dropped) made its intended guard test fail (gradle_rc=1) and was reverted; the unmutated run passed afterwards. The demos ran at e87cc80. Round 2 only added assertions and did not weaken any guard (both reviewers checked the delta), so they were not re-run at 57e9c94.
