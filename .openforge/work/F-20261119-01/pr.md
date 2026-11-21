# Referral abuse checks: code-age rule, IP check built but off by default (TD-P8-13)

## What
- A referral code can be claimed only after it is `USER_REFERRAL_MIN_CODE_AGE` old (default 10m), which blocks
  instant self-referral. The claim order is 404 / own code 422 / already claimed 409 / REFERRAL_CODE_TOO_NEW /
  REFERRAL_IP_MATCH / insert.
- An IP-match check (HMAC fingerprints, right-hand X-Forwarded-For with trusted hops, 20-row cap, retention
  purge under JobLock) is built and tested but **disabled by default** (`USER_REFERRAL_IP_LOOKBACK=0`). While
  it is off, no IP is hashed or stored. Startup logs a WARN if someone enables it. It needs F-20261119-03
  (gateway-provided client IP) first: Spring Cloud Gateway 5.0.2 strips X-Forwarded-* without trusted-proxies.
- V5 migration: `users.referral_ip_activity` and `referral_claims.claim_ip_hash`.
- user-service `server.forward-headers-strategy: none`; gateway Helm `SERVER_FORWARD_HEADERS_STRATEGY=none`
  (scope deviation, asked for by security round 2).
- Docs: env reference regenerated (CI `env-reference --check` now passes; it failed on base too). VAPID keys
  are described. TD-P8-13 removed; TD-P8-24 and TD-SEC-15 (OIDC callback, post-deploy check) filed.

## Evidence
- `forge-verify --changed` PASS at fcf6f2a2: 79 tests, 0 failures. `env-reference.mjs --check` exit 0.
- 17/17 ACs evidenced (`.openforge/work/F-20261119-01/evidence.md`). The IP-check ACs are tested with the
  check on.
- Independent review at fcf6f2a2: forge-reviewer **approve** (1 minor, 1 nit, both follow-ups);
  forge-security **approve** (no findings). There were 5 rounds; the history is in `history/`.

## Size
2,028 changed lines in total: production 442 (+429/−13), the rest tests and docs. The class M limit is 600.
**The gate needs an Owner override line in complexity.md** (or approval of proposal G-3: count production
lines only).

## After deploy
- Check the Google sign-in callback URL on AWS (TD-SEC-15).
- Leave `USER_REFERRAL_IP_LOOKBACK` unset until F-20261119-03 ships.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
