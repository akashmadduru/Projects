# Schema inventory

_Generated 2026-11-15T03:10:17Z by `python main.py inspect`. Do not edit._

## Services

| service | database | flyway default schema | migrations | JPA entities | tables |
|---|---|---|---|---|---|
| auth-service | auth_db | auth | 4 | 11 | 11 |
| cart-service | cart_db | cart | 5 | 0 | 7 |
| inventory-service | inventory_db | inventory | 7 | 3 | 6 |
| notification-service | notification_db | notification | 1 | 0 | 4 |
| order-service | order_db | orders | 7 | 6 | 7 |
| payment-service | payment_db | payment | 3 | 0 | 6 |
| product-service | product_db | product | 9 | 5 | 8 |
| promotion-service | promotion_db | promotion | 4 | 0 | 8 |
| rating-service | rating_db | rating | 5 | 5 | 6 |
| seller-service | seller_db | seller | 2 | 0 | 6 |
| support-service | support_db | support | 2 | 0 | 6 |
| user-service | user_db | users | 4 | 2 | 13 |

Tables: **88** across **12** databases; physical FKs: **41**; logical (cross-service) references: **111**.

## auth_db

### auth.federated_identities

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  | FK→auth.users(id) |
| provider | character varying(50) | N |  |  |
| provider_user_id | character varying(255) | N |  |  |
| provider_email | auth.citext | Y |  |  |
| email_verified_at_link | boolean | N | false |  |
| linked_at | timestamp with time zone | N | now() |  |
| last_login_at | timestamp with time zone | Y |  |  |

- unique: (provider,provider_user_id); (user_id,provider)

### auth.known_devices

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  | FK→auth.users(id) |
| fingerprint | character(64) | N |  |  |
| first_seen | timestamp with time zone | N | now() |  |
| last_seen | timestamp with time zone | N | now() |  |

- unique: (user_id,fingerprint)

### auth.mfa_challenges

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| token_hash | character(64) | N |  |  |
| user_id | uuid | N |  | FK→auth.users(id) |
| auth_method | character varying(20) | N |  |  |
| attempts | smallint | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| expires_at | timestamp with time zone | N |  |  |
| consumed_at | timestamp with time zone | Y |  |  |

- unique: (token_hash)

### auth.one_time_tokens

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  | FK→auth.users(id) |
| purpose | character varying(30) | N |  |  |
| token_hash | character(64) | N |  |  |
| target | character varying(320) | Y |  |  |
| attempts | smallint | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| expires_at | timestamp with time zone | N |  |  |
| consumed_at | timestamp with time zone | Y |  |  |

