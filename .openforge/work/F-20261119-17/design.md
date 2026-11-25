# F-20261119-17 Design: gateway route/authorization contract test (TD-TEST-04), class M, test-only

## 1. Current behaviour
- `SecurityConfig.java:37-40` and `application.yml:9-13` name a `RouteAuthorizationContractTest` that does not exist (grep: only those two lines plus `docs/`).
- Rules (`SecurityConfig.java:105-201`, first match wins): health permit (112), `/actuator/**` denyAll (119), `PUBLIC_PATHS` (51-74, 15 entries) permit (121), method-specific permits (130-191, incl. `GET /api/sellers/me` authenticated at 178 before `GET /api/sellers/*` permit at 180), `/api/admin/**` hasAnyAuthority ADMIN/SUPPORT/FINANCE/CATALOG_MANAGER (195-197), `anyExchange().authenticated()` (200).
- 15 routes, each with exactly one `Path=` predicate (`application.yml:122-340`). Every route goes to `lb://`. With Eureka off and no instances, a request that passes security gets the circuit-breaker fallback 503 `UPSTREAM_UNAVAILABLE` (`GatewayEdgeIntegrationTest.java:506`).
- 401 is written by `ProblemAuthenticationEntryPoint` (code `UNAUTHENTICATED`). 403 is written by `ProblemAccessDeniedHandler.java:32` (code `FORBIDDEN`). Both use `application/problem+json`.
- Only existing coverage: spot checks in `GatewayEdgeIntegrationTest.java:180-202` (actuator, `/api/orders/1`).

## 2. Proposed change (test sources only; no src/main diff, no new dependency)
New `src/test/java/com/acme/ecommerce/gateway/RouteAuthorizationContractTest.java`, plus two helpers in `support/`.

**2a. Route table: `support/RouteTable`.** Load `classpath:application.yml` (the main resource; there is no test override) with Boot's `YamlPropertySourceLoader`. Keep only the document that has no `spring.config.activate.on-profile`. Bind `spring.cloud.gateway.server.webflux.routes` with `Binder` (`ApplicationConversionService`) to `List<RouteDefinition>`. This is the same binding Spring Cloud Gateway's own `GatewayProperties` uses, and `PredicateDefinition`'s text constructor does the `Path=a,b` comma split. So we write no YAML or predicate parser of our own.
- Classpath check: there is no direct SnakeYAML import. SnakeYAML is on the test runtime classpath through spring-boot-starter (the app already loads `application.yml` in every existing IT). `build.gradle:70-72` gives spring-boot-starter-test (spring-test `ReflectionTestUtils`, JUnit, AssertJ, Mockito), spring-security-test and reactor-test. Nothing is added.
- Fail-loud rules:
  - zero routes fails;
  - a route with no `Path` predicate fails;
  - a predicate other than `Path` (Host, Method, ...) fails with "extend the contract";
  - the parsed route ids must equal the live `org.springframework.cloud.gateway.config.GatewayProperties#getRoutes` ids. Use the FQN, because the name clashes with the project's own `config.GatewayProperties`.

**2b. Rule table: `support/EdgeRules`.** Read the real chain, not a copy.
- Take the `SecurityWebFilterChain` bean, then `getWebFilters()`, then the `AuthorizationWebFilter`. From there, reach `DelegatingReactiveAuthorizationManager.mappings` reflectively (`ReflectionTestUtils.getField`), unwrapping observation decorators (`delegate`/`filter` fields).
- Each `ServerWebExchangeMatcherEntry` gives:
  - `OrServerWebExchangeMatcher.matchers`, then each `PathPatternParserServerWebExchangeMatcher` (`pattern`, `method`), giving ordered `(method|ANY, pattern)`;
  - `anyExchange`, the final catch-all.
- Each entry's decision class comes from invoking its manager (`authorize`/`check`, whichever 7.0.6 exposes) with: no auth (`Mono.empty()`), a CUSTOMER auth, and each admin role. The classes are:
  - PERMIT: anonymous allowed;
  - DENY: nobody allowed;
  - AUTHENTICATED: CUSTOMER allowed;
  - ROLE{allowed set}: CUSTOMER refused, some roles allowed.
