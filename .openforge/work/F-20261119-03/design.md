# F-20261119-03 Design: one trustworthy client IP (gateway decides, services read `X-Internal-Client-Ip`)

Base: head of `forge/F-20261119-01` (worktree `.openforge/worktrees/F-20261119-01`). Paths are `com/acme/ecommerce`. ADR: `.openforge/knowledge/decisions/0002-client-ip-from-gateway.md`.

## 1. Current behaviour
- Gateway rule: `services/gateway/.../filter/RateLimitWebFilter.java:140-157` `remoteIp()`: if `gateway.rate-limit.trusted-proxy-hops` N>0 (`:53`), take the N-th XFF entry from the right if it matches the loose regex `IP_LITERAL` (`:44-45`, accepts `1`, `1.2.3.4:80`, and returns the raw string). Otherwise use the socket peer, or `"unknown"`. Used only for the anonymous key `"ip:"+…` (`:127`). No unit test exists for this filter.
- Edge header namespace: `GatewayHeaders.java:52-53` treats `x-user-`, `x-auth-` and `x-internal-` as untrusted. `InboundHeaderSanitizationWebFilter.java:37,43-69` strips them at WebFilter order -300, the first filter in the process. `IdentityPropagationGatewayFilter.java:44,49-61` is a GlobalFilter (order 0) that sets the X-User-* headers on the proxied request from the token only.
- XFF never reaches services: SCG 5.0.2 `RemoveXForwardedHeadersFilter` drops `x-forwarded-*` on proxy (objective.md fact 1). Every downstream "client IP" through the gateway is therefore the gateway pod IP today:
  - `libs/common-web/.../audit/AuditInterceptor.java:90-96`: leftmost XFF, else remote address.
  - `payment/web/WalletController.java:101-103`: leftmost XFF, else remote address. The TD-P8-21 per-IP limit is effectively one bucket per gateway pod, and it is forgeable by a direct in-cluster caller.
  - `auth/web/RequestDeviceContext.java:39-52`: leftmost XFF if `auth.rate-limit.trust-forwarded-for`. This flag is `true` in prod (`application.yml:311-315`), but XFF is stripped, so login throttling buckets on the gateway pod.
  - `auth/admin/AdminUserController.java:97,116`: `getRemoteAddr()`.
  - `user/web/ClientIp.java:27-77`: the N-th XFF entry from the right, with hops = gateway N + 1 (`deployment.yaml:128-131`), then a strict `InetAddress.ofLiteral` parse. Its callers are `EngageController.java:87,99`.

## 2. Proposed change
**One rule, in one place (gateway, reactive).** Add `gateway/filter/GatewayClientIp` (`@Component`) with `Optional<InetAddress> resolve(ServerHttpRequest)`. It holds the N-hop rule moved from `RateLimitWebFilter.remoteIp`, and its `@Value("${gateway.rate-limit.trusted-proxy-hops:0}")` keeps the property name, so Helm is unchanged. Candidates are parsed with the shared literal parser below. An absent or unparseable peer returns empty.
- `RateLimitWebFilter`: inject `GatewayClientIp`, drop its own `remoteIp`, `IP_LITERAL` and hops field. The key becomes `"ip:" + resolve(req).map(ClientAddress::format).orElse("unknown")`.
- New `gateway/filter/ClientIpPropagationGatewayFilter` (GlobalFilter, `ORDER = 1`). On the request to be proxied, it calls `headers.set(X-Internal-Client-Ip, format(ip))` when the IP is present, else `headers.remove(...)`. It is a separate filter so that `IdentityPropagationGatewayFilter` keeps its "token is the only source" invariant (`:31-34`). `GatewayHeaders` gains `CLIENT_IP = ClientAddress.HEADER`.

**Parse/format shared (framework-free, common-core).** New `libs/common-core/.../common/core/net/ClientAddress`:
- `HEADER = "X-Internal-Client-Ip"`;
- `Optional<InetAddress> parse(String)`, moved verbatim from user-service `ClientIp.parse`: trims; rejects >64 chars; strips `[]`, zone id and v4 `:port`; dotted quad only; `InetAddress.ofLiteral`, never DNS; never throws;
- `String format(InetAddress)`: `InetAddress.getByAddress(bytes).getHostAddress()`, canonical and scope-free.

