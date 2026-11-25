# F-20261119-03 Tasks: one trustworthy client IP

Worktree W = `C:/Users/Akash/Desktop/Projects/.openforge/worktrees/F-20261119-03` (branch `forge/F-20261119-03-shared-client-ip`, base fcf6f2a2).
Owner of every task: forge-backend-engineer. Verify command form: `bash C:/Users/Akash/Desktop/Projects/.openforge/scripts/gradle.sh -C W <tasks>` (W written out in full when run). One build at a time.
Package root is `com/acme/ecommerce`. Each task ships its own tests.

**Slices are committed separately.** Slice 1 (T1-T6) is its own commit and review checkpoint (reviewer is not the author, review names the commit SHA). Slice 2 (T7-T15) starts only after slice 1 is verified (CTO condition 4). Neither slice enables the referral IP check.

Size: slice 1 about 150 production lines (limit 600); slice 2 about 110 added production lines (limit 600). Tests and docs do not count toward the production limit. CTO condition 1 counts all changed lines per PR (tests, docs, deletions) at 600; slice 1 about 480, slice 2 about 470. If slice 2 nears 600, split T12 and T13/T14 into a third PR.

## Slice 1: gateway + contract (AC-1, 2, 3, 4, 5, 10, 13)

### T1. Shared parse/format in common-core
- Goal: add `ClientAddress` (`HEADER`, `parse`, `format`), parse moved verbatim from user-service `ClientIp.parse`. Copy (do not yet delete) the parse test cases into `ClientAddressTest`; user `ClientIpTest` is removed in T12.
- Files: `libs/common-core/src/main/java/com/acme/ecommerce/common/core/net/ClientAddress.java` (new); `libs/common-core/src/test/java/com/acme/ecommerce/common/core/net/ClientAddressTest.java` (new).
- ACs: AC-1, AC-2 (parse rules: bracketed, port, zone, mapped v4, >64 chars, hostname, never throws).
- Verify: `bash .../gradle.sh -C W :libs:common-core:test`

### T2. GatewayClientIp and RateLimitWebFilter refactor
- Goal: add `GatewayClientIp.resolve(ServerHttpRequest)` (N-hop rule moved from `RateLimitWebFilter.remoteIp`, same `@Value` property `gateway.rate-limit.trusted-proxy-hops`). `RateLimitWebFilter` uses it and drops `remoteIp`, `IP_LITERAL`, hops field; key is `"ip:" + format(ip)` or `unknown`. Add `GatewayHeaders.CLIENT_IP`. Contract test runs the AC-1/2 matrix plus null peer, and the strict-parser deviation cases (`::FFFF:1.2.3.4` canonicalised, `127.1`/`1` fall back to peer).
- Files: `services/gateway/src/main/java/com/acme/ecommerce/gateway/filter/GatewayClientIp.java` (new), `.../filter/RateLimitWebFilter.java`, `.../filter/GatewayHeaders.java`; test `services/gateway/src/test/java/com/acme/ecommerce/gateway/filter/GatewayClientIpContractTest.java` (new; the propagation half is completed in T3).
- ACs: AC-1, AC-2, AC-3 (rate-limit side), AC-13.
- CTO condition 2 (test part).
- Verify: `bash .../gradle.sh -C W :services:gateway:test --tests "*GatewayClientIpContractTest"`

### T3. ClientIpPropagationGatewayFilter
- Goal: GlobalFilter `ORDER = 1` that `set`s `X-Internal-Client-Ip` from `GatewayClientIp` + `ClientAddress.format`, or `remove`s it when empty. Unit test asserts `set` replaces repeated/mixed-case client values, order is after Identity (0) and before RouteToRequestUrl (10000), and extend `GatewayClientIpContractTest` so the key suffix equals the header for the whole matrix (empty iff `unknown`).
- Files: `.../gateway/filter/ClientIpPropagationGatewayFilter.java` (new); tests `.../filter/ClientIpPropagationGatewayFilterTest.java` (new), `GatewayClientIpContractTest.java`.
- ACs: AC-1, AC-2, AC-3 (propagation side), AC-4 (unit level).
- Verify: `bash .../gradle.sh -C W :services:gateway:test --tests "*ClientIpPropagationGatewayFilterTest" --tests "*GatewayClientIpContractTest"`

### T4. Through-the-gateway integration test
- Goal: `GatewayClientIpIntegrationTest` in `GatewayEdgeIntegrationTest` style (real Netty, real chain, `StubHttpServer` echo, `trusted-proxy-hops=1`). Cases: (a) `XFF: 9.9.9.9, 203.0.113.7` echoes `203.0.113.7`; (b) spoofed `X-Internal-Client-Ip: 6.6.6.6` twice in mixed case, and alongside valid XFF, on public, authenticated and rate-limited routes, echoes exactly one real value; AC-5: 401, 403, 429 and no-route requests reach the stub with no header (or not at all) and statuses are the same as without the change.
- Files: `services/gateway/src/test/java/com/acme/ecommerce/gateway/GatewayClientIpIntegrationTest.java` (new).
- ACs: AC-4, AC-5, AC-10(a), AC-10(b).
- Verify: `bash .../gradle.sh -C W :services:gateway:test --tests "*GatewayClientIpIntegrationTest"`

