# Per-shipment cancel with refund for sellers and admins, with shipping refunded once across cancels and returns (O-09 part 2, ADR 0014)

**Two stacked PRs** on `forge/F-20261119-14-per-shipment-cancel`, built on the F-20261119-15 head (8ff29132). Merge after F-15. **A and B must merge together** (CTO condition 1): A on its own under-refunds shipping on returns that follow a partial cancel.
- **PR-A:** `8ff29132..0727afee`, 586 production lines, order-service. Covers AC-1, AC-2, AC-3, AC-5, AC-6, AC-9, AC-10, AC-11 and the cancel side of AC-7.
- **PR-B:** `0727afee..57e20e69`, 35 production lines, order-service plus docs. Covers the return side of AC-7 and AC-8.

## Why (TD-P9-12, TD-P9-13; F-13 #9..#12)
- A seller could not reject one shipment of a multi-seller order, and an admin could cancel only the whole order.
- A whole-order cancel after any partial refund paid back the full total again under one `cancel-<orderId>` key.
- Shipping had no rule across cancels and returns, so it could be refunded twice or not at all.

## What changed
- **New endpoints:**
  - `POST /api/admin/orders/{id}/shipments/{sid}/cancel` (ADMIN);
  - `POST /api/seller/shipments/{sid}/cancel` (approved SELLER, own shipments only; 404 otherwise).
  - Both refund the shipment's items value less its pro-rata share of discount, coupon and coins, with the same formula and rounding as returns.
- **Three-step cancel; no remote call runs inside a transaction:**
  - **T1:** a claim is frozen under the order row lock, holding the share, the wallet and card legs, and the attempt.
  - **Card leg:** strict, with key `ship-cancel-<sid>`, or `-r<n>` after a reported FAILED.
  - **T3:** an idempotent commit. It sets CANCELLED, records the refund and queues the wallet credit-back durably under `ship-cancel-wallet-<sid>`, all in one transaction.
  - **Failure:** a card failure gives 503 and changes nothing. A wallet-only cancel succeeds even when payment-service is down.
- **Whole-order cancel of a paid order** now runs through the per-shipment claims. Earlier partial cancels are never refunded again, and the order-wide follow-ups run once.
- **Shipping once (AC-7):**
  - The event that leaves every line cancelled or returned carries the shipping, and both sides use one coverage rule.
  - A FAILED claim moves no money, so it never counts as covered. A retry re-freezes it under the next key.
  - Shipping a shipment whose cancel FAILED is refused with 409 while another cancel or return holds the shipping. The operator retries the cancel to clear it.
- **Shipping ship-guard:** `advanceShipment` locks the order row and refuses to ship a shipment whose cancel is REQUESTED.
- **Migration:** `V14__shipment_cancel_refund.sql`, additive columns with a lock timeout.
- **Cancel events** carry no return fields, so seller-service reverses nothing (AC-8).
- The history note is capped at 500 characters, so a long reason can never fail the commit after money has moved.

## Evidence
- **Full order-service run:** 256 tests at 0727afe and 262 at 57e20e6, none failing.
- **`forge-verify --changed`:** PASS at both part heads. The CEO re-ran it at 57e20e6 and it passed (gradle_rc=0).
- **QA:** mapped all 10 in-scope ACs (AC-1..3, AC-5..11) to passing tests in the JUnit XML, with AC-7 covered on both the cancel and the return side. The AC-8 seller side is shown by code review only, because seller-service is unchanged here.
- **`forge-gate`:** PASS, 32 checks. The 2 contiguous parts are 586 and 35 lines, each approved at its own head.
- **Guard demos:** each new IT fails on its real assertion against the code before its fix:
  - the round-1 FAILED-sibling IT against a764802;
  - the two r3 ship-guard ITs against 8f214e7;
  - the B-fix IT against b4c7f44, which expected shipping 50.00 and got 0.0000.
- **Tests added:**
  - ShipmentCancelIT, ShipmentCancelWalletIT, ShipmentCancelRaceIT, WholeOrderCancelIT, ShipmentCancelAuthzIT, OrderStoreCancelIT, ShippingOnceIT and CancelEventShapeTest;
  - cases for the long reason, the FAILED-claim orderings, the retry exit path, COD plus wallet, and the 401/403/404/409 matrix.

## Review
- Independent code and security review.
  - **PR-A:** 3 rounds, both approve at 0727afe.
  - **PR-B:** 2 rounds, both approve at 57e20e6.
- Majors found and fixed along the way:
  - a long reason overflowed the note after the card refund;
  - shipping could be refunded early through a FAILED sibling claim (two orderings);
  - shipping could be lost when a return was requested while a sibling cancel was in flight.
- Design: ADR 0014 (Accepted). The CTO accepted it with 6 conditions.

## Known gaps (disclosed; on the backlog)
- **AC-4 deferred (CTO condition 5).** seller-service does not yet treat a CANCELLED shipment as terminal. A forged or stale DELIVERED event could book SALE, COMMISSION or FEE until the seller-service follow-up lands on the F-13 head, and F-13 reconcile would report a STATE_MISMATCH. order-service itself can never advance a CANCELLED shipment.
- **PENDING card refund counted as accepted.** A refund that later settles FAILED overstates refunded_amount. There is no settlement watch yet; this is the same gap as whole-order cancel today.
- **Persistent card decline.** A FAILED cancel blocked by the ship guard needs the manual path in the runbook until the admin refund-to-wallet action exists.
- **COD due amount.** A partial cancel on an uncollected COD order leaves the COD row's due amount at total minus wallet.
- **Smaller items:**
  - the seller's free-text reason is shown in the customer timeline;
  - `requestReturn` takes the lock before checking ownership;
  - the return arm of the ship guard has no dedicated test;
  - `uncovered()` is O(items x shipments).

## Before and after deploy (Owner or ops)
- No deploy-order constraint beyond merging A and B together after F-15.
- **Runbook** (`docs/guides/runbooks/payment-problems.md`):
  - stuck REQUESTED claims, cleared by retrying the cancel (with a query on `cancel_state`);
  - FAILED shipments whose sibling cancel or return holds the shipping (with a query);
  - the manual path for a persistent decline.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
