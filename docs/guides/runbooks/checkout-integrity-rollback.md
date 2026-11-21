# Runbook: roll back the durable cart clear (checkout integrity, PR-C)

**When:** order-service with the `CLEAR_CART` outbox kind must go back to an older image, for example
after a bad deploy. Background: ADR 0010 (checkout integrity), TD-BE-03.

**Why the order matters:** an older relay that meets an outbox row of a kind it does not know can
stall the whole pass, so orders stop sending events and compensations. Once the unknown-kind
tolerance (PR-A of F-20261119-11) is live, an old relay logs ERROR and skips such rows. Step 2 below
is then only a backstop, but it is cheap, so always run it.

## Deploy order (forward)

1. **PR-A first.** order-service with the fail-closed fingerprint and the relay's unknown-kind
   tolerance must be live **before** PR-C is deployed. Do not deploy PR-C on top of an order-service
   that lacks it.
2. product-service (PR-B) and cart-service (PR-C: `POST /internal/carts/{userId}/consumed`).
3. order-service (PR-B and PR-C).

Until cart-service has the endpoint, a clear is retried by the outbox (404 counts as a failure), and
checkout itself is not affected.

## Rollback steps (in this order)

1. Stop or scale down the new order-service pods, so no new `CLEAR_CART` rows are written and the
   relay stops.
2. Mark the waiting clears DEAD, so an old relay never meets them:

   ```sql
   -- order_db
   UPDATE orders.outbox SET status = 'DEAD' WHERE kind = 'CLEAR_CART' AND status = 'PENDING';
   ```

3. Start the old image.

The only cost of step 2 is that those orders keep their lines in the cart until the user next checks
out (the fingerprint guard still refuses a second order for the same cart and clears it then).

## Bringing the clears back (after rolling forward again)

`DEAD` rows are listed at `GET /api/admin/orders/outbox` and are retried one at a time with
`POST /api/admin/orders/outbox/{id}/retry` (ADMIN). A `CLEAR_CART` row removes only the lines that
order consumed, and repeating it is harmless, so retrying every `DEAD` `CLEAR_CART` row is safe.

## Checks

- `select kind, status, count(*) from orders.outbox group by 1, 2;` shows no `CLEAR_CART` `PENDING`
  rows after step 2.
- The relay logs `OUTBOX DEAD` for a `CLEAR_CART` row that gave up after 400 attempts (about 26 h at
  a 4-minute cap); that is the alert to act on.
