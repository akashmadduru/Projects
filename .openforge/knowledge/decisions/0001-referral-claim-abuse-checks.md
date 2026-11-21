# 0001 Referral claim abuse checks: code age plus keyed-hash IP match, inside user-service

## Status
Accepted (2026-11-19). Class M, so no Owner design approval is required.

## Context
TD-P8-13: `POST /api/referrals/claim` accepts any claim except own-code and duplicates, so one person can create accounts and claim their own code. The Owner asked for these refusals:
- the account is "too new";
- the claim comes from the same IP as the referrer's recent activity.

The Owner also asked that the claim IP is recorded and that the change stays in user-service. user-service has no per-user IP history, and IPs are PII. The CEO decided:
- "too new" means the age of the referrer's referral code;
- "recent activity" means the referrer's own referral-endpoint calls;
- refusals are 422 with specific codes and are not stored;
- two `ErrorCode` entries are allowed in `libs/common-core`.

## Decision
- Refuse a claim when the referrer's code is younger than `user.referral.min-code-age` (default 10 minutes) with `REFERRAL_CODE_TOO_NEW`. Otherwise, refuse when the claimant's IP matches an IP the referrer used on `GET /api/referrals` within `user.referral.ip-lookback` (default 30 days) with `REFERRAL_IP_MATCH`. The age check goes first and only one code is returned. A value of 0 disables a check.
- The client IP is the first `X-Forwarded-For` entry, else the remote address. It is parsed as an IP literal only (no DNS) and canonicalised to address bytes, so IPv6 spellings match and v4 never matches v6.
- IPs are stored only as HMAC-SHA256 keyed by a configured secret (`USER_REFERRAL_IP_HASH_SALT`):
  - in `users.referral_ip_activity`, one row per (user, IP) refreshed by upsert;
  - in `users.referral_claims.claim_ip_hash`.
  A scheduled job under `JobLock` deletes activity rows and nulls claim hashes older than `user.referral.ip-retention` (default 30 days). Account deletion removes them too.
- If no secret is configured, a random per-process key is used with a WARN. This fails open (missed matches only), and the age check remains.

## Alternatives considered
- **Raw IPs in an `inet` column:** simpler, and allows subnet matching, but it retains PII. Rejected.
- **Unkeyed SHA-256:** the IPv4 space can be brute-forced. Rejected.
- **Hold suspicious claims for manual review:** needs a status model, a queue and an admin UI, all non-goals. Rejected.
- **Shared client-IP helper in `libs/common-web`:** would also tidy the audit and payment copies, but it goes beyond the approved scope. Deferred.
- **Device or email fingerprinting, IP reputation:** non-goals, and would need new dependencies.

## Consequences
- Referrer and referee behind one shared IP (household, office, carrier NAT) are refused within the lookback. This is an accepted cost, and it can be switched off by config without a deploy.
- Anyone who reaches user-service directly can spoof `X-Forwarded-For` to dodge the IP check. The code-age check and the delivered-order reward condition still apply.
- `GET /api/referrals` now writes, at most one upsert per (user, IP) per hour.
- Two new public error codes. The response bodies are otherwise unchanged. Migration V5 is additive, so an app rollback is safe.

## Work id
F-20261119-01 (TD-P8-13). Design: `.openforge/work/F-20261119-01/design.md`.