### T5. N=0 peer case (no silent skip)
- Goal: nested test in `GatewayEdgeIntegrationTest` (default N=0): client binds to `127.0.0.2`, assert echoed header `127.0.0.2` and not the stub-observed peer. Per CTO condition 3, add an equivalent non-skipping unit assertion in `ClientIpPropagationGatewayFilterTest` that the header equals the request's remote address, not the outbound socket. If `assumeTrue` skips, the skip is recorded in the verify output.
- Files: `services/gateway/src/test/java/com/acme/ecommerce/gateway/GatewayEdgeIntegrationTest.java`, `.../filter/ClientIpPropagationGatewayFilterTest.java`.
- ACs: AC-10(c), AC-2.
- CTO condition 3.
- Verify: `bash .../gradle.sh -C W :services:gateway:test --tests "*GatewayEdgeIntegrationTest" --tests "*ClientIpPropagationGatewayFilterTest"`

### T6. Slice 1 ADR note and verification gate
- Goal: add the strict-parser bounded deviation to the ADR Consequences (canonicalisation; loose non-literals fall back to the peer; one-minute bucket reset at deploy) and note it for the PR description. Run the full slice gate and record output in `work/F-20261119-03/verify-slice1.md`. Commit slice 1 as its own commit. Hand the commit SHA to a reviewer who is not the author.
- Files: `.openforge/knowledge/decisions/0002-client-ip-from-gateway.md`; `.openforge/work/F-20261119-03/verify-slice1.md` (new).
- ACs: AC-13 (and re-confirms AC-1 to 5, 10).
- CTO conditions 2, 3 (record skip if any), 5.
- Verify: `bash .../gradle.sh -C W :libs:common-core:test :services:gateway:test` then `node .claude/hooks/forge-verify.mjs --changed` (run in W).

## Slice 2: readers + docs (AC-6, 7, 8, 9, 11, 12)

### T7. Servlet resolver in common-web
- Goal: `ClientIp.resolve` / `resolveAsString` (exactly one header value that parses, else parsed remote address, else empty/`unknown`; never reads XFF; never throws). `ClientIpTest` covers header present, absent, malformed, comma-joined, two header lines, hostname (no DNS), remote null.
- Files: `libs/common-web/src/main/java/com/acme/ecommerce/common/web/net/ClientIp.java` (new); `libs/common-web/src/test/java/com/acme/ecommerce/common/web/net/ClientIpTest.java` (new).
- ACs: AC-6, AC-7.
- Verify: `bash .../gradle.sh -C W :libs:common-web:test`

### T8. AuditInterceptor
- Goal: `ip()` calls `ClientIp.resolveAsString`. Test sends the header plus `X-Forwarded-For: 9.9.9.9, real` and asserts the audit record has the real IP.
- Files: `libs/common-web/src/main/java/com/acme/ecommerce/common/web/audit/AuditInterceptor.java`; its existing test in `libs/common-web/src/test/...`.
- ACs: AC-6, AC-8.
- Verify: `bash .../gradle.sh -C W :libs:common-web:test`

### T9. WalletController per-IP limit
- Goal: redeem uses `ClientIp`. MockMvc test: header plus spoofed XFF yields the real-IP key; rotating XFF from one client gives the same bucket key.
- Files: `services/payment/src/main/java/com/acme/ecommerce/payment/web/WalletController.java`; the existing wallet web test in `services/payment/src/test/...`.
- ACs: AC-6, AC-8.
- Verify: `bash .../gradle.sh -C W :services:payment:test --tests "*Wallet*"`

### T10. Auth RequestDeviceContext and flag removal
- Goal: `RequestDeviceContext.clientIp` uses `ClientIp`; remove `trustForwardedFor` from `AuthProperties.RateLimit`, both `application.yml` lines, and fix the 2 tests that build `RateLimit`. `RequestDeviceContextTest` covers header vs remote, and flag no longer influences the result.
- Files: `services/auth/src/main/java/com/acme/ecommerce/auth/web/RequestDeviceContext.java`, `.../AuthProperties.java`, `services/auth/src/main/resources/application.yml`; the affected tests in `services/auth/src/test/...`.
- ACs: AC-6, AC-8, AC-9.
- Verify: `bash .../gradle.sh -C W :services:auth:test`

