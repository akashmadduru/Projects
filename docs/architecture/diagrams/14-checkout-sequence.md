# 14. Checkout Sequence

Source: `services/order-service/order-service-app/.../service/OrderService.java`, `OrderStore.java`, `pages/CheckoutPage.tsx`.

All remote calls run sequentially on the request thread (bearer token relayed) with no DB transaction open. Only the insert is transactional.

```mermaid
sequenceDiagram
    autonumber
    participant SPA as Storefront
    participant GW as Gateway
    participant OS as order-service (8087)
    participant CS as cart-service (8086)
    participant US as user-service (8085)
    participant PS as product-service (8082)
    participant DB as order_db

    SPA->>GW: POST /api/orders {addressId} + Idempotency-Key (UUID, reused on retry)
    GW->>OS: forward (JWT)
    OS->>DB: find (userId, key)
    alt key seen, same addressId
        OS-->>SPA: 200 original order
    else key seen, different addressId
        OS-->>SPA: 422 DOMAIN_RULE_VIOLATION (IDEMPOTENCY_KEY_REUSED)
    else new key
        OS->>CS: GET /api/cart
        OS-->>SPA: 409 CART_EMPTY (if no items)
        OS->>US: GET /api/users/me/addresses/{id}
        OS-->>SPA: 404 ADDRESS_NOT_FOUND (if missing)
        loop each cart line (max 50)
            OS->>PS: GET /api/products/{id}
            Note right of OS: price = salePrice > 0 ? salePrice : priceAmount
        end
        OS-->>SPA: 409 PRODUCT_UNAVAILABLE / CURRENCY_MISMATCH (on rule failure)
        OS->>DB: INSERT order PLACED + items (one tx)
        OS->>CS: DELETE /api/cart (best-effort, failure logged only)
        OS-->>SPA: 201 OrderResponse
    end
```

Not in this flow (see techdebt): inventory reservation (TD-BE-01), payment (TD-BE-02), `order.events` (TD-BE-04).