The gateway gets common-core through common-security (`gateway/build.gradle:32-38`).

**One servlet resolver (common-web).** New `libs/common-web/.../common/web/net/ClientIp`:
- `Optional<InetAddress> resolve(HttpServletRequest)`: exactly one `X-Internal-Client-Ip` value that `parse`s; else `parse(getRemoteAddr())`; else empty. Never reads XFF. Never throws.
- `String resolveAsString(HttpServletRequest)`: canonical string or `"unknown"`.

Reactive/servlet split: common-web is deliberately not a gateway dependency (`gateway/build.gradle:34-38`), and common-core must stay servlet-free (`libs/common-core/build.gradle:6`). So the parse/format/header contract lives in common-core and is used by both sides. Only the request adapters differ: `GatewayClientIp` for the reactive gateway, `ClientIp` for servlet services. No new module.

**Readers migrated** (all call `ClientIp`):
- AuditInterceptor `ip()`;
- WalletController redeem;
- `RequestDeviceContext.clientIp`: flag removed, which also removes `trustForwardedFor` from `AuthProperties.RateLimit` and both yml lines;
- AdminUserController (×2);
- EngageController (×2): delete user-service `ClientIp`, `UserProperties.Referral.trustedProxyHops`, its yml key, and Helm `deployment.yaml:128-131`.

**Filter ordering (the guarantee).** Sanitization WebFilter -300 (`InboundHeaderSanitizationWebFilter.java:37`), then Correlation -250, then Security -100, then RateLimit -90 (`RateLimitWebFilter.java:63-65`), then DispatcherHandler. `RoutePredicateHandlerMapping` selects `FilteringWebHandler` only when a route matches, then the GlobalFilters run: Identity 0, then ClientIp 1, then `RouteToRequestUrlFilter` 10000, LB 10150, `NettyRoutingFilter`. Consequences:
- (a) The strip of every client `x-internal-*` (any case, any count) finishes before any component reads or sets the header.
- (b) The header is set only inside `FilteringWebHandler`, so only routed, proxied requests carry it. 401/403 (security), 429 (rate limit), unmatched paths (404/deny) and local handlers (`/actuator`, `/__fallback`) never reach it.
- (c) `set` replaces all values: belt and braces with the strip. Status codes are untouched.

**Data/migrations:** none. **Config:** removed `AUTH_TRUST_FORWARDED_FOR` / `auth.rate-limit.trust-forwarded-for` and `USER_REFERRAL_TRUSTED_PROXY_HOPS` / `user.referral.trusted-proxy-hops`. Spring ignores stale env vars, so this is safe. **API/events:** one additive internal header; no DTO change.

## 3. AC mapping
- AC-1, AC-2: `GatewayClientIp.resolve` plus the propagation filter. Unit matrix: N=0; too few entries; no XFF; non-literal; `ip:port`; bracketed v6; mapped v4; >64 chars.
- AC-3: both RateLimitWebFilter and the propagation filter call `GatewayClientIp.resolve` and format the result with `ClientAddress.format`. `GatewayClientIpContractTest` runs the AC-1/2 matrix plus a null peer through both filters (capturing Redis mock + captured proxied request) and asserts key suffix == header (empty ⇔ `unknown`).
- AC-4: strip at -300 plus `set` at GlobalFilter 1. IT sends `x-internal-client-ip: 6.6.6.6` in mixed case, twice, and alongside a valid XFF, on public, authenticated and rate-limited routes.
- AC-5: ordering (b). The IT asserts the stub saw no request (or no header) for 401/403/429/no-route, and that the statuses equal today's.
- AC-6, AC-7: common-web `ClientIp` and `ClientIpTest`. MockHttpServletRequest covers the header present; absent; malformed; comma-joined; two header lines; hostname (no DNS: `ofLiteral`); remote null → `unknown`.
- AC-8: readers use `ClientIp`. MockMvc tests in payment (wallet key), auth (device context) and the common-web AuditInterceptor test send header + `X-Forwarded-For: 9.9.9.9, real` and assert the real IP. The wallet test proves that rotating XFF yields the same key.
- AC-9: flag deleted. `RequestDeviceContextTest` covers header vs remote. The AdminUserController test asserts that the audit IP equals the header.
- AC-10: see section 5.
- AC-11: EngageController uses `ClientIp`. `ReferralEndpointsMvcTest` and `ReferralForwardedForTomcatTest` switch their input from XFF to the header, with the same assertions. The lookback-0 tests are unchanged.
- AC-12: the `USER_REFERRAL_IP_LOOKBACK` doc and the Helm `values.yaml:49` comment state how to turn the check on: set `USER_REFERRAL_IP_LOOKBACK` (e.g. `30d`) and `USER_REFERRAL_IP_HASH_SALT`. Precondition: `gateway.trustedProxyHops` equals the real proxy count (CloudFront + ALB = 2), else every user shares one IP and referrals are refused.
- AC-13: no RateLimitWebFilter test exists today. The new contract test covers its key. `forge-verify --changed` passes per slice.

