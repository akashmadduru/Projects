# 0002 The gateway decides the client IP once; services read `X-Internal-Client-Ip` through one resolver

## Status
Accepted (2026-11-19). Class L: CTO accepted; Owner standing approval under run R-20261119-01.

## Context
Five readers derive the client IP five ways (backlog B-1, TD-P8-21):
- AuditInterceptor and WalletController use the leftmost `X-Forwarded-For`.
- auth `RequestDeviceContext` uses the leftmost XFF behind a flag.
- AdminUserController uses the socket address.
- user-service `ClientIp` uses the N-th XFF entry from the right.

Spring Cloud Gateway 5.0.2 strips `x-forwarded-*` before proxying unless `trusted-proxies` is set, so every reader behind the gateway actually sees the gateway pod IP. As a result:
- the wallet per-IP limit and auth login throttling bucket per gateway pod;
- the audit IP is useless;
- the referral IP check (ADR 0001) cannot be enabled.

Only the gateway sees the real edge. Its `RateLimitWebFilter` already applies the right rule: the N-th XFF entry from the right with N = `trusted-proxy-hops`, else the socket peer.

## Decision
- The gateway computes the client IP in one component (`GatewayClientIp`), which holds the N-hop rule moved out of `RateLimitWebFilter`. The rate limiter's anonymous key and a new GlobalFilter (`ClientIpPropagationGatewayFilter`, order 1) both use it. The filter sets `X-Internal-Client-Ip` on proxied requests only, or removes it when the peer is unresolvable.
- The header is trustworthy because the `x-internal-` namespace is stripped from every inbound request at WebFilter order -300, before security (-100), the rate limiter (-90) and any GlobalFilter. The GlobalFilter also uses `set`, which replaces any value.
- IP literal parsing (no DNS, `InetAddress.ofLiteral`, max 64 chars), canonical formatting and the header name live in framework-free `libs/common-core` (`net/ClientAddress`), so the reactive gateway and servlet services share them.
- Servlet services read the IP only through `libs/common-web` `net/ClientIp`. It takes a single well-formed header value, else the remote address, else `"unknown"`. It never reads XFF and never throws.
- The five readers use `ClientIp`. `auth.rate-limit.trust-forwarded-for` and `user.referral.trusted-proxy-hops` are removed. The referral IP check stays off by default; the docs state how to enable it and that its precondition is a correct `trusted-proxy-hops`.

## Alternatives considered
- **SCG `trusted-proxies` plus per-service right-hand XFF parsing:** this was a non-goal. It also duplicates the rule in every service and needs the hop counts kept in sync. Rejected.
- **Add the header in `IdentityPropagationGatewayFilter`:** fewer lines, but it breaks that filter's "token is the only source" invariant. Rejected.
- **Compute once in a WebFilter and pass it via an exchange attribute:** an extra ordering coupling for a pure function. Rejected.
- **Spring `ForwardedHeaderFilter`, Tomcat `RemoteIpValve`, Netty forwarded strategy:** these trust the leftmost entry or use regex trusted-proxy semantics, and they are configured per service. Rejected.

## Consequences
- Every service sees the same canonical client IP:
  - audit records become meaningful;
  - the wallet limit and login throttling become per client;
  - the referral IP check can be enabled.
- Bounded deviation from "no change to rate-limit behaviour" (slice 1; name it in the PR): `RateLimitWebFilter` now uses the strict `ClientAddress.parse` instead of the regex `^[0-9a-fA-F:.]{2,45}$`. Rate-limit keys change as follows:
  - canonicalisation: v4-mapped `::FFFF:1.2.3.4` becomes `1.2.3.4`; v6 case variants merge; v6 is printed in full form;
  - `ip:port` entries have the port stripped (`1.2.3.4:80` was key `ip:1.2.3.4:80`, now `ip:1.2.3.4`), so per-port buckets merge;
  - bracketed v6 (`[2001:db8::1]:443`) used to fall back to the peer and now resolves to the address;
  - `127.1`-style shorthand IPv4, which the regex accepted, now falls back to the peer;
  - an empty right-hand XFF entry (trailing comma, as in `1.1.1.1,`) now falls back to the socket peer.
  `1` and over-long entries are not deviations: the old regex already rejected them. At deploy this costs a one-minute bucket reset. Covered by `GatewayClientIpContractTest`.
- All `X-Forwarded-For` field lines are now joined (RFC 9110 5.3) before counting N from the right (slice 1 round 2). Previously only the first line was read, so a client-sent extra line could have supplied the counted entry if an edge proxy appended its hop as a separate line.
- Trust boundary. When `X-Internal-Client-Ip` is absent, consumers fall back to the socket peer. Fix round 2 (a04ad61) keeps forwarded headers out of that fallback:
  - **auth** keeps `forward-headers-strategy=framework`, because the OIDC callback needs the forwarded host and proto. Its filter always pins `getRemoteAddr`, `getRemoteHost` and `getRemotePort` to the socket peer, so neither `X-Forwarded-For` nor `Forwarded: for=` can become the fallback address.
  - **Every other service** runs with `SERVER_FORWARD_HEADERS_STRATEGY=none`, the Helm default.
  - **Inside the cluster**, a caller can still set `X-Internal-Client-Ip` directly. This is the same ClusterIP trust that `X-User-*` relies on.
  - **Hop count:** a wrong `trusted-proxy-hops` makes the whole fleet share one IP.
- Service-to-service (Feign) calls do not relay the header, and the callee falls back to the caller pod IP. A follow-up is needed if a reader moves behind Feign.
- Rollout in any order is safe: an absent header means falling back to the remote address, which is what readers see today. Recommended order: gateway first. There is no data change, and rollback is an image revert.

## Deployment precondition
The right-hand N-hop rule (N = `trustedProxyHops`, 2 = CloudFront + ALB, `deploy/aws/helm/ecommerce/values.yaml:50`) is only safe if the gateway can be reached solely through those N proxies. Otherwise a client can write the entry N from the right. This now matters for audit, the wallet limit, login throttling and the referral check, not only a rate-limit bucket. What the repo shows today (config read, not checked against a live account):
- ALB: `internet-facing` (`deploy/aws/helm/ecommerce/templates/network.yaml:117`), and `api.<domain>` is public DNS for it. It is restricted to the CloudFront origin-facing prefix list only when `cloudfrontPrefixListId` is set (`network.yaml:136-140`). `deploy.sh` sets it when `RESTRICT_ALB_TO_CLOUDFRONT=true` (the default, `deploy/aws/lib/common.sh:85`, `deploy/aws/config.example.env:78`). The restriction fails open: if the prefix list lookup fails, `deploy/aws/lib/stages.sh:316-327` only warns and the ALB accepts traffic from anywhere.
- No secret origin header: the CloudFront `api` origin (`deploy/aws/terraform/modules/edge/main.tf:263-274`) sends no custom header, and the ALB does not check one.
- Gateway Service: `ClusterIP` (`deploy/aws/helm/ecommerce/templates/service.yaml:23`), so not public. Its NetworkPolicy, however, admits the whole VPC CIDR on the gateway port (`network.yaml:61-69`), so any VPC host or pod can call it directly and choose the XFF. This is within the in-cluster trust boundary above.

**Release gate B-4.** It covers all four consumers: audit, the wallet per-IP limit, auth login throttling and the device record, and referral. Before release, one of these must hold:
- the prefix-list restriction is mandatory and fails closed;
- the ALB verifies a CloudFront secret origin header;
- the Owner explicitly accepts the risk.

## Work id
F-20261119-03 (backlog B-1, TD-P8-21). Design: `.openforge/work/F-20261119-03/design.md`.