- `classify(method, path)` = the first matching entry. This is the real first-match evaluation (AC-2).
- Self-check, so an upgrade that changes Spring Security internals fails loudly instead of returning an empty table. All of these must hold:
  - every `PUBLIC_PATHS` entry (reflected from the private static field) appears as an ANY PERMIT;
  - `/actuator/**` is DENY;
  - `/api/admin/**` is ROLE;
  - the last entry is the catch-all AUTHENTICATED.

**2c. Public list: a reviewed table in the test, checked against the chain.**
- `REVIEWED_PERMITS`: a list of `(method|ANY, pattern, reason)`, one per PERMIT matcher (about 40 entries: the 15 PUBLIC_PATHS, health, and the method-specific ones).
- Assert set(chain PERMITs) == set(reviewed). A new permit fails until it is reviewed (AC-12), and a removed permit leaves a stale reviewed entry, which also fails.
- Trade-off: reflection on `PUBLIC_PATHS` alone cannot see the inline method permits. A reviewed table alone cannot see new narrow permits (for example `GET /api/orders/*/invoice` would escape the `/api/orders/**` probe). Chain introspection plus a reviewed table gives completeness plus a deliberate second key. The cost is coupling to Spring Security internals, accepted and fenced by the self-check. **Chosen.**

**2d. Unrouted allow-list.** `UNROUTED_ALLOWED`: a pattern-to-reason map. Today it holds `/actuator/health` and `/actuator/health/**` ("served by the gateway's own actuator, not a route").
- Every PERMIT pattern (PUBLIC_PATHS for AC-6, method-specific for AC-7) gets a concrete probe path (2e).
- At least one route `PathPattern` must match the probe, unless the pattern is allow-listed.
- An allow-listed pattern that does match a route fails (stale entry).

**2e. Probe paths.** Derive each from its pattern: `**` becomes `contract-probe`, `*` and `{x}` become `1`. Methods probed: GET, POST, PUT, PATCH, DELETE.

**2f. Driving the real chain.**
- Copy the `GatewayDenyListEdgeIntegrationTest` boot setup: `@SpringBootTest(RANDOM_PORT)` and `WebTestClient.bindToServer()` with a 20s timeout. Real Netty matters, because it does the real path normalisation for the edge cases.
- `TestKeys.generate("route-contract-kid")`; `StubHttpServer.start(KEYS::jwksJson)`, which serves JWKS only.
- `@DynamicPropertySource`: `gateway.jwt.jwks-uri`, `eureka.client.enabled=false`, `gateway.rate-limit.enabled=false`.
- `@MockitoBean ReactiveStringRedisTemplate`, whose `opsForValue().get(any)` returns `Mono.empty()` (deny-list miss).
- No LoadBalancer instances, so the downstream is unreachable and a pass-through shows as 503, never 401/403.
- Pass = status not in {401, 403}.
- Tokens: `KEYS.accessToken(sub, email, "password", List.of(role))` for CUSTOMER, ADMIN, SUPPORT, FINANCE, CATALOG_MANAGER (`Roles` constants where they exist; CUSTOMER is a literal), plus SELLER for Q1. Minted once per class.
- Use `@TestFactory` dynamic tests named `route/pattern/method/actor` so each failure names its row. The classification table (route, pattern, probe, method, class) is printed once.

