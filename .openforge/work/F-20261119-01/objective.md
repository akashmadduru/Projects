# F-20261119-01 Referral claim abuse checks (TD-P8-13)

## Objective (verbatim from the Owner, via /forge)
Close tech-debt item TD-P8-13: add abuse checks to referral claims in user-service
(POST /api/referrals/claim). Refuse a claim when the referee's account is too new or when the claim
comes from the same IP as the referrer's own recent activity; record the claim IP. Keep it to
user-service, with tests.

## Goal
Stop self-referral farming (one person creating accounts to claim their own code) without blocking
genuine referrals.

## Affected areas
- `services/user-service/.../membership/ReferralService.java` (`claim`, `view`), `web/EngageController.java` (`POST /api/referrals/claim`, `GET /api/referrals`).
- Tables `users.referral_codes`, `users.referral_claims` (V3). Next migration: V5.
- Tests: `services/user-service/.../src/test/.../support/AbstractUserIntegrationTest.java` (Testcontainers base exists).
- Register: `docs/techdebt/phase8.md#td-p8-13`; reward is paid by promotion-service only after the referee's first delivered order (unchanged).

## Assumptions (CEO decisions; the Owner asked for decisions to be made and logged)
1. "Account too new" cannot mean the referee: a referee is new by definition. It is read as a
   **minimum age of the referrer's referral code** (the code is created the first time the referrer opens
   their referral page). A code used within minutes of being created is the farming pattern. Configurable.
2. "The referrer's recent activity" = the IPs from which the referrer used their referral endpoints
   (viewing their code). user-service has no other per-user IP record, and we keep the change in
   user-service. IPs are stored only as a salted hash and kept for a limited time (PII minimisation).
3. Client IP: the gateway sets `X-Forwarded-For`; use its first entry, else the remote address.
4. A refused claim returns 422 with a specific error code, and is not stored.

## Blocking questions
None (assumptions above recorded instead).

## CEO decisions on the CPO's open questions (2026-11-19)
1. Scope widened by two enum entries: `REFERRAL_CODE_TOO_NEW` and `REFERRAL_IP_MATCH` (422) are added to
   `libs/common-core` `ErrorCode`, following the existing pattern (COUPON_*, CART_LIMIT), and to the
   error catalogue in `docs/reference/api.md`. No other code outside user-service.
2. Defaults accepted: min code age 10 minutes, IP lookback 30 days, IP retention 30 days.
3. (step 4) Unset `USER_REFERRAL_IP_HASH_SALT`: start with a random per-process key and log a WARN,
   as designed. It fails safe (the IP check can only miss matches, never refuse wrongly; the age check
   still applies). Follow-up outside this objective: wire the secret through Helm and fail closed
   under the `prod` profile (to be filed in the tech-debt register at step 11).

## Correction after security review (2026-11-19, round 1)
- **Assumption 3 was wrong.** The gateway does not overwrite `X-Forwarded-For`; it appends, and
  CloudFront and the ALB append before it. The leftmost entry is caller-controlled
  (`services/gateway/.../RateLimitWebFilter.remoteIp` documents this). New rule: the client IP is the
  N-th entry from the RIGHT, N = `user.referral.trusted-proxy-hops`
  (`USER_REFERRAL_TRUSTED_PROXY_HOPS`), mirroring the gateway; N=0 or too few entries → remote address.
  Local compose: N=1 (the gateway's own append). AWS: N = gateway trustedProxyHops (2) + 1 = 3, set in Helm.
4. (round 1) Accepted residual risk: the distinct 422 codes reveal facts about the referrer, limited to the
   claimant's own real IP once the major is fixed. Kept distinct for support and UX; documented.
5. (round 1) Out of scope, filed at step 11: the auth-service `application.yml` comment that also claims the
   gateway overwrites X-Forwarded-For.

## Owner delegation (2026-11-19)
Owner: "go ahead let agents make decesion, i want only report log of actions and performance of my
prompts vs ai agent auto promts". Recorded as delegation of in-workflow decisions (refactor scope, the
size-limit question) to the CEO and role agents. Not delegated (constitution §1, needs an explicit yes
in the conversation): push, merge, release, governance-file edits (CLAUDE.md, constitution,
.claude/settings.json, hooks, workflows). The run stops at a prepared PR.

## Scope deviation (review round 3)
The gateway is a requirements non-goal, but security required `SERVER_FORWARD_HEADERS_STRATEGY=none`
for the gateway in the Helm chart (config only, no gateway Java code): under the Kubernetes default
(NATIVE) the gateway appends the forged leftmost X-Forwarded-For value. Accepted by the CEO.
Post-deploy check (PR notes): Google sign-in callback URL on AWS still https.
Follow-up (PR notes, not a blocker): env docs say a blank USER_REFERRAL_IP_RETENTION is a startup
error; in fact it keeps the default (30d).

## Owner decision (round 4)
Owner: "go ahead and complete pending works without my intervention". CEO chose option (b): the IP
check ships disabled (`USER_REFERRAL_IP_LOOKBACK` default 0, no IP activity recorded while disabled);
the referral code-age check stays on. A correct client IP through the gateway is backlog B-1, the next
objective; the IP check is enabled only after B-1 lands. Scope deviation kept: gateway Helm
`SERVER_FORWARD_HEADERS_STRATEGY=none` (requested by security round 2, judged safe in round 3).
