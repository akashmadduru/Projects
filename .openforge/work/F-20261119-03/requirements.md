# F-20261119-03 Requirements: one trustworthy client IP for every service

## 1. Problem and users affected
Five places derive the client IP five different ways. Two trust the leftmost X-Forwarded-For (XFF), which
the caller can forge (audit trail; wallet per-IP limit). Spring Cloud Gateway strips XFF before proxying, so
downstream services cannot see the real client at all. Affected: security/audit reviewers (forged audit IP),
payments operators (limit bypass), and the referral-abuse check, shipped disabled until the IP is trustworthy.

## 2. Scope
- The gateway computes the client IP once, with the rule RateLimitWebFilter uses today, and sends it
  downstream as `X-Internal-Client-Ip`.
- One shared resolver, in an existing shared lib, replaces the readers in AuditInterceptor, WalletController
  (per-IP limit), auth `RequestDeviceContext`, auth `AdminUserController`, and user-service `ClientIp`.
- Documentation for enabling the referral IP check.

## Non-goals
- OIDC callback forwarded host/proto (separate item). IPv6 normalisation beyond what exists today.
- Enabling the referral IP check by default (see AC-12). New infrastructure, services, datastores,
  dependencies, or the gateway `trusted-proxies` property. Changing the rate-limit behaviour itself.

## 3. Acceptance criteria
- AC-1: Given `trusted-proxy-hops` N>0 and XFF with at least N entries whose N-th from the right is an IP
  literal, when a request passes the gateway, then the downstream request carries `X-Internal-Client-Ip` equal to that entry.
- AC-2: Given N=0, fewer than N XFF entries, no XFF, or a non-IP-literal entry, when a request passes the
  gateway, then `X-Internal-Client-Ip` equals the socket peer address.
- AC-3: Given any request, then the value the gateway puts in `X-Internal-Client-Ip` and the key
  RateLimitWebFilter uses for an anonymous caller come from one shared implementation; a test fails if they
  differ for the same input (AC-1/2 input matrix, including the unresolvable-peer case).
- AC-4: Given a client sends `X-Internal-Client-Ip: 6.6.6.6` (any letter case, repeated, or alongside a
  valid XFF), when the request reaches any downstream service, then the downstream sees exactly one
  `X-Internal-Client-Ip`, the gateway-computed value, never 6.6.6.6. This holds on public, authenticated and
  rate-limited routes alike.
- AC-5: Given a request that the gateway rejects (401/403/429) or that matches no route, then no
  client-sent `X-Internal-Client-Ip` is forwarded anywhere and the response status is unchanged by this change.
- AC-6: Given a downstream servlet request with a well-formed `X-Internal-Client-Ip`, when any of the five
  readers asks for the client IP, then all five return that same value (one shared resolver, no per-reader parsing).
- AC-7: Given the header is absent or not an IP literal (service called directly, in-cluster), then the
  resolver returns the request's remote address; it never throws and never returns the leftmost XFF entry.
- AC-8: Given a client sends `X-Forwarded-For: 9.9.9.9, <real>` through the gateway, then the AuditInterceptor
  audit record, the WalletController per-IP limit key and the auth device context all use the real client
  IP, not 9.9.9.9 and not the gateway's address. Rotating XFF values from one real client no longer yields a fresh wallet limit bucket.
- AC-9: Given auth's `AUTH_TRUST_FORWARDED_FOR` flag, then it no longer influences the client IP (the
  resolver is the only source); AdminUserController no longer uses the raw socket address when the header is present.
- AC-10: An automated test goes client -> real gateway filter chain -> a downstream test endpoint (not mocks of
  the filters) and asserts the endpoint sees the real client IP for: (a) a spoofed XFF entry, (b) a spoofed
  `X-Internal-Client-Ip`, (c) N=0 where the peer, not the gateway pod IP, is expected as the socket peer.
- AC-11: The user-service referral flow obtains the IP from the shared resolver; existing referral behaviour with
  the IP check off (lookback zero) is unchanged and its existing tests pass.
- AC-12: The referral IP check stays default off. A doc (runbook or helm values comment) states the exact
  settings to turn it on and the precondition: gateway `trusted-proxy-hops` must equal the real proxy count, else all users share one IP and referrals are wrongly refused.
- AC-13: Existing RateLimitWebFilter tests pass unmodified in intent; `forge-verify --changed` passes.

## 4. Edge cases and abuse cases
- Comma-joined or multiple `X-Internal-Client-Ip` header lines from a client; padded, bracketed, `ip:port`,
  zone-id, over-long (>64 chars), or hostname values (no DNS lookup may ever occur).
- IPv4-mapped IPv6 equals plain IPv4 (existing behaviour kept). Unresolvable peer: today the gateway keys on
  "unknown"; the header must then be omitted or the documented sentinel, never empty or forged.
- Header name differing in case; header injected on a request the gateway sanitises vs. one it routes to
  actuator/internal paths. Direct in-cluster call can set the header: accepted (ClusterIP trust model
  already relied on by X-User-*), to be stated in the docs.
- Wrong N in deployment: the whole fleet sees one IP (documented risk, AC-12).

## 5. Open questions
None blocking. Note for design: objective.md paths read `com/ecommerce`; actual package is `com/acme/ecommerce`.
The gateway is reactive and the existing user-service `ClientIp` is servlet-based, so the shared lib must be usable by both.
