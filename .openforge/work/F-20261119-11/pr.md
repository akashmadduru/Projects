# Checkout integrity: fail-closed cart fingerprint, outbox unknown-kind tolerance, one batched product lookup, durable scoped cart clear (TD-BE-15/07/06/03, ADR 0010)

**Three stacked PRs** on `forge/F-20261119-11-checkout-integrity`, built on F-20261119-10 (820e5a8). Merge after the F-01 → F-04 → F-09 → F-10 chain.

| Part | Range | Production lines | Covers |
|---|---|---|---|
| PR-A | 820e5a8..a9265001 | 67 | fail-closed fingerprint, outbox relay tolerates unknown kinds, regression cover for AC-1..AC-9b |
| PR-B | a9265001..c3d8e3b4 | 104 | `GET /api/products/batch?detail=true`, checkout makes one product call (AC-10..AC-14) |
| PR-C | c3d8e3b4..b7f5b974 | 316 | durable, scoped cart clear on the order outbox (AC-15..AC-20), plus PR-B follow-ups |

## Deploy order (required)
1. **PR-A first, and confirm it is live.** Its relay tolerates unknown outbox kinds, so a later rollback of PR-C cannot stall payment or stock events.
2. Then deploy product-service and cart-service (PR-B, PR-C).
3. Then deploy order-service (PR-B, PR-C).

Rolling back PR-C is covered in `docs/guides/runbooks/checkout-integrity-rollback.md`: stop the new pods, optionally mark CLEAR_CART rows DEAD, then start the old image.

## Why
- **Duplicate orders:** a cart line with no `addedAt` had no fingerprint, so concurrent checkouts could double-order it.
- **Product lookups:** checkout looked up products one at a time.
- **Cart clear:** it was best effort, so a cart-service outage left a stale cart behind.
- **Already in place:** the breakers and the different-key race guard existed but had no regression cover.

## What changed
- **PR-A:**
  - **Fail-closed cart:** a cart line without `addedAt` gets 503 before any order row or side effect.
  - **Relay and unknown kinds:** the outbox relay keeps unknown kinds out of its batch but still honours them for per-aggregate ordering. They are logged once per pass.
  - **Tests:** breaker regressions, including half-open recovery, per-host isolation, 4xx not counted, and compensation (stock, coupon, coins, wallet) when an open breaker hits mid-placement.
- **PR-B:**
  - **Opt-in detail batch:** `detail=true` has the same shape as `/{id}` but leaves variant stock empty. The lean default is byte-identical.
  - **One product call:** checkout makes one batched call over the distinct ids. A missing id gives `PRODUCT_UNAVAILABLE` before save. Prices come only from product-service, and the user's bearer token is forwarded.
- **PR-C:**
  - **`CLEAR_CART` outbox kind:** keyed per user and enqueued with the order. It is tried inline after commit, and the relay retries it with a 240 s cap for up to 400 attempts. A 4xx other than 404/408/429 goes DEAD immediately.
  - **Internal endpoint:** `POST /internal/carts/{userId}/consumed`. It needs the internal token (fail-closed) and validates its input. It removes only the exact lines the order consumed, in that user's cart; lines added later survive. It never creates a cart.
  - **Internal client:** it sends the internal token and never the user's bearer.
  - **Gateway:** a test checks that it does not route `/internal/**`.
  - **Enqueue failure:** it never fails a placed order. For COD, `ORDER_PLACED` is recorded before the clear. A 409 is kept as a 409.

## Evidence
- `forge-verify --changed` PASS at every part head. At b7f5b974 that covers the env reference check plus the cart, gateway, order and product test suites.
- QA mapped AC-1..AC-20 and AC-9b to passing tests in the JUnit XML. `forge-gate` PASS: 3 contiguous parts, each under 600 lines, each approved at its own head.
- Guard tests were shown failing on their real assertion:
  - outbox starvation: a valid row behind 101 unknown rows was never delivered;
  - the race test;
  - whole-cart deletion;
  - a cross-user clear;
  - 401 checks;
  - a leaked bearer token;
  - an enqueue failure making a COD order return 500;
  - cart creation for unknown users;
  - a 4xx retried for 26 h.

## Review
Independent code and security review, 7 rounds in total.
- **PR-A:** the code reviewer found 2 majors:
  - outbox batch starvation: 100 or more unknown rows stalled every payment and stock event;
  - wallet compensation was untested.
- **PR-B:** found that a javadoc wrongly claimed "no inventory call".
- **PR-C:** found that a failed outbox insert could skip a COD order's `ORDER_PLACED` event.
- All three were fixed. Both reviewers approve every part head.
- Design: ADR 0010 (Accepted). The CTO accepted it with 7 conditions and moved relay tolerance into PR-A.

## Known issues (approved minors, follow-ups)
- A CLEAR_CART row that gets 401/403, for example from a misconfigured internal token, goes DEAD immediately instead of retrying. Replay is harmless, and the runbook covers it.
- When an enqueue fails, the inline clear is skipped too, and the failure is logged at WARN rather than ERROR.
- Public `detail=true` with 50 ids makes about 50 inventory calls (TD-BE-18).
- Inactive products can be read anonymously, as `/{id}` already allows (TD-BE-17, Owner's call).
- CLEAR_CART payloads (per-user line data) stay in `orders.outbox` (TD-BE-19).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
