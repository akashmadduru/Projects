# F-20261119-14 Requirements: per-shipment cancel, reject and refund (O-09 part 2)

Checked against the snapshot 71bba97 (order-service, payment-service, seller-service). TDs: td-p9-12, td-p9-13. Aliases: F-13 #9..#12.

## 1. Problem and users affected
On a multi-shipment order a customer can cancel only the whole order, and only while every shipment is pre-dispatch. A seller who cannot fulfil one shipment has no way to reject it, and its value is not refunded. Shipping refunds ignore cancelled shipments. Affected: customers (money), sellers, finance (ledger), the Owner.

Verified today:
- `Order.cancel` is whole-order only and refunds `total - wallet` under key `cancel-<orderId>`. A failed refund gives 503 and leaves the status unchanged. Stock, coupon, coins and wallet are then released for the whole order.
- A shipment can only move forward along PLACED..DELIVERED. A seller or admin posting `CANCELLED` to a shipment gets 409 `INVALID_STATUS_TRANSITION`. No per-shipment refund exists.
- Whole-order cancel is still allowed when some shipments are already CANCELLED, and it refunds the full total again (a double-refund hazard once shipments can be cancelled one by one).
- Return shipping (ADR 0008) goes to the return that "completes the order". Lines of a cancelled shipment never count as covered, so shipping would never be refunded.
- seller-service books SALE, COMMISSION and FEE only on a DELIVERED part and ignores CANCELLED. At 71bba97 it has no shipment register. The F-13 design (ADR 0012) adds `ledger_shipments` but says nothing about CANCELLED, so "F-13 already skips cancelled shipments" is not true. See section 3 (seller-service touch).

## 2. Scope and non-goals
In scope: order-service per-shipment cancel (seller reject, admin), its refund, its event, the shipping rule across cancels and returns, and a minimal seller-service guard (AC4, deferred).
Non-goals: SLA scheduler and auto-cancel (it must reuse the AC-1 path later); customer-initiated per-shipment cancel (the customer keeps whole-order cancel); shipment timeline rows, invoices, GST, notifications; changing ADR 0008 leg logic; settlement tracking of a PENDING cancel refund (known gap, as for whole-order cancel today); no new service, datastore or vendor.

## 3. Acceptance criteria
Defaults used: a seller or admin may cancel only before dispatch (PLACED, CONFIRMED, PACKED). A refund that payment-service accepts (SUCCEEDED or PENDING) commits the cancel; one reported FAILED or unreachable does not.

