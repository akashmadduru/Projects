# F-20261119-17 Requirements: gateway route/authorization contract test (TD-TEST-04)

## 1. Problem and users affected
`SecurityConfig` (javadoc) and `application.yml` both claim a `RouteAuthorizationContractTest` that does not exist. A route that
silently loses protection, a public entry with no route, or a drifted role rule would still build and run. Users affected: every
customer, seller and admin behind the edge, plus the Owner/security reviewer who relies on deny-by-default (notably `/api/users`,
`/api/cart`, `/api/wishlist`, `/api/orders`, which have no explicit rule).

## 2. Scope and non-goals
In scope: one test-only contract in `services/gateway/src/test` (plus any test fixtures), exercising the real `SecurityConfig` chain
against the routes parsed from `services/gateway/src/main/resources/application.yml`; removal of TD-TEST-04 from the register.
Facts the code supports: route ids auth-public, auth-secured, auth-jwks, auth-oauth2, product, inventory, user, cart, order, rating,
payment, promotion, notification, seller, support. Edge rules: `/actuator/health` permit, `/actuator/**` denyAll, `PUBLIC_PATHS`
(15 entries incl. `/oauth2/**`, `/login/oauth2/**`), method-specific permits (GET products/categories, ratings, promotions, sellers,
support, etc.), `/api/admin/**` limited to ROLE_ADMIN/SUPPORT/FINANCE/CATALOG_MANAGER, `anyExchange().authenticated()`.
Non-goals: no production behaviour change (SecurityConfig, application.yml, filters). A real exposure is reported to the CEO and
escalated, not fixed silently. No downstream-service authorization checks; no JWT/deny-list/CORS behaviour (already covered); no
new edge role rule for `/api/seller/**` (none exists today; see Q1); no prod-profile actuator port test; no `/internal` checks
(no `/internal` route exists in the table).

## 3. Acceptance criteria
- AC-1: Given `application.yml`, when the test runs, then it loads every entry under the gateway routes key by parsing the file (no
  hand-written route list) and every `Path=` predicate value (comma-split) is extracted; the test fails if zero routes or a route
  without a Path predicate is found.
- AC-2: Given the parsed routes, when classified, then every path pattern of every route is assigned a documented class
  (public-any-method, public-method-specific, authenticated, role-restricted, denied) by evaluating it against `SecurityConfig`'s
  real rules (not a copy of them), and the classification table is printed/asserted so a reviewer can read it.
- AC-3: Given each route path pattern classed non-public (including the `/api/users`, `/api/cart`, `/api/wishlist`, `/api/orders`
  routes), when a request (a concrete path derived from the pattern; each HTTP method that the pattern is not publicly open for)
  is sent with no Authorization header through the real security chain, then the response is HTTP 401 with the platform problem body.
- AC-4: Given each public-any-method entry in `PUBLIC_PATHS`, `/actuator/health`, and each method-specific public permit, when requested
  with no token using its permitted method, then the response is not 401/403 from the edge (the request passes security).
- AC-5: Given a method-specific public path (e.g. `/api/products/**`, `/api/categories/**`, `/api/ratings/products/*`), when
  requested with no token using a non-permitted method (POST/PUT/DELETE on products; POST to `/api/ratings/products/1`), then 401.
- AC-6: Given every `PUBLIC_PATHS` entry, when matched against the parsed route patterns, then each matches at least one route
  path; any entry without a route fails the test unless it is in a named allow-list in the test where each entry carries a written
  reason. The allow-list has no entries that actually do match a route (no stale allow-list).
- AC-7: Given every method-specific public permit in `SecurityConfig` (e.g. `/api/push/vapid-public-key`, `/api/payments/webhook`,
  `/api/ads/impressions`, `/sitemap.xml`), when matched against route patterns, then each is reachable via at least one route; same
  allow-list rule as AC-6.
- AC-8: Given every route path under `/api/admin/` (across routes auth-secured, product, inventory, order, rating, payment, promotion,
  notification, seller, support), when requested with a valid token whose roles are only CUSTOMER, then 403 with the platform
  problem body; with no token, 401; with each of ROLE_ADMIN, ROLE_SUPPORT, ROLE_FINANCE, ROLE_CATALOG_MANAGER the request passes
  the edge (not 401/403).
