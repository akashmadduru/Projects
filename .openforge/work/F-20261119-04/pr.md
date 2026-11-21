# Razorpay refunds wait for webhook confirmation; one Razorpay order per payment (TD-P6-30, TD-P6-31, TD-P6-32)

This objective ships as **two stacked PRs**. Each is under the 600-line class-L limit, and each is gated by forge-gate under G-8:

| PR | Range | Production lines | Reviewed |
|---|---|--:|---|
| A: refund states | `fcf6f2a2..4e5fb6d0` | 263 | approved at 4e5fb6d0 (code and security) |
| B: order claim, capture watch, alerts | `4e5fb6d0..218998b1` | 520 | approved at 218998b1 (code and security, after 3 rounds) |

Branch `forge/F-20261119-04-razorpay-refund-states`, stacked on the referral pilot F-20261119-01 (fcf6f2a2). Merge F-01 first, then PR A, then PR B.

## Why
- A Razorpay refund was recorded as succeeded as soon as it was requested. If Razorpay later failed it, the order still showed REFUNDED.
- Concurrent create calls could make two Razorpay orders for one payment.
- A payment that was authorized but never captured went unnoticed.

## What changed
- **PR A, refunds.**
  - Migration V7 adds a refund `status`, with existing rows backfilled to SUCCEEDED, and a `razorpay_refund_parked` table.
  - Razorpay refunds are inserted as PENDING. The signed `refund.processed` and `refund.failed` webhooks settle them with `UPDATE … WHERE status='PENDING'`, so each refund is settled once.
  - A failed refund releases only its own amount.
  - Events that arrive before the refund row exists are parked, then applied under the payment lock.
  - Stripe, COD and wallet refunds are unchanged and stay SUCCEEDED.
- **PR B, orders and alerts.**
  - Migration V8 starts with `SET LOCAL lock_timeout='5s'` and adds nullable timestamps only.
  - One Razorpay order per payment: the first caller inserts a claim (unique `order_id`) and creates the order outside any transaction. The order is then published with a conditional UPDATE guarded on provider order id, mode, status and method.
  - Waiters poll, and take over the lease only after it expires.
  - A failed create releases only the caller's own lease, compared by stamp. The original error is kept, with any release failure attached as suppressed.
  - `RazorpayWatch`, under the existing JobLock and `SKIP LOCKED`, alerts once for each of two cases: an authorized payment that was never captured (`RAZORPAY NOT CAPTURED`), and a refund stuck in PENDING (`REFUND PENDING TOO LONG`).
  - Startup fails fast if the lease and timeout settings are invalid.
  - New ERROR alert strings:
    - `REFUND FAILED`
    - `REFUND FAILED AFTER SUCCEEDED`
    - `LEDGER MISMATCH`
    - `REFUND ID DUPLICATED`
    - `PARKED EVENT DROPPED`
  - New env vars: `RAZORPAY_PROVISION_LEASE`, `RAZORPAY_WATCH_INTERVAL_MS`, `RAZORPAY_CAPTURE_ALERT_AFTER` and `RAZORPAY_REFUND_PENDING_ALERT_AFTER`.
  - The runbook covers auto-capture, the webhook subscriptions, every alert, the historic-SUCCEEDED reconcile, and the V7 and V8 lock profiles.

## Evidence
- `forge-verify` passes at 218998b1 (env reference and payment-service).
- An uncached run (`--rerun-tasks`) gave 14 suites and 97 tests, with 0 skipped and 0 failures. It covered the concurrency and timing tests.
- QA mapped all 12 ACs to passing tests.
- forge-gate passes with G-8 per-part checks.
- Design: ADR 0003.

## Before merge (Owner)
- **V8 was edited after round 1.** Confirm that no persistent environment applied the earlier V8. If one did, run `flyway repair` there; otherwise validate-on-migrate fails at startup.
- **Razorpay dashboard:** subscribe to `refund.processed`, `refund.failed` and `payment.authorized`, and keep auto-capture on. The runbook has the steps.

## Known issues (not blocking, in the backlog)
- Order-service still shows REFUNDED after a failed Razorpay refund (B-3; this PR only adds the alert).
- No partial index for the `claimUncaptured` scan.
- No test for the `method='CARD'` publish guard.
- The interleaved refund and webhook race is proven by reasoning, not by a test.
- A switchProvider call after a successful publish can still create a second payable order (backlog item 2, pre-existing).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