- AC-1 (F-13 #9). Given a paid order with shipments S1 and S2, S1 pre-dispatch, when its seller (or an admin) cancels S1, then 200 and S1 is CANCELLED. S2 is unchanged. The customer is refunded S1's items value less S1's pro-rata share of discount, coupon and coins, using the same formula and rounding as returns, to the cent. The wallet and card split follows the ADR 0008 frozen-leg rules. The order's refunded amount rises by exactly that amount and the payment status becomes PARTIALLY_REFUNDED. Only S1's stock is released. One `SHIPMENT_STATUS_CHANGED` (S1, CANCELLED) is published.
- AC-2 (F-13 #9). Given AC-1, when the refund uses a key derived from S1's id (never `cancel-<orderId>`), then a retry or replay after success sends the same key and amounts, creates no second refund, and returns 200 without a second event. Two different shipments never share a key.
- AC-3 (F-13 #9). Given payment-service is down or reports the refund FAILED, when S1 is cancelled, then the response is 503, S1's status, history, stock and the order's refunded amount are unchanged and no event is published. A later retry with the same key and amounts succeeds.
- AC4(deferred to backlog, CEO ruling 2026-11-23: seller-service touch needs the F-13 head; disclosed per CTO condition 5) (F-13 #10). Given S1 was cancelled, when seller-service consumes order events, then no SALE, COMMISSION or FEE is ever booked for S1 and nothing is reversed. A stale or replayed DELIVERED for S1 arriving after the cancel books nothing (a WARN is logged). A CANCELLED shipment never moves back in the register, whatever the event order. This is the seller-service touch below.
- AC-5 (F-13 #11). Given a shipment that is SHIPPED, OUT_FOR_DELIVERY or DELIVERED, or whose order is PENDING_PAYMENT or on risk hold, when a cancel is requested, then 409 (`ORDER_NOT_CANCELLABLE` for dispatched or delivered shipments), with no payment call, no ledger entry and no event.
- AC-6 (F-13 #11). Given a seller who does not own S1 (or an admin using the wrong order id), when they cancel it, then 404, identical to the other seller shipment endpoints, and nothing changes. A caller without the SELLER or ADMIN role gets 403 and an unauthenticated one gets 401. A seller that is not approved gets 403.
- AC-7 (F-13 #12). Given a multi-shipment order, when any mix of shipment cancels and returns completes, then the shipping refunded over all of them equals the order's shipping exactly once. The event that leaves every order line either cancelled or returned carries it. Lines of cancelled shipments count as covered. Examples: S1 cancelled, then S2 delivered and fully returned: the S2 return carries shipping. S2 returned first, then S1 cancelled: the S1 cancel carries shipping. A single-shipment order keeps today's behaviour.
- AC-8 (F-13 #12). Given any return or cancel refund, when RETURN_REFUNDED or the cancel event reaches seller-service, then shipping is never reversed from the seller. The return reversal is items value only (F-13 #3).
- AC-9. Given S1 is already CANCELLED and refunded, when the customer cancels the whole order, then only the value of the remaining live shipments is refunded (never the full total again, never shipping already refunded), under a key that cannot collide with S1's. When the last live shipment is cancelled, the order becomes CANCELLED and the order-level follow-ups (coupon, coins, wallet) run once for the remainder, never twice.
- AC-10. Given a cancel and a ship (or two cancels) of S1 at the same moment, then exactly one wins: after SHIPPED no refund is made, and after CANCELLED the ship returns 409. Cancelling an already CANCELLED shipment returns 200 with no new refund.
- AC-11. Given a COD order that is not collected, or a refund amount of 0.00, when S1 is cancelled, then it is cancelled with no payment-service call and no refund row.

Seller-service touch (explicit): required, small, and only on the F-13 head (it needs `ledger_shipments`). seller-service must (a) record CANCELLED from SHIPMENT_STATUS_CHANGED and ORDER_CANCELLED parts in the register, as a terminal state, and (b) make `recordDelivered` skip a CANCELLED shipment. No new table, no reversal logic, no change to FEE policy. If F-13 is not merged first, this objective cannot satisfy AC4.

## 4. Edge cases and abuse cases
- A seller rejecting after payment to force a refund of another seller's goods (AC-6); or cancelling every shipment to drain a coupon or coins (released once, AC-9).
- Rounding: the last shipment's share is the remainder, so shares sum to the order's refundable total to the cent. Coupon, coins and wallet share use the same rule.
- Mixed payments (wallet plus card): the legs split pro rata and capped by what is unrefunded, as ADR 0008 does.
- Cancel while a return exists on the same order for another shipment: both refunds together never exceed the amount paid.
- Replay of RETURN_REFUNDED or DELIVERED events out of order (AC4, deferred).
- Order on risk hold; shipment already on a payout (a cancelled shipment never has a SALE, so no payout row).
- Admin cancel with no seller: allowed for any shipment with the same pre-dispatch rule.

## 5. Open questions
None blocking. Defaults (CEO may relay to the Owner): (1) seller and admin only, not the customer; (2) PENDING card refund counts as accepted, and a later FAILED is the same known gap as whole-order cancel, to be tracked in a follow-up; (3) shipping rides on the event that completes the order (AC-7); (4) if the F-13 head cannot be the base for the seller-service guard, AC4 moves to a follow-up and the Owner is told that the stale-DELIVERED guard is missing.
