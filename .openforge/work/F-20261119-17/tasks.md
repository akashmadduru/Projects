# F-20261119-17 Tasks: gateway route/authorization contract test (TD-TEST-04)

Owner of every task: forge-backend-engineer. Worktree: `c:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-17`.
Branch: `forge/F-20261119-17-*`. Base: `48847f8f759572b273ded0411d22c7425e484ea9`.

Conventions
- Test dir `T` = `services/gateway/src/test/java/com/acme/ecommerce/gateway`. Evidence file = `.openforge/work/F-20261119-17/evidence.md`.
- VERIFY-FULL (run from the worktree): `node .claude/hooks/forge-verify.mjs --changed --base 48847f8f759572b273ded0411d22c7425e484ea9`
- VERIFY-GW (run from the worktree): `bash .openforge/scripts/gradle.sh :services:gateway:test --tests '*RouteAuthorizationContractTest'` (docker; prints `gradle_rc=`).
- One docker/Gradle build at a time. Never run two verify commands concurrently. Record each output summary in evidence.md.
- Test-only: no edit to `services/gateway/src/main` or `build.gradle` (CTO condition 3). If any test finds a real exposure or a path-normalisation gap, STOP and report to the CEO (Q2). Do not touch production code.
- Every implementation task includes its own tests and ends with a commit. Do not push.
- "Gate" before each commit: run the task's verification command, confirm it passes, and paste the tail into evidence.md.

---

## Task 1. Spike: EdgeRules introspection against Spring Security 7.0.6 (decision point)
- Goal: prove or disprove that the real `SecurityWebFilterChain` rules can be read, and record the real names. Throwaway-quality probe that becomes the seed of `EdgeRules`.
  1. Boot the gateway context in a scratch test `T/support/EdgeRulesSpikeTest.java` (copy the `GatewayDenyListEdgeIntegrationTest` setup: RANDOM_PORT, `TestKeys`, `StubHttpServer`, `@MockitoBean ReactiveStringRedisTemplate`, eureka and rate-limit off).
  2. From the `SecurityWebFilterChain` bean call `getWebFilters()`, find `AuthorizationWebFilter`, then walk to `DelegatingReactiveAuthorizationManager.mappings` with `ReflectionTestUtils`, unwrapping observation decorators. Record the actual field names, class names and the manager method (`authorize` vs `check`) that 7.0.6 exposes.
  3. Extract the ordered `(method|ANY, pattern)` list from `OrServerWebExchangeMatcher.matchers` and `PathPatternParserServerWebExchangeMatcher` (`pattern`, `method`), plus the `anyExchange` entry. Invoke each manager with anonymous, CUSTOMER and each admin authority to derive PERMIT / DENY / AUTHENTICATED / ROLE.
  4. Print the table and compare by eye to `SecurityConfig.java:105-201` (15 PUBLIC_PATHS, health permit, `/actuator/**` deny, `/api/admin/**` role, catch-all).
- DECISION POINT (CTO condition 2), recorded in evidence.md under "Task 1 spike outcome":
  - MODE-A (introspection works): record the real field and method names, then continue with the design as written.
  - MODE-B (manager cannot be read, or the table does not match SecurityConfig): do NOT skip silently and do NOT drop to probes-only. Record "fallback adopted" in evidence.md and notify the CEO in the task report before continuing. Fallback = a reviewed `(method, pattern, reason)` permit table in the test plus behavioural probes of every route pattern x method (GET, POST, PUT, PATCH, DELETE) with an anonymous, a CUSTOMER and each admin-role token, so a new `permitAll` is caught through its route. Self-check anchors and the `> 0` counts are kept in either mode. Tasks 3 to 9 then replace "chain introspection" with this probe table, keeping every AC assertion.
- Files: `T/support/EdgeRulesSpikeTest.java` (temporary; promoted or deleted in Task 3), `.openforge/work/F-20261119-17/evidence.md`.
- ACs covered: groundwork for AC-2, AC-9, AC-12 (no AC closes here).
- Verify: `bash .openforge/scripts/gradle.sh :services:gateway:test --tests '*EdgeRulesSpikeTest'`; then the spike outcome is written to evidence.md.
- Commit: `forge(F-20261119-17): spike EdgeRules introspection of gateway security chain`