### T11. AdminUserController
- Goal: both `getRemoteAddr()` uses (lines 97, 116) call `ClientIp`. Test asserts the audit IP equals the header when present.
- Files: `services/auth/src/main/java/com/acme/ecommerce/auth/admin/AdminUserController.java`; its test in `services/auth/src/test/...`.
- ACs: AC-6, AC-9.
- Verify: `bash .../gradle.sh -C W :services:auth:test --tests "*AdminUser*"`

### T12. User-service referral flow
- Goal: `EngageController` (lines 87, 99) uses the common-web `ClientIp`. Delete user `ClientIp.java` and `ClientIpTest` (cases already moved in T1), remove `UserProperties.Referral.trustedProxyHops` and its yml key. Switch `ReferralEndpointsMvcTest` and `ReferralForwardedForTomcatTest` inputs from XFF to the header with the same assertions; lookback-0 tests unchanged.
- Files: `services/user-service/src/main/java/com/acme/ecommerce/user/web/ClientIp.java` (delete), `.../web/EngageController.java`, `.../config/UserProperties.java`, `services/user-service/src/main/resources/application.yml`; tests in `services/user-service/src/test/...`.
- ACs: AC-11.
- Verify: `bash .../gradle.sh -C W :services:user-service:test`

### T13. Helm and documentation (referral check stays off)
- Goal: remove `deployment.yaml:128-131` hops env; `values.yaml:49` comment and the `USER_REFERRAL_IP_LOOKBACK` doc state how to enable (set `USER_REFERRAL_IP_LOOKBACK` e.g. `30d` and `USER_REFERRAL_IP_HASH_SALT`), the precondition (`gateway.trustedProxyHops` confirmed against the real proxy count, CloudFront + ALB = 2, else all users share one IP and referrals are refused), the in-cluster header-trust caveat, and why `SERVER_FORWARD_HEADERS_STRATEGY` stays `none`. Remove the dropped env vars from `env-descriptions.json`/`environment-variables.md`; update `phase-08` doc and techdebt register/remediation plan (B-1, TD-P8-21). Default stays off.
- Files: Helm `deployment.yaml`, `values.yaml` (under `infra/`, locate by path above); `docs/reference/env-descriptions.json`, `docs/reference/environment-variables.md`, `docs/phases/phase-08-retention-and-loyalty.md`, `docs/techdebt/README.md`, `docs/techdebt/remediation-plan.md`.
- ACs: AC-12.
- CTO condition 4 (doc wording, default off).
- Verify: `bash .../gradle.sh -C W :services:user-service:test --tests "*Referral*"` (config unchanged in behaviour) plus `node .claude/hooks/forge-verify.mjs --changed` for the docs/Helm checks.

### T14. File the Feign relay backlog item
- Goal: add a backlog entry (service A to B Feign calls do not relay `X-Internal-Client-Ip`, B falls back to A's pod IP; none of the five readers is affected today) to the techdebt register, before slice 2 merges.
- Files: `docs/techdebt/README.md`.
- ACs: none (process).
- CTO condition 6.
- Verify: `node .claude/hooks/forge-verify.mjs --changed` (run in W).

### T15. Slice 2 verification gate
- Goal: run the full slice gate, record output in `work/F-20261119-03/verify-slice2.md`, confirm referral lookback default is still zero/off, commit slice 2 as its own commit. A reviewer who is not the author reviews against the commit SHA; slice 1 verified first.
- Files: `.openforge/work/F-20261119-03/verify-slice2.md` (new).
- ACs: re-confirms AC-6, 7, 8, 9, 11, 12.
- CTO conditions 1, 4, 5.
- Verify: `bash .../gradle.sh -C W :libs:common-web:test :services:payment:test :services:auth:test :services:user-service:test` then `node .claude/hooks/forge-verify.mjs --changed` (run in W).

## Coverage table

| AC | Tasks |
|---|---|
| AC-1 | T1, T2, T3, T4, T6 |
| AC-2 | T1, T2, T3, T5, T6 |
| AC-3 | T2, T3 |
| AC-4 | T3, T4, T6 |
| AC-5 | T4, T6 |
| AC-6 | T7, T8, T9, T10, T11 |
| AC-7 | T7 |
| AC-8 | T8, T9, T10 |
| AC-9 | T10, T11 |
| AC-10 | T4 (a, b), T5 (c), T6 |
| AC-11 | T12 |
| AC-12 | T13 |
| AC-13 | T2, T6 |

## CTO condition mapping

| Condition | Tasks |
|---|---|
| 1. At most 600 changed lines per slice, split slice 2 if near | Size note above; T15 (check diff size before commit) |
| 2. Strict-parser deviation named in PR description and ADR; contract test covers it | T2 (test), T6 (ADR, PR text) |
| 3. AC-10(c) must not silently skip | T5, T6 |
| 4. Slice 2 after slice 1 verified; referral check not enabled; docs wording | Slice header, T13, T15 |
| 5. Record forge-verify per slice; different reviewer on commit SHA | T6, T15 |
| 6. Feign relay backlog item before slice 2 merges | T14 |