- AC-9: Given the role-restricted prefix set, when the test enumerates it, then it is derived from `/api/admin/**` and any other
  prefix whose rule is not plain `authenticated`; if a future rule adds a role prefix (e.g. seller), the test fails until a
  role assertion for it is added.
- AC-10: Given `/actuator/env`, `/actuator/shutdown` and `/actuator/anything`, when requested with no token and with an ADMIN token,
  then each is denied (401 without a token, 403 with an ADMIN token), while `/actuator/health` and `/actuator/health/liveness` are not.
- AC-11: Given a path that no route and no rule names (e.g. `/api/zz-unmapped/x`, `/internal/x`), when requested with no token, then
  401 (deny-by-default); with a valid CUSTOMER token it passes security (reaches routing, 404 from the gateway, not 401/403 from rules).
- AC-12: Given a route is added to `application.yml` whose path has no matching edge rule, when the test runs, then it still passes only
  if the path is authenticated by default, and a route whose path is covered by a new `permitAll` fails unless it is added to the
  test's reviewed public list with a reason. Likewise a new `PUBLIC_PATHS` entry with no route fails (AC-6).
- AC-13: Demonstration: the Dev records in `evidence.md` three mutation runs, each failing the new test and then reverted (working
  tree restored): (a) add a permitAll for a route prefix, (b) add a `PUBLIC_PATHS` entry with no route, (c) change the admin role
  rule or remove it. Each shows the failing assertion message and the final green run.
- AC-14: Given the test, when run via the project verify command (`node .claude/hooks/forge-verify.mjs --changed`), then it passes
  with the existing gateway suite and its output is recorded; it runs without external infrastructure (no network, Redis or
  Eureka needed; stubs from `support/` allowed).
- AC-15: Given `docs/techdebt/testing.md` and `docs/techdebt/README.md`, when the PR is complete, then the TD-TEST-04 section and its
  index row are removed, and the other references (phase9.md line naming TD-TEST-04, README note at the "RouteAuthorizationContractTest
  not updated" entry) no longer point at a missing anchor.
- AC-16: Given `SecurityConfig` javadoc references `RouteAuthorizationContractTest`, then the test class exists with exactly that
  name in `com.acme.ecommerce.gateway` test packages; no `src/main` file differs from the base commit.

## 4. Edge cases and abuse cases
- Overlap: `auth-secured` has `/api/auth/2fa/**` while `/api/auth/2fa/verify` is public; `/api/auth/email/verify/resend` must stay
  authenticated though `/api/auth/email/verify` is public. Assert both sides.
- Trailing slash and case variants (`/api/orders/`, `/API/ORDERS`, `/api/admin/orders/..;/`, double slash, encoded `%2e%2e`) of a
  protected path must not return a pass-through response without a token.
- `/api/sellers/me` is authenticated while `/api/sellers/*` is public (first-match ordering); `/api/orders/delivery-estimate` public
  while `/api/orders/**` is not; `/api/products/*/views` POST public, other POSTs not.
- Expired, malformed or wrong-signature token on a protected route returns 401, not pass-through.
- Method coverage: HEAD/OPTIONS on a protected path (CORS preflight handling must not be mistaken for an auth bypass).
- Path-less or `Host`/`Method` predicates added later: the parser fails loudly rather than skipping the route.
- Route ordering: a broad route shadowing a narrower one does not change the edge classification (security is path-based).

## 5. Open questions
- Q1 (non-blocking, report only): `/api/seller/**` has only `authenticated` at the edge (no role rule); the brief mentions seller as
  an example. Default: assert today's behaviour (authenticated, CUSTOMER token passes the edge) and flag it in the report; adding an edge
  role rule is a production change needing CEO sign-off, not part of this item.
- Q2 (non-blocking): if the test finds a real exposure (a route unexpectedly public or a role rule missing), Dev stops and reports to
  the CEO before touching production code.