## 4. Reuse before build (what was found)
- user-service `ClientIp.parse`: strict, uses `InetAddress.ofLiteral` (JDK 25, no DNS). **Reused:** moved verbatim to common-core `ClientAddress.parse`, and its `ClientIpTest` parse cases move with it.
- `RateLimitWebFilter.remoteIp`: **Reused:** moved into `GatewayClientIp`. Its loose regex is replaced by the strict parser (see risks).
- `GatewayHeaders` / `InboundHeaderSanitizationWebFilter` / the `x-internal-` prefix: **Reused** as is, with no change to the strip logic.
- Spring `ForwardedHeaderFilter`, Tomcat `RemoteIpValve`, Netty `forward-headers-strategy`: leftmost or regex-trusted-proxy semantics, configured per service. **Not used.** SCG `trusted-proxies` is excluded by the requirements.
- `CorrelationAndAuthForwardingInterceptor` (Feign) does not relay the header. Out of scope (section 5).

## 5. Alternatives, risks, tests, rollout
**Alternatives rejected.**
- (1) Set SCG `trusted-proxies` and let each service count N+1 from the right of XFF. This is a non-goal, puts the rule in 5+ places, and needs hops kept in sync per service (the bug class F-01 hit).
- (2) Put the IP in `IdentityPropagationGatewayFilter`. Fewer lines, but it breaks that filter's "token is the only source" invariant and changes its constructor and tests.
- (3) Compute once in a WebFilter and store it as an exchange attribute. This adds a filter and ordering coupling for a pure function; calling the same method twice gives AC-3 more cheaply.

**Risks and failure modes.**
- Rate-limit key canonicalisation: `::FFFF:1.2.3.4` and upper-case v6 now share a bucket, and loose non-literals (`127.1`, `1`) now fall back to the peer. The impact is a one-minute bucket reset at deploy. This is intended, not a behaviour change of the limiter.
- Wrong N: the fleet shares one IP. Now visible in audit too; documented (AC-12).
- An in-cluster caller can set the header. This is the same ClusterIP trust as X-User-* (objective fact 2) and is stated in the docs.
- If someone sets the gateway `SERVER_FORWARD_HEADERS_STRATEGY` to `native`, the N=0 peer becomes the leftmost XFF. Helm pins `none` (`deployment.yaml:125-126`); the docs line says why.
- Feign hops (service A → B) carry no header, so B falls back to A's pod IP. None of the five readers is on a Feign path today. A follow-up backlog item would relay the header in the Feign interceptor.
- Auth uses `SERVER_FORWARD_HEADERS_STRATEGY=framework`, so its remote-address fallback (header absent) may honour a forwarded header. This is only reachable in-cluster.

**Rollout and migration.** If the header is absent, readers fall back to the remote address, which is what they effectively see today (the gateway pod IP, because XFF is stripped). So any order is safe:
- services before the gateway: no regression;
- gateway before services: the header is ignored until the services update.

Recommended order: slice 1 (gateway), then slice 2 (services). The referral check stays off by default in both. Rollback is a per-service image revert; there is no data. Watch login throttling: after slice 2 it buckets per real client instead of per gateway pod (stricter per user, looser globally).

