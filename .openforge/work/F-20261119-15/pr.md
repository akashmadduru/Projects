# Every COD order gets a payment row: durable repair at placement, lazy ensure for legacy orders, refunds and collect-marks unblocked (B-10, ADR 0013)

**Two stacked PRs** on `forge/F-20261119-15-cod-payment-row`, built on the F-20261119-11 head (b7f5b974). Merge after F-11.
- **PR-A:** `b7f5b974..60a10507`, 242 production lines, order-service only. Covers AC-1..AC-5, AC-13 and AC-15.
- **PR-B:** `60a10507..8ff29132`, 177 production lines, order-service and payment-service. Covers AC-6..AC-12 and AC-14.

## Deploy order: HARD GATE
**For PR-B, deploy payment-service first. Do not deploy order-service PR-B before payment-service PR-B is live.**

An old payment-service answers 404 to the new legacy-credit lookup. For an order that already has a payment row, the refund then goes ahead without the legacy check. A pre-F-09 COD return with a `return-rest-<id>` credit could then be paid twice. This is a security-review minor; deploy order closes it.

PR-A can deploy on its own; it changes order-service only.

## Why (backlog B-10)
- When payment-service failed during COD placement, the error was only logged. The order was saved with no payment row and `payment_id` null.
- Since F-09, a COD return refund goes through payment-service. For such orders it failed with 404, so the return stayed PICKED_UP forever.
- The delivery collect-mark (`COD_COLLECTED`) also failed with 404 for those orders.

## What changed
- **PR-A, placement repair:**
  - **Repair row.** An `ENSURE_COD_PAYMENT` outbox row is written in the same transaction as the order save. It has its own aggregate and does not run for 60 s.
  - **Inline path.** When the inline create and attach succeed, the row goes DONE. Otherwise the relay calls the idempotent `POST /internal/payments` create, which already exists.
  - **Create rules.**
    - payment-service inserts with ON CONFLICT on the order id, and an existing row wins.
    - No wallet or provider call is made.
    - A CARD row is reported, never converted.
    - A COD create that hits a non-COD row now returns at once, without the Razorpay wait.
  - **Attaching the row.**
    - `payment_id` is attached only when it is null.
    - The update bumps `version`, so a concurrent JPA flush cannot reset `payment_id` to null.
- **PR-B, lazy ensure for legacy orders (no backfill job):**
  - **`collect`** ensures the row first, then marks it collected only for delivered orders whose order state is collected. It never does this for cancelled orders. The relay's `COD_COLLECTED` step now runs through it.
  - **`prepareRefund`** runs on every COD refund attempt on route PAYMENT_WALLET, never DIRECT_WALLET. It runs before the ensure and before any money moves.
  - **Legacy `return-rest-<returnId>` wallet credit.**
    - It is read through the new internal `GET /internal/wallet/credits/{key}`.
    - If the credit matches the frozen rest leg exactly (user, amount compared numerically, currency), the leg is settled with no money moved.
    - Any mismatch returns 409 with an ERROR log, and the return stays PICKED_UP for a human to settle.
  - **New internal endpoint.** It needs the internal token, always answers 200 with `found`, `userId`, `amount` and `currency` only, logs nothing, and is not routed by the gateway.
  - **`PaymentFeignClient`** now sends the internal token and never the caller's bearer token.
- **Migrations:** none. All contract changes are additive.

## Evidence
- `forge-verify --changed` passed at both part heads, running the order-service and payment-service tests.
- QA mapped AC-1..AC-15 to passing tests in the JUnit XML. Gateway coverage for `/internal/**` comes from `GatewayEdgeIntegrationTest.internalPathsAreNotRouted`.
- `forge-gate` passed: 2 contiguous parts, each under 600 lines and approved at its own head.
- Guard tests fail on their real assertion against mutants:
  - durable repair at placement;
  - the relay creating the row exactly once;
  - an old-build relay leaving the row alone;
  - the legacy match on user, amount and currency (each gives 409, with no credit and no refund);
  - an exact match leading to no refund;
  - the refund cap;
  - no collected mark for a cancelled order;
  - 401 and 403 on the new endpoint;
  - the bearer token stripped.

## Review
- Independent code and security review, 2 rounds, approving PR-A at 60a1050 and PR-B at 8ff2913.
- PR-A minors were folded into PR-B: the `@Version` bypass, order-dependent tests, the missing decode test, and bearer forwarding.
- Design: ADR 0013 (Accepted). The CTO accepted it with 9 conditions; all are met.

## Before and after deploy (Owner or ops)
- **Re-arm dead collect-marks.** Old `COD_COLLECTED` outbox rows that already went DEAD must be re-armed from the runbook (`docs/guides/runbooks/payment-problems.md`). Use the given SQL in batches of 500 or fewer, oldest first, and wait for the relay to drain between batches.
- **Rowless legacy orders.** A legacy order that is never delivered, returned or re-armed keeps no payment row. This is accepted because no money is involved.
- **409 legacy mismatch.** The runbook gives the queries to settle it by hand. A manual close must also release the stock and record the event, as in the F-09 note.

## Known issues (approved minors, follow-ups)
- **Test strength:**
  - two mismatch tests do not assert the 409 code;
  - nothing pins that DIRECT_WALLET skips the lookup;
  - there is no legacy-paid case that also has a wallet leg and a sibling return;
  - the gateway test does not name the new path.
- **Legacy wallet-leg mismatch is silent:** if a legacy `return-wallet-<id>` credit's amount differs from today's frozen wallet leg, the key dedupe keeps the old amount and nothing reports it.
- **Data anomaly:** a COD_COLLECTED order with no `delivered_at` is ensured but not collect-marked. It fails loudly with a 409 and moves no money.
- **Stray row:** a placement slower than 60 s can leave a harmless COD_PENDING row on an order that is later cancelled. This is in the runbook.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
