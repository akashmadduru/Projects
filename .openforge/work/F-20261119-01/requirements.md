# F-20261119-01 Referral claim abuse checks (TD-P8-13) - Requirements

## 1. Problem and users affected
Today `POST /api/referrals/claim` accepts any claim except own-code and duplicates, so one person can create
accounts and claim their own code to farm rewards. Affected: customers claiming genuine referrals (must not be
blocked), referrers (reward integrity), and support/finance (fraud cost). Reward payment is unchanged.

## 2. Scope and non-goals
In scope: user-service only; the claim and referral-view endpoints; a new migration (V5); tests.
Non-goals: changes to promotion-service or reward payment; device/email/payment fingerprinting; IP
reputation, geo or VPN detection; an admin UI; back-filling or re-checking existing claims; changing the gateway;
rate limiting of the endpoints in general.
Settled by CEO (objective Assumptions): "too new" = age of the referrer's referral code; "recent activity" = IPs
of the referrer's own referral-endpoint calls; IPs stored salted-hashed and time-limited; client IP = N-th `X-Forwarded-For` entry from the right (trusted hops), else remote address (corrected, see objective.md); refusals are 422 with a specific error code and are not stored.

## 3. Acceptance criteria
- AC-1: Given a referrer whose code was created longer ago than the min-age threshold and no IP overlap, when a
  different authenticated user POSTs the code, then 204 and a claim row exists (genuine claims still succeed).
- AC-2: Given a code younger than the min-age threshold, when claimed, then 422 with error code
  `REFERRAL_CODE_TOO_NEW` (distinct from the generic DOMAIN_RULE_VIOLATION); no claim row is stored.
- AC-3: Given the referrer used a referral endpoint (GET /api/referrals) from IP X within the lookback window,
  when the referee claims from the same IP X, then 422 with error code `REFERRAL_IP_MATCH`; no claim row stored.
- AC-4: Given the referrer's only recorded IPs are older than the lookback window, when claimed from that IP,
  then 204 (IP check does not apply).
- AC-5: Given a successful claim, then the claim IP is recorded against the claim, only as a salted hash; the
  raw IP appears in no table, log line or response.
- AC-6: Given the referrer views their referral page, then the caller's IP (salted hash) and timestamp are
  recorded; the response body of GET /api/referrals is unchanged.
- AC-7: Given stored IP records (referrer activity and claim IPs) older than the retention period, when the
  retention cleanup runs, then they are deleted; newer records and the claim itself are retained.
- AC-8: The min code age, IP lookback window and IP retention period are each configurable by property with
  documented defaults; changing a value changes behaviour without code change (tested with non-default values).
- AC-9: Given min age = 0 (or lookback = 0), then the corresponding check is disabled.
- AC-10: Given both checks would refuse, then 422 with exactly one code, `REFERRAL_CODE_TOO_NEW` first.
- AC-11: Given a refused claim, when the same referee later claims a valid code (or retries after the threshold
  passes), then it succeeds; a refusal does not consume the referee's one claim.
- AC-12: Existing behaviour is preserved: unknown code 404 RESOURCE_NOT_FOUND; own code 422
  DOMAIN_RULE_VIOLATION; second claim by same referee 409 RESOURCE_CONFLICT; unauthenticated 401.
- AC-13: Given `X-Forwarded-For: A, B, C` and `user.referral.trusted-proxy-hops` = N, then the entry N from the RIGHT (N=1 → C) is the client IP used for both recording and comparison; N=0 or fewer than N entries → the remote address. (Corrected after security review round 1; the leftmost entry is caller-controlled.)
- AC-14: Given a missing, blank or unparsable `X-Forwarded-For`, then the remote address is used; the request
  never fails with 5xx because of the header.
- AC-15: Given the same IPv6 address written differently (compression, case, zone-free forms), then it is
  treated as one IP for matching; IPv4 and IPv6 never match each other.
- AC-16: Given the referrer never opened their referral page, no code exists, so claim returns 404 (not a new
  error); no IP record exists for them.
- AC-17: Given a refused claim, the error body follows the platform's standard error shape and does not reveal
  the referrer's IP, code creation time or whether other IPs were recorded.

## 4. Edge cases and abuse cases
- Referee and referrer behind one shared IP (household, office, mobile carrier NAT): refused by design (AC-3);
  accepted false-positive cost, see open question 2.
- Spoofed `X-Forwarded-For` sent by a client directly to user-service (bypassing the gateway): out of scope to
  fix here, but note the farmer can evade the IP check by spoofing; min code age is the backstop.
- Referrer views from a new IP after creating the code just to age it: the age check still applies.
- Referrer could poison the lookback by "viewing" from many IPs: only recorded for authenticated calls.
- Concurrent duplicate claims by the same referee: exactly one claim row; the other gets 409.
- Referrer's page hammered: IP records must not grow unbounded (dedupe or bound per referrer).
- Case/whitespace variations of the code still hit the same checks.
- Very long or multi-value header (>1 entry with spaces) is handled without error.

## 5. Open questions
1. New error codes need entries in the shared `ErrorCode` enum (libs/common-core), which is outside
   "user-service only". Non-blocking, but the CEO should confirm that a one-line addition there is acceptable.
   Otherwise the specific code must be carried some other way, e.g. a code field in the 422 body.
2. Default values (proposed: min code age 10 minutes, IP lookback 30 days, IP retention 30 days) are not
   Owner-set. Non-blocking: they are configurable (AC-8).
