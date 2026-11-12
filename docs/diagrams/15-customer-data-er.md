# 15. Customer Data ER (user_db, cart_db, order_db)

Source: `V1__user_baseline.sql`, `V1__cart_baseline.sql`, `V1__order_baseline.sql`. Each database is separate; `user_id` is the auth-service JWT `sub` and has no cross-database FK. `orders.orders.address_id` is a reference by value (the address is copied into `ship_*` columns).

```mermaid
erDiagram
    USER_PROFILES {
        uuid user_id PK
        varchar email
        varchar display_name
        varchar phone
        timestamptz created_at
        timestamptz updated_at
        bigint version
    }
    ADDRESSES {
        uuid id PK
        uuid user_id
        varchar label
        varchar full_name
        varchar line1
        varchar line2
        varchar city
        varchar state
        varchar postal_code
        char country
        boolean is_default "unique per user where true"
    }
    USER_PROFILES ||--o{ ADDRESSES : "user_id (logical)"

    CARTS {
        uuid id PK
        uuid user_id UK
        timestamptz updated_at
        bigint version
    }
    CART_ITEMS {
        uuid id PK
        uuid cart_id FK
        uuid product_id "UK with cart_id"
        int quantity "1..99"
        varchar name
        numeric unit_price
        char currency
        varchar image_url
    }
    WISHLIST_ITEMS {
        uuid id PK
        uuid user_id "UK with product_id"
        uuid product_id
        varchar name
        numeric price
        char currency
    }
    CARTS ||--o{ CART_ITEMS : contains

    ORDERS {
        uuid id PK
        uuid user_id
        varchar status "PLACED|CANCELLED"
        char currency
        numeric total_amount
        timestamptz placed_at
        timestamptz cancelled_at
        varchar idempotency_key "UK with user_id"
        uuid address_id
        varchar ship_full_name
        varchar ship_line1
        varchar ship_city
        varchar ship_postal_code
        char ship_country
    }
    ORDER_ITEMS {
        uuid id PK
        uuid order_id FK
        uuid product_id
        varchar sku
        varchar name
        numeric unit_price
        int quantity
        numeric line_total
    }
    ORDERS ||--o{ ORDER_ITEMS : contains
```

Schemas: `users`, `cart`, `orders` (inside `user_db`, `cart_db`, `order_db`). Some columns omitted for brevity (`ship_phone`, `ship_line2`, `ship_state`, `image_url`, timestamps).
