# One trustworthy client IP for every service (B-1)

Branch `forge/F-20261119-03-shared-client-ip`, head `a04ad610`. It is stacked on the referral pilot `forge/F-20261119-01-*` (fcf6f2a2), so merge that first.

## Why
Five services worked out the client IP in five different ways. Two of them trusted the leftmost `X-Forwarded-For`, which the caller can forge: the audit trail and the wallet per-IP limit. The gateway also stripped XFF, so services behind it never saw the real client.

## What changed
- **Gateway:** works out the client IP once, using the existing rate-limit rule (the N-th XFF entry from the right, where N is `trusted-proxy-hops`, or else the socket peer), and joins all XFF header lines first. It forwards the result as `X-Internal-Client-Ip` and removes any client-supplied copy. The rate-limit key and the header always agree.
- **`libs/common-core` `ClientAddress`:** strict literal parsing with no DNS lookup and canonical output. **`libs/common-web` `ClientIp`:** the single reader. It trusts exactly one valid header value, otherwise falls back to the socket peer, and never reads XFF.
- **Consumers moved to `ClientIp`:** AuditInterceptor, WalletController (gift-card per-IP limit), auth RequestDeviceContext (login throttle and device record), auth AdminUserController audit, and user-service EngageController (referral check). The old user-service `ClientIp`, `AUTH_TRUST_FORWARDED_FOR` and `USER_REFERRAL_TRUSTED_PROXY_HOPS` are removed.
- **auth:** a forwarded-header filter keeps the forwarded host and proto that the OIDC callback needs, and pins the remote address, host and port to the socket peer. It is registered under `server.forward-headers-strategy=framework`, like Boot's own filter, which backs off; the evidence is from the Boot 4.0.7 source.
- **Helm:** `SERVER_FORWARD_HEADERS_STRATEGY=none` is now the default for every service except auth, which stays on `framework`.
- **Docs:** ADR 0002, deploy-aws, the env reference (regenerated) and the phase-08 and tech-debt notes.

Behaviour changes, all within the bounds the ADR sets:
- The address is canonicalised and its port stripped.
- A bracketed IPv6 address now resolves.
- The `127.1` shorthand now falls back to the peer.
- An empty right-hand XFF entry (`1.1.1.1,`) now falls back to the peer.

## Release gate: do not enable in production until B-4 is resolved
The ALB is internet-facing, and the CloudFront prefix list is optional and fails open. A client that reaches the ALB directly can therefore choose the N-th XFF entry. That affects all four consumers: audit, the wallet per-IP limit, login throttling and device records, and referral. Before release, one of these must be true:
- the prefix list is made mandatory and fails closed;
- a CloudFront secret origin header is verified;
- the Owner explicitly accepts the risk.

The referral IP check stays off by default (`USER_REFERRAL_IP_LOOKBACK=0`).

## Evidence
- `forge-verify --changed` passed at a04ad610: env reference check, plus common-core, common-web, auth, gateway, payment and user-service tests. 60 suites, 293 tests, 0 skipped, 0 failures.
- QA mapped all 13 ACs to passing tests in the JUnit XML. The `forge-gate` PR gate passed.
- Size: 572 production lines, against a class L limit of 600.

## Review
- Independent code and security reviews ran over 5 rounds, the latest at a04ad610. Both approve, with no blocker or major findings.
- Majors fixed along the way: only the first XFF line was being read, the zone and bracket parsing was too lenient, auth fell back to a forged XFF, and auth also fell back to a forged `Forwarded: for=`.

## Known issues (not blocking)
- auth's `application.yml` has no safe default for `server.forward-headers-strategy`, so only the chart protects it. It is in the backlog.
- The context test proves the conditional registration, not that Boot backs off. The back-off rests on the Boot source check in evidence.md.
- The `USER_FORWARD_HEADERS_STRATEGY` setting does nothing under Helm, because the server-level value is pinned there.
- The Feign relay of the client IP to service-to-service calls is tracked in backlog B-2, not in the tech-debt register.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
