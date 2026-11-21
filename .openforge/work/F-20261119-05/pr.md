# Seller stock integrity: atomic offer and stock writes, seller stock events, fail-closed reservations (TD-P9-04, TD-P9-05)

Branch `forge/F-20261119-05-seller-stock-integrity`, head `64fdb21d`. It is stacked on the referral pilot F-20261119-01 (fcf6f2a2), so merge F-01 first.

**Deploy order: product-service, then order-service, then inventory-service.** The new `sellerId` and `offerId` fields are additive, and backward compatibility is tested in both directions.

## Why
- **Stock written without an offer:** when a seller created an offer or product and the stock write failed, the offer was left with no stock row, or with a partial one.
- **Seller stock events:** these were treated as platform stock events.
- **Bulk CSV:** a file could hold the same SKU twice.
- **Reservations:** a seller's offer could be reserved against platform stock, which oversold it.

## What changed
- **product-service**
  - `SellerCatalogService`: creating an offer or product now writes stock through one `stockWrite` helper. If the write fails with an unknown outcome (5xx, timeout or open circuit), the service makes a best-effort call to zero the stock. It then discards the offer, or deletes the product, and returns 503. A validation failure (400 or 403) skips the zeroing step.
  - Price sync runs outside the compensated block.
  - Bulk CSV rejects a repeated SKU with `duplicate sku in file`.
  - The inventory listener ignores offer-scoped quantity events.
- **order-service**
  - The reserve request now carries the owner of each line.
  - An offer whose owner is unknown sends `sellerId=null`, so inventory resolves the owner and rejects the line if it can't. The order item stays at PLATFORM.
  - This also fixes a broken order-service test build that was already failing on the base branch (B-5).
- **inventory-service**
  - Reservation falls back to platform rows only when the owner is PLATFORM. A seller offer, an unknown owner, or a stale or orphan offerId produces a shortfall.
  - Owner lookup through `SellerDirectory` runs before the transaction and is memoised for each request. An upstream error returns 503 before any lock is taken.
  - A seller stock write publishes one after-commit, offer-scoped `QUANTITY_CHANGED` event, and only when the quantity actually changed.
- **Docs:** endpoints.md and events.md, covering the contract notes, the 503s, the orphan-row caveats and the deploy order.

## Evidence
- `forge-verify --changed` passes on the cart, inventory, order and product tests: 68 suites, 313 tests, 0 skipped, 0 failures.
- QA mapped AC-1..AC-11 to passing tests in the JUnit XML. `forge-gate` passes.
- About 300 production lines (class M).

## Review
- Independent code and security review ran over 4 rounds, plus a QA catch.
- Problems fixed along the way:
  - Two CTO-condition tests could never fail. One was an orphan-row test seeded with a quantity of 0; the other was a concurrency test that never actually raced writes. Both were rewritten.
  - QA found that a round-2 change had persisted `order_items.seller_id` as NULL, because the column is `updatable=false` and the value was set after the first save. This was fixed in round 3.
- A planted-defect benchmark ran on slice 1. The reviewer caught 2 of 2 seeded bugs.
- Design: ADR 0004 (Accepted).

## Known issues (not blocking; in the backlog; unreachable while `offers.seller_id` is NOT NULL)
- An owner-less offer line is booked to PLATFORM even when inventory resolves a real seller. The cleaner fix is to reject the line at placement.
- `Order.attach` now contains a dead `al.sellerId()` branch and a dead post-save `tagOffer`.
- A mixed cart could label the platform shipment with a seller's display name.
- Orphan seller rows can remain after a timed-out create. They are documented in endpoints.md.
- Pre-existing issues:
  - Inventory trusts a caller-supplied `sellerId` within `/internal/**` (hardening item).
  - Warehouse ownership is not enforced on the internal stock path (B-6).
  - Bulk CSV errors echo raw exception text (B-7).
- Tech-debt register rows for TD-P9-04 and TD-P9-05 will be updated by the register-sync objective.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