**AC-10 test (through the gateway).** New `GatewayClientIpIntegrationTest` in the `GatewayEdgeIntegrationTest` style: real Netty, real filter chain, `StubHttpServer` echo downstream (it echoes all headers, joining repeats with `,`, `StubHttpServer.java:117-123`), `StubServiceInstances`, `trusted-proxy-hops=1`.
- (a) `XFF: 9.9.9.9, 203.0.113.7` → echoed header == `203.0.113.7`.
- (b) a spoofed `X-Internal-Client-Ip: 6.6.6.6` (×2, mixed case) → exactly one value, the real one.
- (c) An added nested test in `GatewayEdgeIntegrationTest` (default N=0). The client binds its source to `127.0.0.2` (Reactor Netty `bindAddress`; Linux docker loopback /8), the gateway's own socket to the stub is `127.0.0.1`. Assert the header == `127.0.0.2` and != the stub-observed peer; `assumeTrue` if the bind fails.

The servlet half of the hop is proven by `ClientIpTest` and the reader MockMvc tests that feed the same header.

## 6. Files and slices (each PR < 600 changed lines)
**Slice 1, gateway + contract (AC-1, 2, 3, 4, 5, 10, 13), about 150 production + 330 test lines.**
- Production: common-core `net/ClientAddress.java` (new); gateway `filter/GatewayClientIp.java` (new), `filter/ClientIpPropagationGatewayFilter.java` (new), `RateLimitWebFilter.java`, `GatewayHeaders.java`.
- Tests: `ClientAddressTest`, `GatewayClientIpContractTest`, `ClientIpPropagationGatewayFilterTest`, `GatewayClientIpIntegrationTest`, `GatewayEdgeIntegrationTest` (+N=0 case).

**Slice 2, readers + docs (AC-6, 7, 8, 9, 11, 12), about 110 production added / 120 removed, about 200 test, 40 doc lines.**
- Production:
  - common-web `net/ClientIp.java` (new) and `audit/AuditInterceptor.java`;
  - payment `WalletController.java`;
  - auth `RequestDeviceContext.java`, `AuthProperties.java`, `AdminUserController.java`, `application.yml`, plus the 2 tests that build `RateLimit`;
  - user `ClientIp.java` (delete), `EngageController.java`, `UserProperties.java`, `application.yml`;
  - Helm `deployment.yaml`, `values.yaml`.
- Tests: common-web `ClientIpTest`, `AuditInterceptor` test; the payment, auth and user tests named above; delete user `ClientIpTest` (its cases moved).
- Docs: `docs/reference/env-descriptions.json` and `environment-variables.md`, `docs/phases/phase-08-retention-and-loyalty.md`, the `docs/techdebt` register and `remediation-plan.md` (B-1, TD-P8-21).

## CTO decision
**ACCEPT with conditions.** Class L: the Owner's approval is still required after this verdict, before implementation.

Verified: strip filter order is -300 (`InboundHeaderSanitizationWebFilter.java:37`), Correlation -250, Identity GlobalFilter order 0, and `RateLimitWebFilter` holds the `IP_LITERAL` regex and `trusted-proxy-hops` rule being moved. The design is minimal: one rule in `GatewayClientIp`, one servlet reader `ClientIp`, shared parse in existing common-core, no new module, service, datastore or vendor. Every AC has a named test. The rollout is safe in any order because an absent header falls back to the remote address.

On the "services see the gateway pod IP today" risk: it is the defect this objective fixes, not a new requirement. Slice 2 resolves it for the five readers and the design says so. No scope increase.

Conditions:
1. Each slice must stay at or below 600 changed lines (tests and docs included, deletions counted). Slice 2 must split if it nears the limit; the natural cut is user and Helm/docs into a third PR.
2. The strict-parser change to rate-limit keys (canonicalisation, loose non-literals fall back to the peer) must be named in the PR description and the ADR Consequences as a bounded deviation from "no change to rate-limit behaviour". The contract test must cover it.
3. The AC-10(c) bind-to-127.0.0.2 test must not silently skip in CI. If `assumeTrue` skips there, record that in the verify output, or add an equivalent unit assertion that the header equals the request peer rather than the outbound socket.
4. Slice 2 may not merge before slice 1 is verified, and neither slice may enable the referral IP check. The docs for AC-12 must state that it is enabled only after `trusted-proxy-hops` is confirmed against the real proxy count.
5. Record `forge-verify --changed` output per slice. A different agent from the author must review each PR against its commit SHA.
6. The Feign relay gap must be filed as a backlog item before slice 2 merges.