## 3. AC to change mapping
| AC | Test (in RouteAuthorizationContractTest) |
|---|---|
| 1 | `routesAreParsedFromApplicationYml`: RouteTable fail-loud rules and id cross-check (2a) |
| 2 | `classificationTable`: every pattern × method classified through `EdgeRules.classify`; table printed; every class is one of the five |
| 3 | `nonPublicRoutesRequireAToken`: each non-PERMIT (pattern, method), no token, gives 401 + problem (`$.status` 401, `$.code` UNAUTHENTICATED). Covers user, cart (incl. wishlist), order. AUTHENTICATED rows with a CUSTOMER token pass |
| 4 | `publicEntriesPassWithoutToken`: every REVIEWED_PERMITS probe with its method and no token passes (incl. `/actuator/health`) |
| 5 | `methodSpecificPermitsAreMethodBound`: other methods on those probes give 401 (POST/PUT/DELETE `/api/products/1`, POST `/api/ratings/products/1`) |
| 6, 7 | `everyPermitHasARoute` + stale allow-list check (2d) |
| 8 | `adminPathsAreRoleRestricted`: every route pattern under `/api/admin/`. No token gives 401; CUSTOMER gives 403 `FORBIDDEN`; each of the 4 roles passes |
| 9 | `rolePrefixesAreKnown`: chain ROLE entries must equal `EXPECTED_ROLE_RULES = {/api/admin/** → the 4 authorities}`. A new role rule (e.g. seller) or a changed set fails |
| 10 | `actuatorIsClosed`: env/shutdown/anything give 401 with no token and 403 with ADMIN; health and health/liveness pass |
| 11 | `unmappedPathsDenyByDefault`: `/api/zz-unmapped/x` and `/internal/x` give 401 with no token and 404 with CUSTOMER |
| 12 | Follows from 2c (a new permit must be reviewed), 2d (a new PUBLIC_PATHS entry must be routed) and AC-3 (a new route falls to the catch-all) |
| 13 | Guard demo (section 5) |
| 14 | Runs under `forge-verify --changed`; no network/Redis/Eureka |
| 15 | Register edits (section 6) |
| 16 | The class name and package as stated; `git diff <base> -- services/gateway/src/main` is empty |

**Edge-case table**, explicit rows, so intent is readable:
- `POST /api/auth/2fa/verify` passes vs `/api/auth/2fa/contract-probe` gives 401.
- `/api/auth/email/verify` passes vs `.../resend` gives 401.
- `GET /api/sellers/me` gives 401 vs `/api/sellers/1` passes.
- `GET /api/orders/delivery-estimate` passes vs `/api/orders/1` gives 401.
- `POST /api/products/1/views` passes vs `POST /api/products/1` gives 401.
- Path variants with no token must be in {400, 401}, never a pass-through: `/api/orders/`, `/API/ORDERS`, `//api/orders`, `/api/admin/orders/..;/`, `/api/orders/%2e%2e/products`. Send them with `URI.create` so there is no client re-encoding.
- HEAD and plain OPTIONS on `/api/orders/1` give 401. A CORS preflight (allowed Origin + ACRM) gets a 200 CORS answer, and the follow-up GET still gives 401.
- Expired, malformed (`Bearer x.y`) and wrong-signature tokens (a second `TestKeys` with the same kid) on `/api/orders/1` give 401.
- Q1: `/api/seller/*` probes with a CUSTOMER or SELLER token pass. The test asserts today's behaviour, and its comment flags it as authenticated-only at the edge (per the CEO ruling).

## 4. Alternatives considered
- **Reviewed table plus behavioural probes only (no introspection).** Rejected: it misses narrow new permits that no route-derived probe hits (2c).
- **Regex over `SecurityConfig.java` source.** Rejected: it breaks on formatting and comments, and it reads the text rather than the rules that actually run.
- **`bindToApplicationContext` / `WebFluxTest` slice.** Rejected: no real Netty path handling for the variants, and the slice may not build the full chain.
- **Hard-coded route list.** Forbidden by AC-1.
- **Runtime `RouteLocator`.** Its predicates are opaque lambdas. It is used only as the id cross-check.

## 5. Guard demo (AC-13), Dev in the worktree, one Gradle build at a time
Run `bash .openforge/scripts/gradle.sh :services:gateway:test --tests '*RouteAuthorizationContractTest'` for each mutation below. Record the failing assertion text, then `git checkout -- services/gateway/src/main` and confirm with `git diff --exit-code -- services/gateway/src/main`.
- (a) Add `.pathMatchers("/api/orders/**").permitAll()` before `anyExchange`. Expected: "unreviewed permit ANY /api/orders/**" and an AC-3 row passing with no token.
- (b) Add `"/api/auth/zz-no-route"` to `PUBLIC_PATHS`. Expected: "PUBLIC_PATHS entry ... matches no route" (and an unreviewed permit).
- (c) Drop `ROLE_SUPPORT`, or change `/api/admin/**` to `authenticated()`. Expected: an AC-9 role-table mismatch and/or an AC-8 SUPPORT/CUSTOMER row.