- unique: (purpose,token_hash)
- checks: CHECK (((purpose)::text = ANY ((ARRAY['PASSWORD_RESET'::character varying, 'EMAIL_VERIFY'::character varying, 

### auth.recovery_codes

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  | FK→auth.users(id) |
| code_hash | character(64) | N |  |  |
| used_at | timestamp with time zone | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |

### auth.refresh_tokens

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  | FK→auth.users(id) |
| token_hash | character(64) | N |  |  |
| family_id | uuid | N |  |  |
| parent_id | uuid | Y |  | FK→auth.refresh_tokens(id) |
| auth_method | character varying(20) | N |  |  |
| issued_at | timestamp with time zone | N | now() |  |
| expires_at | timestamp with time zone | N |  |  |
| family_absolute_expires_at | timestamp with time zone | N |  |  |
| rotated_at | timestamp with time zone | Y |  |  |
| revoked_at | timestamp with time zone | Y |  |  |
| revoked_reason | character varying(32) | Y |  |  |
| user_agent | character varying(400) | Y |  |  |
| ip_address | inet | Y |  |  |

- unique: (token_hash)
- checks: CHECK (((revoked_reason)::text = ANY ((ARRAY['ROTATED'::character varying, 'LOGOUT'::character varying, 'REUSE; CHECK (((auth_method)::text = ANY ((ARRAY['password'::character varying, 'google'::character varying, 'otp'::c

### auth.roles

| column | type | null | default | notes |
|---|---|---|---|---|
| id | smallint | N |  | PK |
| name | character varying(50) | N |  |  |
| description | character varying(255) | Y |  |  |

- unique: (name)

### auth.user_credentials

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK FK→auth.users(id) |
| password_hash | character varying(255) | N |  |  |
| algorithm | character varying(32) | N | 'argon2id'::character varying |  |
| password_updated_at | timestamp with time zone | N | now() |  |
| failed_attempts | smallint | N | 0 |  |
| locked_until | timestamp with time zone | Y |  |  |
| must_change_password | boolean | N | false |  |

### auth.user_roles

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK FK→auth.users(id) |
| role_id | smallint | N |  | PK FK→auth.roles(id) |
| granted_at | timestamp with time zone | N | now() |  |

### auth.user_totp

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  | FK→auth.users(id) |
| secret_enc | character varying(200) | N |  |  |
| enabled | boolean | N | false |  |
| last_used_step | bigint | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| enabled_at | timestamp with time zone | Y |  |  |

- unique: (user_id)

### auth.users

> Platform identities. Credentials live in user_credentials / federated_identities.

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| email | auth.citext | N |  |  |
| email_verified | boolean | N | false |  |
| display_name | character varying(200) | N |  |  |
| status | character varying(20) | N | 'ACTIVE'::character varying |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| version | bigint | N | 0 |  |
| phone | character varying(20) | Y |  |  |
| phone_verified | boolean | N | false |  |

- unique: (email)
- checks: CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying, 'LOCKED'::cha

## cart_db

### cart.alerts

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| email | character varying(254) | N |  |  |
| product_id | uuid | N |  |  |
| variant_id | uuid | Y |  |  |
| variant_key | uuid | Y | COALESCE(variant_id, '00000000-0000-0000 | generated |
| product_name | character varying(300) | Y |  |  |
| type | character varying(20) | N |  |  |
| target_price | numeric(19,4) | Y |  |  |
| last_price | numeric(19,4) | Y |  |  |
| currency | character(3) | Y |  |  |
| active | boolean | N | true |  |
| triggered_at | timestamp with time zone | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (user_id,product_id,variant_key,type)
- checks: CHECK (((type)::text = ANY ((ARRAY['PRICE_DROP'::character varying, 'BACK_IN_STOCK'::character varying])::text

### cart.cart_items

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| cart_id | uuid | N |  | FK→cart.carts(id) |
| product_id | uuid | N |  |  |
| quantity | integer | N |  |  |
| name | character varying(300) | N |  |  |
| unit_price | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| image_url | character varying(2048) | Y |  |  |
| added_at | timestamp with time zone | N | now() |  |
| variant_id | uuid | Y |  |  |
| variant_label | character varying(200) | Y |  |  |
| variant_key | uuid | Y | COALESCE(variant_id, '00000000-0000-0000 | generated |
| offer_id | uuid | Y |  |  |
| seller_id | uuid | Y |  |  |
| offer_key | uuid | Y | COALESCE(offer_id, '00000000-0000-0000-0 | generated |

- unique: (cart_id,product_id,variant_key,offer_key)
- checks: CHECK (((quantity >= 1) AND (quantity <= 99))); CHECK ((unit_price >= (0)::numeric))

### cart.carts

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| updated_at | timestamp with time zone | N | now() |  |
| version | bigint | N | 0 |  |
| email | character varying(320) | Y |  |  |
| reminder_sent_at | timestamp with time zone | Y |  |  |

- unique: (user_id)

### cart.list_items

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| list_id | uuid | N |  | FK→cart.lists(id) |
| product_id | uuid | N |  |  |
| variant_id | uuid | Y |  |  |
| variant_label | character varying(200) | Y |  |  |
| variant_key | uuid | Y | COALESCE(variant_id, '00000000-0000-0000 | generated |
| name | character varying(300) | N |  |  |
| price | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| image_url | character varying(2048) | Y |  |  |
| note | character varying(300) | Y |  |  |
| added_at | timestamp with time zone | N | now() |  |

- unique: (list_id,product_id,variant_key)
- checks: CHECK ((price >= (0)::numeric))

### cart.lists

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| name | character varying(60) | N |  |  |
| is_default | boolean | N | false |  |
| visibility | character varying(10) | N | 'PRIVATE'::character varying |  |
| share_token | character varying(40) | Y |  |  |
| owner_name | character varying(60) | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (share_token)
- checks: CHECK (((visibility)::text = ANY ((ARRAY['PRIVATE'::character varying, 'SHARED'::character varying])::text[]))

### cart.saved_items

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| product_id | uuid | N |  |  |
| name | character varying(300) | N |  |  |
| unit_price | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| image_url | character varying(2048) | Y |  |  |
| saved_at | timestamp with time zone | N | now() |  |
| variant_id | uuid | Y |  |  |
| variant_label | character varying(200) | Y |  |  |
| variant_key | uuid | Y | COALESCE(variant_id, '00000000-0000-0000 | generated |
| offer_id | uuid | Y |  |  |
| seller_id | uuid | Y |  |  |
| offer_key | uuid | Y | COALESCE(offer_id, '00000000-0000-0000-0 | generated |

- unique: (user_id,product_id,variant_key,offer_key)
- checks: CHECK ((unit_price >= (0)::numeric))

### cart.wishlist_items

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| product_id | uuid | N |  |  |
| name | character varying(300) | N |  |  |
| price | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| image_url | character varying(2048) | Y |  |  |
| added_at | timestamp with time zone | N | now() |  |
| variant_id | uuid | Y |  |  |
| variant_label | character varying(200) | Y |  |  |
| variant_key | uuid | Y | COALESCE(variant_id, '00000000-0000-0000 | generated |
| note | character varying(300) | Y |  |  |

- unique: (user_id,product_id,variant_key)
- checks: CHECK ((price >= (0)::numeric))

## inventory_db

### inventory.reservation_releases

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  |  |
| release_key | character varying(100) | N |  |  |
| product_id | uuid | N |  |  |
| quantity | integer | N |  |  |
| created_at | timestamp with time zone | N | now() |  |
| variant_id | uuid | Y |  |  |

- checks: CHECK ((quantity > 0))

### inventory.reservations

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  |  |
| product_id | uuid | N |  |  |
| quantity | integer | N |  |  |
| restored_quantity | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| variant_id | uuid | Y |  |  |
| warehouse_id | uuid | Y |  |  |
| offer_id | uuid | Y |  |  |
| seller_id | uuid | Y |  |  |

- checks: CHECK ((quantity > 0)); CHECK ((restored_quantity >= 0))

### inventory.stock_levels

> Stock levels, one row per product. Event-linked to product.products, no FK - see LLD S4a.

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  |  |
| quantity | integer | N | 0 |  |
| version | bigint | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| warehouse_code | character varying(32) | Y |  |  |
| product_name | character varying(200) | Y |  |  |
| product_sku | character varying(64) | Y |  |  |
| low_stock_threshold | integer | N | 10 |  |
| reserved_quantity | integer | N | 0 |  |
| status | character varying(32) | Y |  |  |
| last_updated_at | timestamp with time zone | Y |  |  |

- unique: (product_id)
- checks: CHECK ((quantity >= 0)); CHECK (((reserved_quantity >= 0) AND (reserved_quantity <= quantity))); CHECK (((status IS NULL) OR ((status)::text = ANY ((ARRAY['IN_STOCK'::character varying, 'LOW_STOCK'::characte

### inventory.variant_stock

| column | type | null | default | notes |
|---|---|---|---|---|
| variant_id | uuid | N |  | PK |
| product_id | uuid | Y |  |  |
| quantity | integer | N | 0 |  |
| updated_at | timestamp with time zone | N | now() |  |

- checks: CHECK ((quantity >= 0))

### inventory.warehouse_stock

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  |  |
| variant_id | uuid | Y |  |  |
| variant_key | uuid | Y | COALESCE(variant_id, '00000000-0000-0000 | generated |
| seller_id | uuid | N |  |  |
| offer_id | uuid | Y |  |  |
| warehouse_id | uuid | N |  | FK→inventory.warehouses(id) |
| quantity | integer | N | 0 |  |
| updated_at | timestamp with time zone | N | now() |  |

- unique: (product_id,variant_key,seller_id,warehouse_id)
- checks: CHECK ((quantity >= 0))

### inventory.warehouses

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| code | character varying(32) | N |  |  |
| name | character varying(200) | N |  |  |
| seller_id | uuid | N |  |  |
| pincode | character varying(10) | N |  |  |
| state | character varying(64) | Y |  |  |
| active | boolean | N | true |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (code)

## notification_db

### notification.campaigns

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| name | character varying(120) | N |  |  |
| channel | character varying(10) | N |  |  |
| audience | character varying(20) | N |  |  |
| subject | character varying(200) | N |  |  |
| body | text | N |  |  |
| link | character varying(500) | Y |  |  |
| status | character varying(10) | N | 'DRAFT'::character varying |  |
| sent_count | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| sent_at | timestamp with time zone | Y |  |  |

- checks: CHECK (((channel)::text = ANY ((ARRAY['EMAIL'::character varying, 'PUSH'::character varying, 'INBOX'::characte; CHECK (((audience)::text = ANY ((ARRAY['ALL'::character varying, 'MEMBERS'::character varying, 'INACTIVE_30D':; CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SENDING'::character varying, 'SENT'::charact

### notification.notifications

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| type | character varying(10) | N |  |  |
| title | character varying(200) | N |  |  |
| body | character varying(1000) | Y |  |  |
| link | character varying(500) | Y |  |  |
| dedupe_key | character varying(120) | Y |  |  |
| read_at | timestamp with time zone | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (dedupe_key)
- checks: CHECK (((type)::text = ANY ((ARRAY['ORDER'::character varying, 'ALERT'::character varying, 'PROMO'::character 

### notification.push_subscriptions

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| endpoint | character varying(2048) | N |  |  |
| p256dh | character varying(200) | N |  |  |
| auth | character varying(100) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |
| last_success_at | timestamp with time zone | Y |  |  |

### notification.settings

| column | type | null | default | notes |
|---|---|---|---|---|
| key | character varying(80) | N |  | PK |
| value | text | N |  |  |

## order_db

### orders.order_items

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  | FK→orders.orders(id) |
| product_id | uuid | N |  |  |
| sku | character varying(64) | Y |  |  |
| name | character varying(300) | N |  |  |
| unit_price | numeric(19,4) | N |  |  |
| quantity | integer | N |  |  |
| line_total | numeric(19,4) | N |  |  |
| image_url | character varying(2048) | Y |  |  |
| variant_id | uuid | Y |  |  |
| variant_label | character varying(200) | Y |  |  |
| shipment_id | uuid | Y |  | FK→orders.order_shipments(id) |
| offer_id | uuid | Y |  |  |
| seller_id | uuid | Y |  |  |

- checks: CHECK ((quantity > 0))

### orders.order_return_items

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| return_id | uuid | N |  | FK→orders.order_returns(id) |
| product_id | uuid | N |  |  |
| name | character varying(300) | N |  |  |
| quantity | integer | N |  |  |
| unit_price | numeric(19,4) | N |  |  |
| variant_id | uuid | Y |  |  |

- checks: CHECK ((quantity > 0))

### orders.order_returns

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  | FK→orders.orders(id) |
| user_id | uuid | N |  |  |
| status | character varying(20) | N |  |  |
| reason | character varying(30) | N |  |  |
| comment | character varying(1000) | Y |  |  |
| refund_amount | numeric(19,4) | N |  |  |
| admin_note | character varying(1000) | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| version | bigint | N | 0 |  |
| refund_to | character varying(10) | N | 'SOURCE'::character varying |  |
| shipment_id | uuid | Y |  |  |
| abuse_flag | boolean | N | false |  |

### orders.order_shipments

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  | FK→orders.orders(id) |
| seller_id | uuid | N |  |  |
| seller_name | character varying(200) | Y |  |  |
| warehouse_id | uuid | Y |  |  |
| warehouse_code | character varying(32) | Y |  |  |
| warehouse_pincode | character varying(20) | Y |  |  |
| status | character varying(20) | N |  |  |
| tracking_number | character varying(100) | Y |  |  |
| carrier | character varying(100) | Y |  |  |
| estimated_delivery_date | date | Y |  |  |
| delivered_at | timestamp with time zone | Y |  |  |
| amount | numeric(19,4) | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| version | bigint | N | 0 |  |

### orders.order_status_history

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  | FK→orders.orders(id) |
| status | character varying(20) | N |  |  |
| note | character varying(500) | Y |  |  |
| actor | character varying(10) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |
| shipment_id | uuid | Y |  |  |

### orders.orders

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| status | character varying(20) | N |  |  |
| currency | character(3) | N |  |  |
| total_amount | numeric(19,4) | N |  |  |
| placed_at | timestamp with time zone | N | now() |  |
| cancelled_at | timestamp with time zone | Y |  |  |
| idempotency_key | character varying(100) | N |  |  |
| address_id | uuid | N |  |  |
| ship_full_name | character varying(120) | N |  |  |
| ship_phone | character varying(32) | Y |  |  |
| ship_line1 | character varying(200) | N |  |  |
| ship_line2 | character varying(200) | Y |  |  |
| ship_city | character varying(100) | N |  |  |
| ship_state | character varying(100) | Y |  |  |
| ship_postal_code | character varying(20) | N |  |  |
| ship_country | character(2) | N |  |  |
| version | bigint | N | 0 |  |
| payment_method | character varying(10) | N | 'CARD'::character varying |  |
| payment_status | character varying(24) | N | 'PAID'::character varying |  |
| payment_id | character varying(64) | Y |  |  |
| subtotal_amount | numeric(19,4) | N |  |  |
| discount_amount | numeric(19,4) | N | 0 |  |
| coupon_code | character varying(40) | Y |  |  |
| shipping_amount | numeric(19,4) | N | 0 |  |
| tax_amount | numeric(19,4) | N | 0 |  |
| tax_label | character varying(40) | Y |  |  |
| cgst_amount | numeric(19,4) | Y |  |  |
| sgst_amount | numeric(19,4) | Y |  |  |
| igst_amount | numeric(19,4) | Y |  |  |
| refunded_amount | numeric(19,4) | N | 0 |  |
| email | character varying(320) | Y |  |  |
| estimated_delivery_date | date | Y |  |  |
| tracking_number | character varying(100) | Y |  |  |
| carrier | character varying(100) | Y |  |  |
| delivered_at | timestamp with time zone | Y |  |  |
| coins_redeemed | integer | N | 0 |  |
| coins_value | numeric(19,4) | N | 0 |  |
| wallet_amount | numeric(19,4) | N | 0 |  |
| membership_free_shipping | boolean | N | false |  |
| subscription_id | uuid | Y |  |  |
| risk_score | integer | N | 0 |  |
| risk_reasons | text | N | '[]'::text |  |
| risk_hold | boolean | N | false |  |
| exchange_quote_id | uuid | Y |  |  |
| exchange_amount | numeric(19,4) | N | 0 |  |
| exchange_shortfall | numeric(19,4) | N | 0 |  |

- unique: (user_id,idempotency_key)
- checks: CHECK (((status)::text = ANY ((ARRAY['PENDING_PAYMENT'::character varying, 'PLACED'::character varying, 'CONFI; CHECK (((payment_method)::text = ANY ((ARRAY['CARD'::character varying, 'COD'::character varying, 'WALLET'::ch

### orders.subscriptions

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| email | character varying(320) | Y |  |  |
| product_id | uuid | N |  |  |
| variant_id | uuid | Y |  |  |
| product_name | character varying(300) | Y |  |  |
| quantity | integer | N |  |  |
| frequency_days | integer | N |  |  |
| next_run_on | date | N |  |  |
| address_id | uuid | N |  |  |
| payment_method | character varying(10) | N |  |  |
| status | character varying(10) | N |  |  |
| discount_percent | integer | N | 5 |  |
| last_order_id | uuid | Y |  |  |
| failures | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |

- checks: CHECK (((quantity >= 1) AND (quantity <= 99))); CHECK ((frequency_days = ANY (ARRAY[7, 14, 30, 60]))); CHECK (((payment_method)::text = ANY ((ARRAY['COD'::character varying, 'WALLET'::character varying])::text[])); CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAUSED'::character varying, 'CANCELLED'::ch

## payment_db

### payment.gift_cards

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| code | character varying(19) | N |  |  |
| amount | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| status | character varying(16) | N |  |  |
| purchaser_user_id | uuid | N |  |  |
| purchaser_email | character varying(320) | Y |  |  |
| recipient_email | character varying(320) | N |  |  |
| message | character varying(500) | Y |  |  |
| redeemed_by | uuid | Y |  |  |
| redeemed_at | timestamp with time zone | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |
| expires_at | timestamp with time zone | N | (now() + '1 year'::interval) |  |

- unique: (code)
- checks: CHECK ((amount > (0)::numeric)); CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REDEEMED'::character varying, 'PENDING_PAYM

### payment.payments

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  |  |
| user_id | uuid | N |  |  |
| method | character varying(10) | N |  |  |
| mode | character varying(10) | N |  |  |
| amount | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| status | character varying(20) | N |  |  |
| stripe_payment_intent_id | character varying(100) | Y |  |  |
| refunded_amount | numeric(19,4) | N | 0 |  |
| reported | boolean | N | false |  |
| report_attempts | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| purpose | character varying(12) | N | 'ORDER'::character varying |  |
| reference_id | uuid | Y |  |  |
| provider_order_id | character varying(100) | Y |  |  |
| provider_payment_id | character varying(100) | Y |  |  |

- unique: (order_id)
- checks: CHECK (((method)::text = ANY ((ARRAY['CARD'::character varying, 'COD'::character varying])::text[]))); CHECK ((amount > (0)::numeric)); CHECK (((status)::text = ANY ((ARRAY['REQUIRES_PAYMENT'::character varying, 'PROCESSING'::character varying, '; CHECK (((purpose)::text = ANY ((ARRAY['ORDER'::character varying, 'MEMBERSHIP'::character varying, 'GIFT_CARD'; CHECK (((mode)::text = ANY ((ARRAY['STRIPE'::character varying, 'RAZORPAY'::character varying, 'SIMULATED'::ch

### payment.processed_events

| column | type | null | default | notes |
|---|---|---|---|---|
| event_id | character varying(100) | N |  | PK |
| received_at | timestamp with time zone | N | now() |  |

### payment.refunds

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| payment_id | uuid | N |  | FK→payment.payments(id) |
| amount | numeric(19,4) | N |  |  |
| reason | character varying(200) | Y |  |  |
| stripe_refund_id | character varying(100) | Y |  |  |
| idempotency_key | character varying(100) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |
| provider_refund_id | character varying(100) | Y |  |  |

- unique: (idempotency_key)
- checks: CHECK ((amount > (0)::numeric))

### payment.wallet_txns

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| type | character varying(6) | N |  |  |
| amount | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| reason | character varying(12) | N |  |  |
| reference_id | character varying(100) | Y |  |  |
| idempotency_key | character varying(150) | N |  |  |
| balance_after | numeric(19,4) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (idempotency_key)
- checks: CHECK (((type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying])::text[]))); CHECK ((amount > (0)::numeric)); CHECK (((reason)::text = ANY ((ARRAY['REFUND'::character varying, 'GIFT_CARD'::character varying, 'ORDER'::cha

### payment.wallets

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| balance | numeric(19,4) | N | 0 |  |
| currency | character(3) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |

- checks: CHECK ((balance >= (0)::numeric))

## product_db

### product.categories

> Catalog categories, one level of subcategory. Depth enforced in the service layer, not by a DB constraint.

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| name | character varying(120) | N |  |  |
| parent_category_id | uuid | Y |  | FK→product.categories(id) |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| description | text | Y |  |  |
| depth_level | integer | N | 0 |  |
| is_active | boolean | N | true |  |
| version | bigint | N | 0 |  |

### product.fx_rates

| column | type | null | default | notes |
|---|---|---|---|---|
| currency | character varying(3) | N |  | PK |
| base | character varying(3) | N |  |  |
| rate | numeric(18,6) | N |  |  |
| updated_at | timestamp with time zone | N | now() |  |

- checks: CHECK ((rate > (0)::numeric))

### product.offers

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  | FK→product.products(id) |
| variant_id | uuid | Y |  |  |
| seller_id | uuid | N |  |  |
| price | numeric(19,2) | N |  |  |
| sale_price | numeric(19,2) | Y |  |  |
| currency | character varying(3) | N |  |  |
| condition | character varying(20) | N | 'NEW'::character varying |  |
| dispatch_days | integer | N | 1 |  |
| active | boolean | N | true |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |

- checks: CHECK ((price >= (0)::numeric)); CHECK ((dispatch_days >= 0))

### product.product_images

> Product image URLs, ordered per-product by display_order.

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  | FK→product.products(id) |
| url | character varying(2048) | N |  |  |
| display_order | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |

### product.product_variants

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  | FK→product.products(id) |
| sku | character varying(80) | N |  |  |
| attributes | jsonb | N | '{}'::jsonb |  |
| price_amount | numeric(19,2) | Y |  |  |
| sale_price | numeric(19,2) | Y |  |  |
| image_url | character varying(2048) | Y |  |  |
| active | boolean | N | true |  |
| sort_order | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |

- unique: (sku)

### product.product_views

| column | type | null | default | notes |
|---|---|---|---|---|
| id | bigint | N | nextval('product.product_views_id_seq':: | PK |
| session_id | character varying(64) | N |  |  |
| product_id | uuid | N |  |  |
| viewed_at | timestamp with time zone | N | now() |  |

### product.products

> Catalog products. Phase 1E: read-only, no categories/inventory/pricing rules.

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| sku | character varying(64) | N |  |  |
| name | character varying(200) | N |  |  |
| price_amount | numeric(19,2) | N |  |  |
| price_currency | character(3) | N |  |  |
| active | boolean | N | true |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| version | bigint | N | 0 |  |
| category_id | uuid | Y |  | FK→product.categories(id) |
| seller_name | character varying(200) | Y |  |  |
| specifications | jsonb | Y |  |  |
| last_known_quantity | integer | Y |  |  |
| description | text | Y |  |  |
| list_price | numeric(19,2) | Y |  |  |
| sale_price | numeric(19,2) | Y |  |  |
| discount_percent | numeric(5,2) | Y |  |  |
| thumbnail_url | character varying(2048) | Y |  |  |
| video_urls | jsonb | Y |  |  |
| view_360_url | character varying(2048) | Y |  |  |
| weight_grams | integer | Y |  |  |
| dimensions_length | numeric(10,2) | Y |  |  |
| dimensions_width | numeric(10,2) | Y |  |  |
| dimensions_height | numeric(10,2) | Y |  |  |
| color | character varying(100) | Y |  |  |
| material | character varying(100) | Y |  |  |
| average_rating | numeric(3,2) | Y |  |  |
| rating_count | integer | N | 0 |  |
| review_count | integer | N | 0 |  |
| rating_distribution | jsonb | Y |  |  |
| is_featured | boolean | N | false |  |
| seller_rating | numeric(3,2) | Y |  |  |
| is_seller_verified | boolean | N | false |  |
| brand | character varying(120) | Y |  |  |
| attributes | jsonb | Y |  |  |
| slug | character varying(240) | Y |  |  |
| search_vector | tsvector | Y | ((setweight(to_tsvector('english'::regco | generated |
| seller_id | uuid | N | '00000000-0000-0000-0000-00000000a11e':: |  |
| listed_by | character varying(20) | N | 'PLATFORM'::character varying |  |

- unique: (sku); (slug)
- checks: CHECK ((price_amount >= (0)::numeric)); CHECK ((price_currency ~ '^[A-Z]{3}$'::text))

### product.theme_settings

| column | type | null | default | notes |
|---|---|---|---|---|
| id | smallint | N | 1 | PK |
| config | text | N |  |  |
| updated_at | timestamp with time zone | N | now() |  |

- checks: CHECK ((id = 1))

## promotion_db

### promotion.bank_offers

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| bank | character varying(60) | N |  |  |
| card_type | character varying(10) | N | 'ANY'::character varying |  |
| title | character varying(200) | N |  |  |
| discount_type | character varying(10) | N |  |  |
| value | numeric(19,4) | N |  |  |
| max_discount | numeric(19,4) | Y |  |  |
| min_amount | numeric(19,4) | N | 0 |  |
| currency | character(3) | Y |  |  |
| active | boolean | N | true |  |
| starts_at | timestamp with time zone | Y |  |  |
| ends_at | timestamp with time zone | Y |  |  |

- checks: CHECK (((card_type)::text = ANY ((ARRAY['CREDIT'::character varying, 'DEBIT'::character varying, 'ANY'::charac; CHECK (((discount_type)::text = ANY ((ARRAY['PERCENT'::character varying, 'FLAT'::character varying])::text[]); CHECK ((value > (0)::numeric))

### promotion.coin_ledger

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| delta | integer | N |  |  |
| reason | character varying(20) | N |  |  |
| reference_id | character varying(100) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |
| expires_at | timestamp with time zone | Y |  |  |

- unique: (reason,reference_id,user_id)
- checks: CHECK ((delta <> 0)); CHECK (((reason)::text = ANY ((ARRAY['EARN_ORDER'::character varying, 'REDEEM_ORDER'::character varying, 'REVE

### promotion.coupons

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| code | character varying(40) | N |  |  |
| description | character varying(300) | Y |  |  |
| type | character varying(10) | N |  |  |
| value | numeric(19,4) | N |  |  |
| max_discount | numeric(19,4) | Y |  |  |
| min_order_amount | numeric(19,4) | N | 0 |  |
| currency | character(3) | Y |  |  |
| starts_at | timestamp with time zone | Y |  |  |
| ends_at | timestamp with time zone | Y |  |  |
| total_limit | integer | Y |  |  |
| per_user_limit | integer | Y |  |  |
| first_order_only | boolean | N | false |  |
| active | boolean | N | true |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |

- unique: (code)
- checks: CHECK (((code)::text = upper((code)::text))); CHECK (((type)::text = ANY ((ARRAY['PERCENT'::character varying, 'FLAT'::character varying])::text[]))); CHECK ((value > (0)::numeric)); CHECK ((max_discount > (0)::numeric)); CHECK ((min_order_amount >= (0)::numeric)); CHECK ((total_limit > 0)); CHECK ((per_user_limit > 0)); CHECK ((((type)::text <> 'FLAT'::text) OR (currency IS NOT NULL)))

### promotion.deal_claims

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| order_id | uuid | N |  |  |
| deal_id | uuid | N |  | FK→promotion.deals(id) |
| quantity | integer | N |  |  |
| released | boolean | N | false |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (order_id,deal_id)
- checks: CHECK ((quantity > 0))

### promotion.deals

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  |  |
| variant_id | uuid | Y |  |  |
| title | character varying(200) | Y |  |  |
| deal_price | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| starts_at | timestamp with time zone | N | now() |  |
| ends_at | timestamp with time zone | N |  |  |
| quantity_limit | integer | N |  |  |
| claimed | integer | N | 0 |  |
| active | boolean | N | true |  |
| created_at | timestamp with time zone | N | now() |  |
| members_only_until | timestamp with time zone | Y |  |  |

- checks: CHECK ((deal_price > (0)::numeric)); CHECK ((quantity_limit > 0)); CHECK ((claimed >= 0))

### promotion.exchange_programs

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| category_id | uuid | N |  |  |
| brand | character varying(80) | Y |  |  |
| title | character varying(200) | N |  |  |
| max_value | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| active | boolean | N | true |  |
| created_at | timestamp with time zone | N | now() |  |

- checks: CHECK ((max_value > (0)::numeric))

### promotion.exchange_quotes

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| program_id | uuid | N |  | FK→promotion.exchange_programs(id) |
| device_brand | character varying(80) | N |  |  |
| device_model | character varying(120) | N |  |  |
| device_condition | character varying(20) | N |  |  |
| age_years | integer | N |  |  |
| quoted_value | numeric(19,4) | N |  |  |
| currency | character(3) | N |  |  |
| expires_at | timestamp with time zone | N |  |  |
| status | character varying(12) | N | 'QUOTED'::character varying |  |
| order_id | uuid | Y |  |  |
| final_value | numeric(19,4) | Y |  |  |
| shortfall | numeric(19,4) | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| verified_at | timestamp with time zone | Y |  |  |

- checks: CHECK (((device_condition)::text = ANY ((ARRAY['WORKING'::character varying, 'SCREEN_DAMAGED'::character varyi; CHECK ((age_years >= 0)); CHECK (((status)::text = ANY ((ARRAY['QUOTED'::character varying, 'APPLIED'::character varying, 'PICKED_UP'::c

### promotion.redemptions

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| coupon_id | uuid | N |  | FK→promotion.coupons(id) |
| user_id | uuid | N |  |  |
| order_id | uuid | N |  |  |
| discount_amount | numeric(19,4) | N |  |  |
| status | character varying(10) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (order_id)
- checks: CHECK (((status)::text = ANY ((ARRAY['APPLIED'::character varying, 'RELEASED'::character varying])::text[])))

## rating_db

### rating.answers

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| question_id | uuid | N |  | FK→rating.questions(id) |
| user_id | uuid | N |  |  |
| author_name | character varying(200) | Y |  |  |
| text | character varying(2000) | N |  |  |
| verified_buyer | boolean | N | false |  |
| upvotes | integer | N | 0 |  |
| created_at | timestamp with time zone | N |  |  |

### rating.qa_votes

| column | type | null | default | notes |
|---|---|---|---|---|
| item_type | character varying(16) | N |  | PK |
| item_id | uuid | N |  | PK |
| user_id | uuid | N |  | PK |
| created_at | timestamp with time zone | N | now() |  |

### rating.questions

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  |  |
| user_id | uuid | N |  |  |
| author_name | character varying(200) | Y |  |  |
| text | character varying(1000) | N |  |  |
| answer_count | integer | N | 0 |  |
| upvotes | integer | N | 0 |  |
| created_at | timestamp with time zone | N |  |  |

### rating.rating_votes

> Helpful/unhelpful votes on ratings.

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| rating_id | uuid | N |  | FK→rating.ratings(id) |
| user_id | uuid | N |  |  |
| vote_type | character varying(32) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (rating_id,user_id)
- checks: CHECK (((vote_type)::text = ANY ((ARRAY['HELPFUL'::character varying, 'UNHELPFUL'::character varying])::text[]

### rating.ratings

> Product ratings and reviews.

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| product_id | uuid | N |  |  |
| user_id | uuid | N |  |  |
| user_display_name | character varying(200) | Y |  |  |
| rating_value | integer | N |  |  |
| review_title | character varying(200) | Y |  |  |
| review_text | text | Y |  |  |
| is_verified_purchase | boolean | N | false |  |
| helpful_count | integer | N | 0 |  |
| unhelpful_count | integer | N | 0 |  |
| version | bigint | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| flagged | boolean | N | false |  |
| flag_reasons | text | Y |  |  |
| moderated | boolean | N | false |  |

- unique: (product_id,user_id)
- checks: CHECK (((rating_value >= 1) AND (rating_value <= 5)))

### rating.review_media

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| rating_id | uuid | N |  | FK→rating.ratings(id) |
| file_name | character varying(80) | N |  |  |
| content_type | character varying(40) | N |  |  |
| size_bytes | bigint | N |  |  |
| created_at | timestamp with time zone | N |  |  |

## seller_db

### seller.ad_campaigns

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| seller_id | uuid | N |  | FK→seller.sellers(id) |
| product_id | uuid | N |  |  |
| keywords | text[] | N | '{}'::text[] |  |
| category_id | uuid | Y |  |  |
| bid_cpc | numeric(10,2) | N |  |  |
| daily_budget | numeric(12,2) | N |  |  |
| currency | character varying(3) | N | 'USD'::character varying |  |
| status | character varying(20) | N | 'ACTIVE'::character varying |  |
| spent_today | numeric(12,2) | N | 0 |  |
| clicks | bigint | N | 0 |  |
| impressions | bigint | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |

- checks: CHECK ((bid_cpc > (0)::numeric)); CHECK ((daily_budget > (0)::numeric)); CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAUSED'::character varying, 'EXHAUSTED'::ch

### seller.ledger_entries

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| seller_id | uuid | N |  | FK→seller.sellers(id) |
| type | character varying(30) | N |  |  |
| amount | numeric(14,2) | N |  |  |
| currency | character varying(3) | N |  |  |
| order_id | uuid | N |  |  |
| shipment_id | uuid | N |  |  |
| created_at | timestamp with time zone | N | now() |  |
| payout_id | uuid | Y |  | FK→seller.payouts(id) |
| return_id | uuid | Y |  |  |

- checks: CHECK (((type)::text = ANY ((ARRAY['SALE'::character varying, 'COMMISSION'::character varying, 'FEE'::characte

### seller.payouts

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| seller_id | uuid | N |  | FK→seller.sellers(id) |
| amount | numeric(14,2) | N |  |  |
| currency | character varying(3) | N |  |  |
| status | character varying(20) | N | 'PAID'::character varying |  |
| reference | character varying(40) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |

### seller.sale_lines

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| seller_id | uuid | N |  | FK→seller.sellers(id) |
| order_id | uuid | N |  |  |
| shipment_id | uuid | N |  |  |
| product_id | uuid | N |  |  |
| name | character varying(300) | Y |  |  |
| quantity | integer | N |  |  |
| amount | numeric(14,2) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |

### seller.seller_feedback

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| seller_id | uuid | N |  | FK→seller.sellers(id) |
| order_id | uuid | N |  |  |
| shipment_id | uuid | N |  |  |
| user_id | uuid | N |  |  |
| rating | smallint | N |  |  |
| comment | character varying(500) | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (shipment_id,user_id)
- checks: CHECK (((rating >= 1) AND (rating <= 5)))

### seller.sellers

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| legal_name | character varying(200) | Y |  |  |
| display_name | character varying(120) | N |  |  |
| slug | character varying(140) | N |  |  |
| email | character varying(254) | Y |  |  |
| phone | character varying(20) | Y |  |  |
| gstin | character varying(15) | Y |  |  |
| pan | character varying(10) | Y |  |  |
| bank_account_last4 | character varying(4) | Y |  |  |
| bank_account_hash | text | Y |  |  |
| ifsc | character varying(11) | Y |  |  |
| pickup_address | jsonb | Y |  |  |
| status | character varying(20) | N | 'DRAFT'::character varying |  |
| rejection_reason | character varying(500) | Y |  |  |
| commission_percent | numeric(5,2) | N | 10 |  |
| rating_avg | numeric(3,2) | N | 0 |  |
| rating_count | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| approved_at | timestamp with time zone | Y |  |  |
| updated_at | timestamp with time zone | N | now() |  |

- unique: (user_id); (slug)
- checks: CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::c

## support_db

### support.audit_log

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N |  | PK |
| actor_user_id | uuid | Y |  |  |
| actor_roles | character varying(200) | Y |  |  |
| action | character varying(80) | N |  |  |
| resource_type | character varying(80) | Y |  |  |
| resource_id | character varying(80) | Y |  |  |
| before_json | text | Y |  |  |
| after_json | text | Y |  |  |
| ip | character varying(64) | Y |  |  |
| at | timestamp with time zone | N |  |  |
| received_at | timestamp with time zone | N | now() |  |

### support.chat_messages

| column | type | null | default | notes |
|---|---|---|---|---|
| id | bigint | N | nextval('support.chat_messages_id_seq':: | PK |
| session_id | character varying(64) | N |  | FK→support.chat_sessions(session_id) |
| role | character varying(4) | N |  |  |
| text | character varying(2000) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |

### support.chat_sessions

| column | type | null | default | notes |
|---|---|---|---|---|
| session_id | character varying(64) | N |  | PK |
| user_id | uuid | Y |  |  |
| low_conf | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |

### support.faq_articles

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| slug | character varying(160) | N |  |  |
| category | character varying(20) | N |  |  |
| title | character varying(200) | N |  |  |
| body | text | N |  |  |
| keywords | text[] | N | '{}'::text[] |  |
| published | boolean | N | true |  |
| helpful_yes | integer | N | 0 |  |
| helpful_no | integer | N | 0 |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |

- unique: (slug)
- checks: CHECK (((category)::text = ANY ((ARRAY['ORDERS'::character varying, 'PAYMENTS'::character varying, 'RETURNS'::

### support.ticket_messages

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| ticket_id | uuid | N |  | FK→support.tickets(id) |
| author_type | character varying(10) | N |  |  |
| author_id | uuid | Y |  |  |
| body | character varying(5000) | N |  |  |
| internal | boolean | N | false |  |
| created_at | timestamp with time zone | N | now() |  |

- checks: CHECK (((author_type)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'AGENT'::character varying, 'BOT'::ch

### support.tickets

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| email | character varying(320) | N |  |  |
| order_id | uuid | Y |  |  |
| shipment_id | uuid | Y |  |  |
| category | character varying(20) | N |  |  |
| subject | character varying(200) | N |  |  |
| status | character varying(20) | N | 'OPEN'::character varying |  |
| priority | character varying(10) | N | 'NORMAL'::character varying |  |
| assignee_user_id | uuid | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| first_response_at | timestamp with time zone | Y |  |  |
| resolved_at | timestamp with time zone | Y |  |  |
| first_response_due_at | timestamp with time zone | N |  |  |
| resolution_due_at | timestamp with time zone | N |  |  |
| csat | smallint | Y |  |  |

- checks: CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'PENDING_CUSTOMER'::character varying, 'PENDIN; CHECK (((priority)::text = ANY ((ARRAY['LOW'::character varying, 'NORMAL'::character varying, 'HIGH'::characte; CHECK (((csat IS NULL) OR ((csat >= 1) AND (csat <= 5))))

## user_db

### users.addresses

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| label | character varying(60) | Y |  |  |
| full_name | character varying(120) | N |  |  |
| phone | character varying(32) | Y |  |  |
| line1 | character varying(200) | N |  |  |
| line2 | character varying(200) | Y |  |  |
| city | character varying(100) | N |  |  |
| state | character varying(100) | Y |  |  |
| postal_code | character varying(20) | N |  |  |
| country | character(2) | N |  |  |
| is_default | boolean | N | false |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| version | bigint | N | 0 |  |

### users.delete_codes

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| code_hash | character(64) | N |  |  |
| attempts | smallint | N | 0 |  |
| expires_at | timestamp with time zone | N |  |  |

### users.deletion_requests

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| email | character varying(320) | Y |  |  |
| status | character varying(12) | N |  |  |
| requested_at | timestamp with time zone | N | now() |  |
| scheduled_for | timestamp with time zone | N |  |  |
| completed_at | timestamp with time zone | Y |  |  |

- checks: CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'CANCELLED'::character varying, 'COMPLETE

### users.export_jobs

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| email | character varying(320) | Y |  |  |
| status | character varying(10) | N | 'PENDING'::character varying |  |
| content | bytea | Y |  |  |
| size_bytes | bigint | Y |  |  |
| error | character varying(300) | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |
| completed_at | timestamp with time zone | Y |  |  |
| expires_at | timestamp with time zone | Y |  |  |

- checks: CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'READY'::char

### users.membership_payments

| column | type | null | default | notes |
|---|---|---|---|---|
| id | uuid | N | gen_random_uuid() | PK |
| user_id | uuid | N |  |  |
| plan | character varying(40) | N |  |  |
| currency | character(3) | N |  |  |
| amount | numeric(19,4) | N |  |  |
| email | character varying(320) | Y |  |  |
| status | character varying(20) | N | 'PENDING'::character varying |  |
| payment_id | character varying(64) | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |

- checks: CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::c

### users.membership_plan_prices

| column | type | null | default | notes |
|---|---|---|---|---|
| plan_code | character varying(40) | N |  | PK FK→users.membership_plans(code) |
| currency | character(3) | N |  | PK |
| price | numeric(19,4) | N |  |  |

- checks: CHECK ((price > (0)::numeric))

### users.membership_plans

| column | type | null | default | notes |
|---|---|---|---|---|
| code | character varying(40) | N |  | PK |
| name | character varying(80) | N |  |  |
| days | integer | N |  |  |
| active | boolean | N | true |  |

- checks: CHECK ((days > 0))

### users.memberships

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| plan | character varying(40) | N |  |  |
| status | character varying(20) | N |  |  |
| starts_at | timestamp with time zone | Y |  |  |
| ends_at | timestamp with time zone | Y |  |  |
| auto_renew | boolean | N | false |  |
| payment_id | character varying(64) | Y |  |  |
| email | character varying(320) | Y |  |  |
| updated_at | timestamp with time zone | N | now() |  |

- checks: CHECK (((status)::text = ANY ((ARRAY['PENDING_PAYMENT'::character varying, 'ACTIVE'::character varying, 'EXPIR

### users.recently_viewed

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| product_id | uuid | N |  | PK |
| viewed_at | timestamp with time zone | N | now() |  |

### users.referral_claims

| column | type | null | default | notes |
|---|---|---|---|---|
| referee_user_id | uuid | N |  | PK |
| referrer_user_id | uuid | N |  |  |
| code | character varying(8) | N |  |  |
| claimed_at | timestamp with time zone | N | now() |  |
| rewarded_at | timestamp with time zone | Y |  |  |

### users.referral_codes

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| code | character varying(8) | N |  |  |
| created_at | timestamp with time zone | N | now() |  |

- unique: (code)

### users.user_preferences

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| email_orders | boolean | N | true |  |
| email_promotions | boolean | N | true |  |
| push_orders | boolean | N | true |  |
| push_promotions | boolean | N | false |  |
| sms | boolean | N | true |  |
| updated_at | timestamp with time zone | N | now() |  |

### users.user_profiles

| column | type | null | default | notes |
|---|---|---|---|---|
| user_id | uuid | N |  | PK |
| email | character varying(320) | Y |  |  |
| display_name | character varying(120) | Y |  |  |
| phone | character varying(32) | Y |  |  |
| created_at | timestamp with time zone | N | now() |  |
| updated_at | timestamp with time zone | N | now() |  |
| version | bigint | N | 0 |  |

## Cross-service logical references (no DB-level FK)

| child | column | → parent |
|---|---|---|
| cart_db.cart.alerts | user_id | auth_db.auth.users.id |
| cart_db.cart.alerts | product_id | product_db.product.products.id |
| cart_db.cart.alerts | variant_id | product_db.product.product_variants.id |
| cart_db.cart.cart_items | product_id | product_db.product.products.id |
| cart_db.cart.cart_items | variant_id | product_db.product.product_variants.id |
| cart_db.cart.cart_items | offer_id | product_db.product.offers.id |
| cart_db.cart.cart_items | seller_id | seller_db.seller.sellers.id |
| cart_db.cart.carts | user_id | auth_db.auth.users.id |
| cart_db.cart.list_items | product_id | product_db.product.products.id |
| cart_db.cart.list_items | variant_id | product_db.product.product_variants.id |
| cart_db.cart.lists | user_id | auth_db.auth.users.id |
| cart_db.cart.saved_items | user_id | auth_db.auth.users.id |
| cart_db.cart.saved_items | product_id | product_db.product.products.id |
| cart_db.cart.saved_items | variant_id | product_db.product.product_variants.id |
| cart_db.cart.saved_items | offer_id | product_db.product.offers.id |
| cart_db.cart.saved_items | seller_id | seller_db.seller.sellers.id |
| cart_db.cart.wishlist_items | user_id | auth_db.auth.users.id |
| cart_db.cart.wishlist_items | product_id | product_db.product.products.id |
| cart_db.cart.wishlist_items | variant_id | product_db.product.product_variants.id |
| inventory_db.inventory.reservation_releases | order_id | order_db.orders.orders.id |
| inventory_db.inventory.reservation_releases | product_id | product_db.product.products.id |
| inventory_db.inventory.reservation_releases | variant_id | product_db.product.product_variants.id |
| inventory_db.inventory.reservations | order_id | order_db.orders.orders.id |
| inventory_db.inventory.reservations | product_id | product_db.product.products.id |
| inventory_db.inventory.reservations | variant_id | product_db.product.product_variants.id |
| inventory_db.inventory.reservations | warehouse_id | inventory_db.inventory.warehouses.id |
| inventory_db.inventory.reservations | offer_id | product_db.product.offers.id |
| inventory_db.inventory.stock_levels | product_id | product_db.product.products.id |
| inventory_db.inventory.variant_stock | variant_id | product_db.product.product_variants.id |
| inventory_db.inventory.variant_stock | product_id | product_db.product.products.id |
| inventory_db.inventory.warehouse_stock | product_id | product_db.product.products.id |
| inventory_db.inventory.warehouse_stock | variant_id | product_db.product.product_variants.id |
| inventory_db.inventory.warehouse_stock | seller_id | seller_db.seller.sellers.id |
| inventory_db.inventory.warehouse_stock | offer_id | product_db.product.offers.id |
| inventory_db.inventory.warehouses | seller_id | seller_db.seller.sellers.id |
| notification_db.notification.notifications | user_id | auth_db.auth.users.id |
| notification_db.notification.push_subscriptions | user_id | auth_db.auth.users.id |
| order_db.orders.order_items | product_id | product_db.product.products.id |
| order_db.orders.order_items | variant_id | product_db.product.product_variants.id |
| order_db.orders.order_items | offer_id | product_db.product.offers.id |
| order_db.orders.order_items | seller_id | seller_db.seller.sellers.id |
| order_db.orders.order_return_items | product_id | product_db.product.products.id |
| order_db.orders.order_return_items | variant_id | product_db.product.product_variants.id |
| order_db.orders.order_returns | user_id | auth_db.auth.users.id |
| order_db.orders.order_returns | shipment_id | order_db.orders.order_shipments.id |
| order_db.orders.order_shipments | warehouse_id | inventory_db.inventory.warehouses.id |
| order_db.orders.order_status_history | shipment_id | order_db.orders.order_shipments.id |
| order_db.orders.orders | user_id | auth_db.auth.users.id |
| order_db.orders.orders | address_id | user_db.users.addresses.id |
| order_db.orders.orders | subscription_id | order_db.orders.subscriptions.id |
| order_db.orders.orders | exchange_quote_id | promotion_db.promotion.exchange_quotes.id |
| order_db.orders.subscriptions | user_id | auth_db.auth.users.id |
| order_db.orders.subscriptions | product_id | product_db.product.products.id |
| order_db.orders.subscriptions | variant_id | product_db.product.product_variants.id |
| order_db.orders.subscriptions | address_id | user_db.users.addresses.id |
| payment_db.payment.gift_cards | purchaser_user_id | auth_db.auth.users.id |
| payment_db.payment.gift_cards | redeemed_by | auth_db.auth.users.id |
| payment_db.payment.payments | order_id | order_db.orders.orders.id |
| payment_db.payment.payments | user_id | auth_db.auth.users.id |
| payment_db.payment.wallet_txns | user_id | auth_db.auth.users.id |
| payment_db.payment.wallets | user_id | auth_db.auth.users.id |
| product_db.product.offers | variant_id | product_db.product.product_variants.id |
| product_db.product.offers | seller_id | seller_db.seller.sellers.id |
| product_db.product.product_views | product_id | product_db.product.products.id |
| promotion_db.promotion.coin_ledger | user_id | auth_db.auth.users.id |
| promotion_db.promotion.deal_claims | order_id | order_db.orders.orders.id |
| promotion_db.promotion.deals | product_id | product_db.product.products.id |
| promotion_db.promotion.deals | variant_id | product_db.product.product_variants.id |
| promotion_db.promotion.exchange_programs | category_id | product_db.product.categories.id |
| promotion_db.promotion.exchange_quotes | user_id | auth_db.auth.users.id |
| promotion_db.promotion.exchange_quotes | order_id | order_db.orders.orders.id |
| promotion_db.promotion.redemptions | user_id | auth_db.auth.users.id |
| promotion_db.promotion.redemptions | order_id | order_db.orders.orders.id |
| rating_db.rating.answers | user_id | auth_db.auth.users.id |
| rating_db.rating.qa_votes | user_id | auth_db.auth.users.id |
| rating_db.rating.questions | product_id | product_db.product.products.id |
| rating_db.rating.questions | user_id | auth_db.auth.users.id |
| rating_db.rating.rating_votes | user_id | auth_db.auth.users.id |
| rating_db.rating.ratings | product_id | product_db.product.products.id |
| rating_db.rating.ratings | user_id | auth_db.auth.users.id |
| seller_db.seller.ad_campaigns | product_id | product_db.product.products.id |
| seller_db.seller.ad_campaigns | category_id | product_db.product.categories.id |
| seller_db.seller.ledger_entries | order_id | order_db.orders.orders.id |
| seller_db.seller.ledger_entries | shipment_id | order_db.orders.order_shipments.id |
| seller_db.seller.ledger_entries | return_id | order_db.orders.order_returns.id |
| seller_db.seller.sale_lines | order_id | order_db.orders.orders.id |
| seller_db.seller.sale_lines | shipment_id | order_db.orders.order_shipments.id |
| seller_db.seller.sale_lines | product_id | product_db.product.products.id |
| seller_db.seller.seller_feedback | order_id | order_db.orders.orders.id |
| seller_db.seller.seller_feedback | shipment_id | order_db.orders.order_shipments.id |
| seller_db.seller.seller_feedback | user_id | auth_db.auth.users.id |
| seller_db.seller.sellers | user_id | auth_db.auth.users.id |
| support_db.support.audit_log | actor_user_id | auth_db.auth.users.id |
| support_db.support.chat_sessions | user_id | auth_db.auth.users.id |
| support_db.support.tickets | user_id | auth_db.auth.users.id |
| support_db.support.tickets | order_id | order_db.orders.orders.id |
| support_db.support.tickets | shipment_id | order_db.orders.order_shipments.id |
| support_db.support.tickets | assignee_user_id | auth_db.auth.users.id |
| user_db.users.addresses | user_id | auth_db.auth.users.id |
| user_db.users.delete_codes | user_id | auth_db.auth.users.id |
| user_db.users.deletion_requests | user_id | auth_db.auth.users.id |
| user_db.users.export_jobs | user_id | auth_db.auth.users.id |
| user_db.users.membership_payments | user_id | auth_db.auth.users.id |
| user_db.users.memberships | user_id | auth_db.auth.users.id |
| user_db.users.recently_viewed | user_id | auth_db.auth.users.id |
| user_db.users.recently_viewed | product_id | product_db.product.products.id |
| user_db.users.referral_claims | referee_user_id | auth_db.auth.users.id |
| user_db.users.referral_claims | referrer_user_id | auth_db.auth.users.id |
| user_db.users.referral_codes | user_id | auth_db.auth.users.id |
| user_db.users.user_preferences | user_id | auth_db.auth.users.id |
| user_db.users.user_profiles | user_id | auth_db.auth.users.id |
