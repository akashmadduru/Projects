# F-20261119-03 One trustworthy client IP for every service (backlog B-1)

## Objective (verbatim)
> (Owner) "go ahead and complete pending works without my intervention" — pending item: backlog B-1
> "one shared client-IP resolver; AuditInterceptor uses leftmost XFF (forgeable audit IP)", which also
> unblocks the referral IP check from F-20261119-01 (shipped disabled).

## Goal
The gateway decides the client IP once, with the rule it already uses for rate limiting, and passes it
downstream in a header clients cannot set; every service reads the client IP from one shared resolver.

## Affected areas (proved by search; excerpts read)
- `services/gateway/.../filter/RateLimitWebFilter.java:134-153`: client = N-th entry from the RIGHT of
  X-Forwarded-For when `gateway.rate-limit.trusted-proxy-hops` N > 0, else the socket peer. Helm sets N
  (`deploy/aws/helm/ecommerce/templates/deployment.yaml:124`).
- `services/gateway/.../filter/GatewayHeaders.java`: `x-internal-` is in UNTRUSTED_INBOUND_PREFIXES, stripped
  from every inbound request by InboundHeaderSanitizationWebFilter, so a header in that namespace cannot be
  forged by a client. `IdentityPropagationGatewayFilter` already adds trusted X-User-* headers downstream.
- Readers of the client IP today (5, all different):
  - `libs/common-web/.../audit/AuditInterceptor.java:91-95`: LEFTMOST X-Forwarded-For (forgeable audit IP).
  - `services/payment-service/.../web/WalletController.java:101-103`: LEFTMOST X-Forwarded-For for the
    TD-P8-21 per-IP limit (forgeable: the limit can be bypassed by rotating the header).
  - `services/auth-service/.../web/RequestDeviceContext.java:40-50`: X-Forwarded-For only if
    AUTH_TRUST_FORWARDED_FOR (default false), else remote address.
  - `services/auth-service/.../admin/AdminUserController.java:97,116`: `getRemoteAddr()`.
  - `services/user-service/.../ClientIp` (on branch forge/F-20261119-01, not yet on base): N-th from right.

## Runtime facts, with proof (lesson from F-20261119-01: no unproven runtime assumptions)
1. Spring Cloud Gateway 5.0.2 strips all `x-forwarded-*` request headers before proxying unless
   `spring.cloud.gateway.server.webflux.trusted-proxies` is set. Proof: `spring-cloud-gateway-server-webflux-5.0.2.jar`
   bytecode — `RemoveXForwardedHeadersFilter.filter` drops names starting with "x-forwarded-"; the bean is
   registered in `GatewayAutoConfiguration` under `TrustedProxies$NotXForwardedTrustedProxiesCondition`;
   property name constant `spring.cloud.gateway.server.webflux.trusted-proxies`. No repo file sets it
   (grep). So downstream services cannot rely on X-Forwarded-For at all today.
2. Downstream services are ClusterIP (`deploy/aws/helm/ecommerce/templates/service*.yaml:23`), reached only
   through the gateway; the existing X-User-* trust model already depends on this.
3. The gateway's own client-IP rule (fact in RateLimitWebFilter) is the only place that sees the real edge.

## Assumptions
- A1: Reusing the x-internal- namespace for `X-Internal-Client-Ip` is safe because of fact 1 in
  GatewayHeaders (inbound strip). The design must cite the strip filter's order relative to the new filter.
- A2: Base = final head of forge/F-20261119-01 (it contains ClientIp and the disabled referral IP check),
  so this objective can switch user-service to the shared resolver and document how to enable the check.

## Related
- F-20261119-01 security r3 (major) and minor (Google OIDC callback relies on X-Forwarded-Host/Proto —
  filed in the register by round 4; out of scope here unless the design shows it is the same fix).
- TD-P8-21 (wallet per-IP limit), TD-P8-24.

## Blocking questions
None. Owner delegated decisions; CEO decisions are logged in log.md.