## Task 2. RouteTable: parse routes from application.yml, fail loudly (TDD)
- Goal: `support/RouteTable` loads classpath `application.yml` with `YamlPropertySourceLoader`, keeps the document without `spring.config.activate.on-profile`, binds `spring.cloud.gateway.server.webflux.routes` to `List<RouteDefinition>` with `Binder`, and exposes route id -> list of `Path=` patterns (comma-split by `PredicateDefinition`). Fail rules: zero routes; a route with no `Path` predicate; any non-`Path` predicate ("extend the contract"); parsed ids differ from live `org.springframework.cloud.gateway.config.GatewayProperties#getRoutes` ids (use the FQN, it clashes with the project's `config.GatewayProperties`).
- Files: `T/support/RouteTable.java`, `T/RouteAuthorizationContractTest.java` (create class with `routesAreParsedFromApplicationYml` only; boot setup comes in Task 4). Unit-test the fail rules in the same task with small in-memory YAML fixtures (zero routes, no Path, Host predicate) via a `RouteTable.parse(Resource)` entry point.
- ACs: AC-1, AC-16 (class name and package exist).
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): add RouteTable parsing gateway routes from application.yml`

## Task 3. EdgeRules: real rule table, classify(), self-check (MODE-A) or reviewed table + probe harness (MODE-B)
- Goal (MODE-A): promote the spike into `support/EdgeRules` with `entries()` (ordered `(method|ANY, pattern, class)`), `classify(method, path)` = first match, and a `selfCheck()`: every reflected `PUBLIC_PATHS` entry is an ANY PERMIT; `/actuator/**` is DENY; `/api/admin/**` is ROLE; last entry is catch-all AUTHENTICATED; entry count > 0. Failure message: "EdgeRules could not read the chain". Delete `EdgeRulesSpikeTest`. Add tests proving the self-check fires (feed a stub/empty table) and that `classify` handles the first-match overlaps (`GET /api/sellers/me` AUTHENTICATED before `GET /api/sellers/*` PERMIT; `/api/orders/delivery-estimate` PERMIT vs `/api/orders/1` AUTHENTICATED).
- Goal (MODE-B): implement the fallback decided in Task 1 (reviewed permit table + probe harness helpers), keeping self-check anchors and `> 0` counts. Delete the spike.
- Files: `T/support/EdgeRules.java`, test `T/support/EdgeRulesTest.java` (or a nested class in the contract test), delete `T/support/EdgeRulesSpikeTest.java`.
- ACs: AC-2 (classify via the real rules), AC-9 (ROLE entries readable), groundwork AC-12.
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): add EdgeRules reading the real security chain with self-check`

## Task 4. Contract test boot, token fixtures, classification table, reviewed permits
- Goal: in `RouteAuthorizationContractTest` add the Spring boot setup (`@SpringBootTest(RANDOM_PORT)`, `WebTestClient.bindToServer()` 20s timeout, `TestKeys.generate("route-contract-kid")`, `StubHttpServer.start(KEYS::jwksJson)`, `@DynamicPropertySource` for `gateway.jwt.jwks-uri`, `eureka.client.enabled=false`, `gateway.rate-limit.enabled=false`, `@MockitoBean ReactiveStringRedisTemplate` returning `Mono.empty()` on `opsForValue().get(any)`). Mint once per class: CUSTOMER, ADMIN, SUPPORT, FINANCE, CATALOG_MANAGER, SELLER tokens (`Roles` constants where they exist; CUSTOMER literal). Add helper `passes(status)` = not in {401, 403}. Add `probePath(pattern)` (`**` -> `contract-probe`, `*` and `{x}` -> `1`). Add test `classificationTable`: every route pattern x {GET, POST, PUT, PATCH, DELETE} classified via `EdgeRules.classify`, each class one of the five (public-any-method, public-method-specific, authenticated, role-restricted, denied), table printed once, row count > 0. Add `REVIEWED_PERMITS` (method|ANY, pattern, reason; about 40 entries, taken from reading `SecurityConfig`, 15 PUBLIC_PATHS + health + method-specific) and test `permitsAreReviewed`: set(chain PERMITs) == set(reviewed) with messages "unreviewed permit <m> <p>" / "stale reviewed permit <m> <p>".
- Files: `T/RouteAuthorizationContractTest.java`.
- ACs: AC-2, AC-12 (new permit must be reviewed).
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): contract test boot, classification table and reviewed permit list`

## Task 5. Every permit has a route (allow-list with reasons)
- Goal: test `everyPermitHasARoute`. For every PERMIT pattern (PUBLIC_PATHS for AC-6, method-specific for AC-7, e.g. `/api/push/vapid-public-key`, `/api/payments/webhook`, `/api/ads/impressions`, `/sitemap.xml`), derive the probe path and require at least one route `PathPattern` (Spring `PathPatternParser`) to match it. `UNROUTED_ALLOWED` map (pattern -> reason) holds `/actuator/health` and `/actuator/health/**` ("served by the gateway's own actuator, not a route"). Stale check: an allow-listed pattern that matches a route fails. Message: "PUBLIC_PATHS entry <p> matches no route". Include the sub-assertion that `/oauth2/**` and `/login/oauth2/**` are routed (auth-oauth2).
- Files: `T/RouteAuthorizationContractTest.java`.
- ACs: AC-6, AC-7, AC-12 (new PUBLIC_PATHS entry without a route fails).
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): assert every public entry is backed by a route`

## Task 6. Non-public routes need a token; public entries pass; method-bound permits
- Goal: three `@TestFactory` dynamic tests named `route/pattern/method/actor`:
  - `nonPublicRoutesRequireAToken` (AC-3): each non-PERMIT (pattern, method) with no Authorization header gives 401 with `application/problem+json`, `$.status` 401, `$.code` UNAUTHENTICATED. Must visibly cover the user, cart, wishlist and order routes (assert those route ids are present in the generated set). AUTHENTICATED rows with a CUSTOMER token pass (not 401/403).
  - `publicEntriesPassWithoutToken` (AC-4): every `REVIEWED_PERMITS` probe with its permitted method and no token is not 401/403, including `/actuator/health`.
  - `methodSpecificPermitsAreMethodBound` (AC-5): non-permitted methods on method-specific permits give 401 (POST/PUT/DELETE `/api/products/1`, POST `/api/ratings/products/1`, POST `/api/categories/1`).
  - Assert each factory generates more than zero tests.
  - If runtime exceeds about 1 minute, limit role rows to GET+POST and record the reason in evidence.md.
- Files: `T/RouteAuthorizationContractTest.java`.
- ACs: AC-3, AC-4, AC-5, AC-12 (new route falls to catch-all authenticated).
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): behavioural probes for protected, public and method-bound routes`

## Task 7. Admin role restriction and role-prefix guard
- Goal:
  - `adminPathsAreRoleRestricted` (AC-8): every route pattern under `/api/admin/` (across auth-secured, product, inventory, order, rating, payment, promotion, notification, seller, support; assert each of those route ids contributes at least one pattern, derived from the parsed table, not hard-coded paths): no token gives 401; CUSTOMER token gives 403 with `application/problem+json`, `$.code` FORBIDDEN; ROLE_ADMIN, ROLE_SUPPORT, ROLE_FINANCE, ROLE_CATALOG_MANAGER each pass (not 401/403).
  - `rolePrefixesAreKnown` (AC-9): chain ROLE entries must equal `EXPECTED_ROLE_RULES = {/api/admin/** -> the 4 authorities}`. A new role rule or a changed set fails with a message telling the author to add a role assertion.
- Files: `T/RouteAuthorizationContractTest.java`.
- ACs: AC-8, AC-9
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): assert admin role restriction and known role prefixes`

## Task 8. Actuator closed and unmapped paths deny by default
- Goal:
  - `actuatorIsClosed` (AC-10): `/actuator/env`, `/actuator/shutdown`, `/actuator/anything` give 401 with no token and 403 with an ADMIN token; `/actuator/health` and `/actuator/health/liveness` are not 401/403.
  - `unmappedPathsDenyByDefault` (AC-11): `/api/zz-unmapped/x` and `/internal/x` give 401 with no token; with a CUSTOMER token they reach routing and return 404 (not 401/403).
- Files: `T/RouteAuthorizationContractTest.java`.
- ACs: AC-10, AC-11
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): assert actuator denial and deny-by-default for unmapped paths`

## Task 9. Edge cases: overlaps, path variants, bad tokens, CORS, seller (Q1)
- Goal: explicit named tests from the design edge-case table:
  - Overlaps: `POST /api/auth/2fa/verify` passes vs `/api/auth/2fa/contract-probe` 401; `/api/auth/email/verify` passes vs `.../resend` 401; `GET /api/sellers/me` 401 vs `/api/sellers/1` passes; `GET /api/orders/delivery-estimate` passes vs `/api/orders/1` 401; `POST /api/products/1/views` passes vs `POST /api/products/1` 401.
  - Path variants with no token must be in {400, 401}, never pass-through: `/api/orders/`, `/API/ORDERS`, `//api/orders`, `/api/admin/orders/..;/`, `/api/orders/%2e%2e/products`. Send via `URI.create` so the client does not re-encode.
  - HEAD and plain OPTIONS on `/api/orders/1` give 401; a CORS preflight (allowed Origin + Access-Control-Request-Method) gets the 200 CORS answer and the follow-up GET still gives 401.
  - Expired, malformed (`Bearer x.y`) and wrong-signature tokens (second `TestKeys` with the same kid) on `/api/orders/1` give 401.
  - Q1: `/api/seller/*` probes with CUSTOMER and SELLER tokens pass; comment states it is authenticated-only at the edge (flagged for the CEO, not fixed).
  - If a variant passes through, STOP and report to the CEO (Q2); do not change production code.
- Files: `T/RouteAuthorizationContractTest.java`.
- ACs: AC-3, AC-5, AC-11 (extra edge coverage; the requirements section 4 cases).
- Verify: VERIFY-GW
- Commit: `forge(F-20261119-17): contract edge cases for overlaps, path variants, tokens and CORS`

## Task 10. Register and doc edits (AC-15)
- Goal:
  - `docs/techdebt/testing.md`: delete the `td-test-04` anchor and its section (lines 19-25).
  - `docs/techdebt/README.md`: delete the TD-TEST-04 index row (line 31); at line 192 end the entry with "Reframed as TD-TEST-04; fixed by `RouteAuthorizationContractTest` (F-20261119-17)." with no link.
  - `docs/techdebt/phase9.md:14`: replace `([TD-TEST-04](testing.md#td-test-04))` with `(done: RouteAuthorizationContractTest, F-20261119-17)`.
  - `docs/techdebt/remediation-plan.md`: add a row "TD-TEST-04 | gateway route/authz contract test | ✅ Fixed" (project.md convention), no anchor link.
  - Confirm line numbers by reading first; then `grep -rn "td-test-04\|TD-TEST-04" docs/` and verify no remaining link points at the removed anchor (remaining plain-text mentions must be the intended ones above).
- Files: `docs/techdebt/testing.md`, `docs/techdebt/README.md`, `docs/techdebt/phase9.md`, `docs/techdebt/remediation-plan.md`.
- ACs: AC-15
- Verify: `grep -rn "testing.md#td-test-04" docs/` returns nothing, then VERIFY-FULL.
- Commit: `forge(F-20261119-17): remove TD-TEST-04 from the tech-debt register`

## Task 11. Guard demo: three mutation runs (AC-13)
- Goal: prove the test catches regressions. For each mutation, edit `SecurityConfig.java` in the worktree, run VERIFY-GW, copy the failing assertion text into evidence.md, then `git checkout -- services/gateway/src/main` and confirm `git diff --exit-code -- services/gateway/src/main` exits 0 before the next mutation. Never commit a mutation.
  - (a) add `.pathMatchers("/api/orders/**").permitAll()` before `anyExchange`. Expect "unreviewed permit ANY /api/orders/**" and an AC-3 row passing with no token.
  - (b) add `"/api/auth/zz-no-route"` to `PUBLIC_PATHS`. Expect "PUBLIC_PATHS entry ... matches no route" (and an unreviewed permit).
  - (c) drop `ROLE_SUPPORT` from the admin rule, or change `/api/admin/**` to `authenticated()`. Expect an AC-9 role-table mismatch and/or an AC-8 SUPPORT/CUSTOMER row failing.
  - If a mutation does NOT fail the test, stop: strengthen the test (return to the relevant earlier task) and rerun the demo from scratch.
  - Finish with the clean green VERIFY-GW run recorded in evidence.md.
- Files: `.openforge/work/F-20261119-17/evidence.md` (no committed code change; commit only if evidence is tracked, otherwise no commit).
- ACs: AC-13
- Verify: each mutation run uses VERIFY-GW (expected `gradle_rc` non-zero), then final `git diff --exit-code -- services/gateway/src/main` and green VERIFY-GW.
- Commit: none expected (evidence only). If a commit is needed: `forge(F-20261119-17): record guard demo evidence`

## Task 12. Final verification and handoff evidence
- Goal: from a clean tree, run VERIFY-FULL once (docker, nothing else running) and record the full output. Confirm: the contract test passes together with the existing gateway suite and needs no network, Redis or Eureka (AC-14); `git diff --exit-code 48847f8f759572b273ded0411d22c7425e484ea9 -- services/gateway/src/main services/gateway/build.gradle` exits 0 (AC-16, CTO condition 3); `grep -rn "RouteAuthorizationContractTest" services/gateway/src` shows the javadoc references now resolve to an existing class. Write in evidence.md: the mode used (A or B), the Q1 flag (`/api/seller/**` authenticated-only at the edge), and any exposure found (expected none). Report the HEAD SHA for the reviewer.
- Files: `.openforge/work/F-20261119-17/evidence.md`.
- ACs: AC-14, AC-16
- Verify: VERIFY-FULL
- Commit: `forge(F-20261119-17): record final verification evidence`

---

## AC coverage table

| AC | Task(s) |
|---|---|
| AC-1 | 2 |
| AC-2 | 1 (groundwork), 3, 4 |
| AC-3 | 6, 9 |
| AC-4 | 6 |
| AC-5 | 6, 9 |
| AC-6 | 5 |
| AC-7 | 5 |
| AC-8 | 7 |
| AC-9 | 3, 7 |
| AC-10 | 8 |
| AC-11 | 8, 9 |
| AC-12 | 4, 5, 6 |
| AC-13 | 11 |
| AC-14 | 12 (and the gate on every task) |
| AC-15 | 10 |
| AC-16 | 2 (class exists), 12 (no src/main diff) |

Every AC-1 to AC-16 is covered by at least one task.
