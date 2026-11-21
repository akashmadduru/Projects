# Add-phone no longer reveals registered numbers; password set after reset is announced (TD-P6-20)

Branch `forge/F-20261119-07-phone-enumeration`, head `2e3ce50b`, stacked on F-20261119-03 (a04ad610). Merge order: F-01, then F-03, then this branch. **Deploy notification-service first**: it carries the new email templates.

## Why
- Add-phone returned 409 `AUTH_PHONE_TAKEN`, so any signed-in user could find out whether a phone number was registered.
- A password reset quietly creates a password credential for accounts that only sign in with Google. The owner was not told.

## What changed
- **Add, resend and verify behave the same for a taken number and a free number.** Each returns `202` with an empty body, uses the same 30 s cooldown, and gives the same `429` on resend. A free number gets the verification SMS. A taken number gets no SMS. Instead, the existing owner is emailed (`PHONE_CLAIM_ATTEMPT`) at most once per 24 h, using Redis `SET NX` on the owner id. The phone number is never stored in Redis, and the email is skipped if Redis is down. A lost race on verify, or a unique-index hit, now returns `AUTH_OTP_INVALID`. The in-transaction re-check and the partial unique index still prevent two owners for one number. `AUTH_PHONE_TAKEN` is no longer emitted.
- **Reset:** when a reset creates a credential, a `PASSWORD_SET` email is sent after commit. If sending fails, the reset still succeeds. This is option (b) of AC-12, because reset is the only way for a Google-only user to set a password.
- **Storefront:** the code field shows a neutral hint: "If the code does not arrive, check the number."
- **notification-service:** two new templates, HTML-escaped and tested.
- **Docs:** api.md, auth-service.md and the env reference (`AUTH_PHONE_CLAIM_NOTICE_WINDOW`). The decision is ADR 0007 (Accepted).

## Evidence
- `forge-verify --changed` PASS: the env reference check; auth and notification tests (107 Java tests, 0 skipped, 0 failures); storefront vitest; tsc.
- QA confirmed AC-9..AC-12 against the JUnit XML. `forge-gate` PASS.
- About 190 production lines (class L, split from O-01; the Owner's design approval is a standing charter approval).

## Review
Independent code and security reviews both approved at 2e3ce50b in the first round, with no blockers or majors.

## Known issues (not blocking, in the backlog)
- **Notice abuse:** nothing caps how many owner-notice emails one requester can trigger, about one every 30 s. Each owner gets at most one a day, but the total volume could hurt sender reputation. Fix: a per-requester daily cap.
- **SMS pumping:** add-phone SMS still has only the 30 s cooldown. This change does not reduce SMS exposure, despite what the design says. It is tracked with the F-06 caps.
- **Same-mailbox notice:** `PASSWORD_SET` goes to the mailbox that received the reset link. An attacker who controls that mailbox can delete it. This is accepted in ADR 0007.
- **Redis timeout:** the notifier has no short Redis command timeout (the Lettuce default is 60 s).
- **Duplicate ADR:** `docs/architecture/adr/0007-generic-add-phone.md` duplicates `.openforge/knowledge/decisions/0007`. Keep one copy (G-9).
- **Test gaps:**
  - The after-commit ordering of the PASSWORD_SET send is not strictly proven by a test.
  - The 429 comparison checks status and code but not the full body or headers.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