Then run the final green run and `forge-verify --changed`, and paste both in `evidence.md`.

## 6. Register edits (AC-15) and files expected to change
- `docs/techdebt/testing.md`: delete lines 19-25 (the `td-test-04` anchor and its section).
- `docs/techdebt/README.md`:
  - delete the line 31 index row;
  - line 192: end with "Reframed as TD-TEST-04; fixed by `RouteAuthorizationContractTest` (F-20261119-17)." with no link.
- `docs/techdebt/phase9.md:14`: replace `([TD-TEST-04](testing.md#td-test-04))` with `(done: RouteAuthorizationContractTest, F-20261119-17)`.
- `docs/techdebt/remediation-plan.md`: add a row "TD-TEST-04 | gateway route/authz contract test | ✅ Fixed" (project.md convention), with no anchor link.
- New: `services/gateway/src/test/java/com/acme/ecommerce/gateway/RouteAuthorizationContractTest.java`, `.../support/RouteTable.java`, `.../support/EdgeRules.java`.
- Unchanged: all of `src/main` and `build.gradle`.

## 7. Reuse before build
- Reused:
  - `support/TestKeys` (real RS256 minting) and `support/StubHttpServer` (JWKS);
  - the `GatewayDenyListEdgeIntegrationTest` boot pattern;
  - `common-security` `Roles`;
  - Boot `YamlPropertySourceLoader`/`Binder`;
  - SCG `RouteDefinition`/`PredicateDefinition` (the predicate text parser);
  - Spring `PathPatternParser` (route matching, the same engine as `Path=`);
  - `ReflectionTestUtils`.
- `StubServiceInstances.none` is available but not needed: no instances is already the default.
- Checked for an existing route/authz contract test in other services (only API DTO `*ContractTest`s exist, unrelated), so there is nothing to extend. Nothing needs to move into a shared lib.

## Risks
- **Spring Security internals** (field names, observation wrappers) change on upgrade. Mitigation: confined to `EdgeRules`, and the self-check (2b) fails with "EdgeRules could not read the chain" rather than passing vacuously. Fallback if 7.0.6 hides the manager entirely: the Dev stops and reports. We do not drop to probes-only silently.
- **Vacuous pass** (zero routes, zero permits, empty table). Each count is asserted > 0, and the self-check pins known anchors.
- **Pass-through misread.** A downstream never runs, so 401/403 can only come from the edge. Circuit breakers opening after 10 calls still return a 503 fallback.
- **Runtime.** About 1.5k local requests (≈95 patterns × 5 methods × actors). Expected well under a minute; if slow, role rows are limited to GET+POST and the reason is recorded.
- **Path variants** may surface a real normalisation gap. Per Q2 the Dev stops and reports to the CEO; no production fix in this item.
- **Mutation demo left unreverted.** Covered by AC-16's `git diff --exit-code -- services/gateway/src/main`, checked by the reviewer at the named SHA.
- **Rollback.** Delete the three test files and revert the doc edits. There is no runtime impact.

## CTO decision
ACCEPT (2026-11-23), with conditions. This is the CTO's verdict as it reported it; the CEO recorded it here because the CTO role may write only complexity.md.
1. Keep the chain introspection, because a reviewed table alone cannot see narrow new permits. In the first task, the Dev spikes `EdgeRules` against Spring Security 7.0.6 and records the real field and manager method names; none are verified yet.
2. If the manager cannot be read, the fallback is a reviewed `(method, pattern, reason)` permit table plus behavioural probes of every route pattern and method. Probe with an anonymous, a CUSTOMER and each admin-role token, so a new `permitAll` is caught through its route. The Dev records the fallback in `evidence.md` and tells the CEO, and must not skip the check silently. Keep the self-check anchors and the `> 0` counts in either mode.
3. No `src/main` diff and no `build.gradle` diff, checked with `git diff --exit-code` at the reviewed SHA. The Q2 stop-and-report rule applies to any real exposure found.
4. The Q1 ruling is accepted: the test asserts that `/api/seller/**` is authenticated-only today and flags it. A seller role rule is a separate production change and needs CEO sign-off.
