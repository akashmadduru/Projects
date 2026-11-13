# HTTP API reference

**Verified against the code:** 2026-11-14 (conventions, roles, error codes, rate limits, paging, checkout).
The request and response examples for product, inventory and rating were written for Phases 1–4.
All 19 of those endpoints still exist. The examples show the Phase 1–4 fields; for the full current field list read the DTOs in each `-api` module (`ProductResponse`, `InventoryResponse`, `RatingResponse`).
**Every endpoint in every service:** [endpoints.md](endpoints.md) (309, generated from the controllers).

## Contents

1. [Conventions](#conventions)
2. [Product service](#product-service-api)
3. [Inventory service](#inventory-service-api)
4. [Rating service](#rating-service-api)
5. [Customer services: user, cart, order](#customer-services-user-cart-order)

---

## Conventions

### Base URL

Clients call the gateway: `http://localhost:8080` locally. Each service also listens on its own
port (8081–8092, see [getting started](../getting-started.md#ports)) for debugging; production
exposes only the gateway. `/internal/**` endpoints are service-to-service only, need
`X-Internal-Token`, and are not routed by the gateway.

### Authentication

Send `Authorization: Bearer <access token>`. auth-service issues RS256 JWTs:

| Claim | Value |
|---|---|
| `sub` | user id (UUID). Every customer endpoint uses this as the user; a resource owned by someone else returns 404. |
| `roles` | list of roles, mapped to `ROLE_*` authorities by `RolesClaimAuthoritiesConverter` |
| `aud` | `ecommerce-platform` (`AUTH_JWT_AUDIENCES`) |
| `iss` | `https://auth.acme-ecommerce.internal` (`AUTH_JWT_ISSUER`) |
| `email`, `auth_method` | informational (`password`, `google`, `otp`) |

Roles: `VIEWER` (default for new customers), `CATALOG_MANAGER`, `ADMIN`, `SELLER` (Phase 9),
`SUPPORT`, `FINANCE` (Phase 10). The gateway requires `ROLE_ADMIN` for `/api/admin/**` except the
paths explicitly opened to `SUPPORT` / `FINANCE`, and each service checks again.

### Access rules for the Phase 1–4 endpoints

| Endpoint | Who |
|---|---|
| `GET /api/products/**`, `GET /api/categories/**` | Anyone (no token) |
| `POST /api/products/{id}/views` | Anyone |
| `POST`/`PUT`/`DELETE /api/products/**` | `CATALOG_MANAGER` or `ADMIN` |
| `GET /api/inventory/**` | Any signed-in user |
| `POST`/`PUT`/`DELETE /api/inventory/**` | `CATALOG_MANAGER` or `ADMIN` |
| `GET /api/ratings/products/{id}`, `…/summary`, questions, answers, media | Anyone |
| `POST /api/ratings/products/{id}` (write a review) | A signed-in user with an order for the product that is not `CANCELLED` or `PENDING_PAYMENT` (else 403 `REVIEW_NOT_ELIGIBLE`; 503 `DEPENDENCY_UNAVAILABLE` if order-service cannot be reached), one review per product (else 409 `REVIEW_ALREADY_EXISTS`) |
| `PUT`/`DELETE /api/ratings/{id}` | The author or `ADMIN` |
| `POST /api/ratings/{id}/votes/{voteType}` | A signed-in user, not on their own review (409 `CANNOT_VOTE_OWN_REVIEW`) |

### Errors

Every error is an RFC 9457 `application/problem+json` document:

```json
{
  "type": "https://errors.acme-ecommerce.internal/validation-failed",
  "status": 400,
  "title": "Validation failed",
  "detail": "Price amount must be non-negative",
  "instance": "/api/products",
  "code": "VALIDATION_FAILED"
}
```

`type` is `https://errors.acme-ecommerce.internal/` plus the code in lower case with `-` for `_`.
5xx responses never include exception text; the details are in the service log under the
`X-Correlation-Id` returned with the response.

All 38 codes (`libs/common-core/.../error/ErrorCode.java`):

| HTTP | Code | Title |
|---|---|---|
| 400 | `INVALID_PINCODE` | Invalid pincode |
| 400 | `VALIDATION_FAILED` | Validation failed |
| 401 | `INTERNAL_AUTH_FAILED` | Internal authentication failed |
| 401 | `UNAUTHENTICATED` | Unauthenticated |
| 402 | `PAYMENT_FAILED` | Payment failed |
| 403 | `FORBIDDEN` | Forbidden |
| 403 | `REVIEW_NOT_ELIGIBLE` | Review not eligible |
| 404 | `ADDRESS_NOT_FOUND` | Address not found |
| 404 | `PAYMENT_NOT_FOUND` | Payment not found |
| 404 | `RESOURCE_NOT_FOUND` | Resource not found |
| 409 | `ACCOUNT_DELETION_BLOCKED` | Account deletion blocked |
| 409 | `ADDRESS_LIMIT` | Address limit reached |
| 409 | `CANNOT_VOTE_OWN_REVIEW` | Cannot vote on own review |
| 409 | `CART_EMPTY` | Cart is empty |
| 409 | `CART_LIMIT` | Cart limit reached |
| 409 | `COUPON_USAGE_LIMIT` | Coupon usage limit reached |
| 409 | `CURRENCY_MISMATCH` | Currency mismatch |
| 409 | `DEAL_SOLD_OUT` | Deal sold out |
| 409 | `INSUFFICIENT_BALANCE` | Insufficient balance |
| 409 | `INVALID_STATUS_TRANSITION` | Invalid status transition |
| 409 | `ORDER_NOT_CANCELLABLE` | Order not cancellable |
| 409 | `OUT_OF_STOCK` | Out of stock |
| 409 | `PRODUCT_UNAVAILABLE` | Product unavailable |
| 409 | `RESOURCE_CONFLICT` | Resource conflict |
| 409 | `RETURN_NOT_ALLOWED` | Return not allowed |
| 409 | `RETURN_WINDOW_EXPIRED` | Return window expired |
| 409 | `REVIEW_ALREADY_EXISTS` | Review already exists |
| 409 | `WISHLIST_LIMIT` | Wishlist limit reached |
| 422 | `COUPON_EXPIRED` | Coupon expired |
| 422 | `COUPON_INVALID` | Coupon invalid |
| 422 | `COUPON_NOT_APPLICABLE` | Coupon not applicable |
| 422 | `DOMAIN_RULE_VIOLATION` | Domain rule violation |
| 422 | `GIFT_CARD_INVALID` | Gift card invalid |
| 422 | `PAYMENT_METHOD_UNAVAILABLE` | Payment method unavailable |
| 429 | `RATE_LIMITED` | Too many requests |
| 500 | `INTERNAL_ERROR` | Internal server error |
| 503 | `DEPENDENCY_UNAVAILABLE` | Dependency unavailable |
| 503 | `UPSTREAM_UNAVAILABLE` | Upstream service unavailable |

### Paging

List endpoints take `page` (0-based, default 0), `size` (default 20) and `sort` (`field,asc|desc`).
The maximum `size` is **100**, except order-service (**50**) and the admin user list (**50**); a larger
value is clamped. Responses use Spring's `PagedModel` shape:

```json
{ "content": [ ], "page": { "size": 20, "number": 0, "totalElements": 100, "totalPages": 5 } }
```

### Rate limits

auth-service only (Bucket4j). Limits are **per instance and per client IP per path**, so running
more replicas multiplies them. There is no gateway-level rate limit yet.

| Paths | Requests per minute |
|---|---|
| `/api/auth/login`, `/api/auth/register`, password forgot/reset, email verify and resend, OTP request and verify, `/api/auth/2fa/{setup,enable,disable,verify}`, `/api/auth/me/phone` | 10 (`AUTH_RATE_LIMIT_LOGIN_CAPACITY`) |
| `/api/auth/refresh` | 60 (`AUTH_RATE_LIMIT_REFRESH_CAPACITY`) |

Exceeding a limit returns 429 `RATE_LIMITED`.

### Idempotency

`POST /api/orders` requires an `Idempotency-Key` header. The same key with the same body returns the
original order (200); the same key with a different body returns 422 `DOMAIN_RULE_VIOLATION`
(rule `IDEMPOTENCY_KEY_REUSED`); a missing key returns 400 `VALIDATION_FAILED`.

---

## Product Service API

### Base Path: `/api/products` (Port 8082)

#### List Products (Paginated)

```http
GET /api/products?page=0&size=20&sort=sku,asc
```

**Query Parameters:**
- `page` — zero-indexed page number (default: 0)
- `size` — rows per page (default: 20, max: 100)
- `sort` — comma-separated sort fields (default: `sku,asc`)

**Response (200 OK):**
```json
{
  "content": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "sku": "SKU-KEYBOARD-01",
      "name": "Mechanical Keyboard",
      "priceAmount": 129.99,
      "priceCurrency": "USD",
      "active": true,
      "version": 2,
      "categoryId": null,
      "sellerName": null,
      "specifications": null,
      "images": [],
      "lastKnownQuantity": null,
      "description": null,
      "listPrice": null,
      "salePrice": null,
      "discountPercent": null,
      "thumbnailUrl": null,
      "videoUrls": null,
      "view360Url": null,
      "weightGrams": null,
      "dimensionsLength": null,
      "dimensionsWidth": null,
      "dimensionsHeight": null,
      "color": null,
      "material": null,
      "averageRating": null,
      "ratingCount": 0,
      "reviewCount": 0,
      "ratingDistribution": null,
      "isFeatured": false,
      "sellerRating": null,
      "isSellerVerified": false
    }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 100,
    "totalPages": 5
  }
}
```

#### Get Product by ID

```http
GET /api/products/{productId}
```

**Response (200 OK):** Same structure as list, but includes `images` array populated with ProductImageResponse objects.

**Errors:**
- 404 if product not found
- 400 if productId is not a valid UUID

#### Get Product with Inventory Aggregate

```http
GET /api/products/catalog/products/{productId}
```

Synchronously joins product data with current inventory from inventory-service.

**Response (200 OK):**
```json
{
  "product": { /* ProductResponse object */ },
  "inventory": {
    "id": "...",
    "productId": "...",
    "quantity": 42,
    "reservedQuantity": 5,
    "availableQuantity": 37,
    "status": "IN_STOCK",
    "lowStockThreshold": 10,
    "warehouseCode": "US-WEST",
    "productName": "...",
    "productSku": "...",
    "version": 1,
    "lastUpdatedAt": "2026-11-09T10:23:45.000Z",
    "createdAt": "2026-11-09T10:00:00.000Z",
    "updatedAt": "2026-11-09T10:23:45.000Z"
  }
}
```

If inventory is unreachable (timeout, 404, 5xx), the response includes `inventory: null`.

**Errors:**
- 404 if product not found
- 400 if productId is not a valid UUID

#### Create Product

```http
POST /api/products
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "sku": "SKU-LAPTOP-01",
  "name": "Gaming Laptop",
  "priceAmount": 1299.99,
  "priceCurrency": "USD",
  "active": true,
  "categoryId": null,
  "sellerName": "TechCorp",
  "specifications": {
    "processor": "Intel i7",
    "ram_gb": 16,
    "storage_type": "SSD"
  },
  "images": [
    {
      "url": "https://cdn.example.com/laptop-1.jpg",
      "displayOrder": 0
    }
  ],
  "initialQuantity": 50,
  "description": "High-performance gaming laptop",
  "listPrice": 1499.99,
  "salePrice": 1299.99,
  "discountPercent": 13.33,
  "thumbnailUrl": "https://cdn.example.com/laptop-thumb.jpg",
  "videoUrls": ["https://video.example.com/demo.mp4"],
  "view360Url": "https://view.example.com/laptop-360",
  "weightGrams": 2500,
  "dimensionsLength": 35.5,
  "dimensionsWidth": 25.0,
  "dimensionsHeight": 2.0,
  "color": "Space Gray",
  "material": "Aluminum",
  "isFeatured": true,
  "sellerRating": 4.8,
  "isSellerVerified": true
}
```

**Required Fields:** `sku`, `name`, `priceAmount`, `priceCurrency`  
**Constraints:**
- `sku` must be unique (409 if duplicate)
- `priceAmount` must be non-negative (422 if negative)
- `priceCurrency` must be 3-letter ISO-4217 code (400 if invalid)

**Response (201 CREATED):** ProductResponse object

**Errors:**
- 400 — validation failed
- 401 — not authenticated
- 403 — role not CATALOG_MANAGER or ADMIN
- 409 — SKU already exists

**Side Effect:** Publishes `ProductEvent(PRODUCT_CREATED)` to `product.events` Kafka topic.

#### Update Product

```http
PUT /api/products/{productId}
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "sku": "SKU-LAPTOP-01",
  "name": "Gaming Laptop Pro",
  "priceAmount": 1399.99,
  "priceCurrency": "USD",
  "active": true,
  "version": 2,
  "categoryId": null,
  "sellerName": "TechCorp",
  "specifications": { /* ... */ },
  "images": [ /* ... */ ],
  "description": "Updated description",
  "listPrice": 1599.99,
  "salePrice": 1399.99,
  "discountPercent": 12.51,
  "thumbnailUrl": "https://cdn.example.com/laptop-thumb-2.jpg",
  "videoUrls": ["https://video.example.com/demo-v2.mp4"],
  "view360Url": "https://view.example.com/laptop-360-v2",
  "weightGrams": 2400,
  "dimensionsLength": 35.5,
  "dimensionsWidth": 25.0,
  "dimensionsHeight": 1.9,
  "color": "Space Gray",
  "material": "Aluminum",
  "isFeatured": true,
  "sellerRating": 4.9,
  "isSellerVerified": true
}
```

**Required:** `version` field — caller must echo the version it last read. Optimistic locking prevents lost updates.

**Response (200 OK):** Updated ProductResponse

**Errors:**
- 400 — validation failed
- 404 — product not found
- 409 — version mismatch or SKU conflict

**Side Effect:** Publishes `ProductEvent(PRODUCT_UPDATED)` to Kafka.

#### Delete Product

```http
DELETE /api/products/{productId}
Authorization: Bearer <jwt>
```

**Response (204 No Content):** Empty body

**Errors:**
- 404 — product not found

**Side Effect:** Publishes `ProductEvent(PRODUCT_DELETED)` to Kafka. Images are cascaded-deleted by the database.

---

## Inventory Service API

### Base Path: `/api/inventory` (Port 8083)

#### List Inventory

```http
GET /api/inventory?page=0&size=20&sort=productId,asc
```

**Query Parameters:** Same as products (page, size, sort)

**Response (200 OK):** Paginated list of InventoryResponse objects

#### Get Inventory by ID

```http
GET /api/inventory/{inventoryId}
```

**Response (200 OK):** InventoryResponse object

#### Get Inventory by Product ID

```http
GET /api/inventory/by-product/{productId}
```

Used internally by product-service's aggregate read endpoint.

**Response (200 OK):** InventoryResponse object

**Errors:**
- 404 — no inventory row for this product (product not yet provisioned)

#### Create Inventory

```http
POST /api/inventory
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "productId": "11111111-1111-4111-8111-111111111111",
  "quantity": 100,
  "warehouseCode": "US-WEST",
  "lowStockThreshold": 10
}
```

**Required:** `productId`, `quantity`  
**Constraints:**
- `quantity >= 0`
- `productId` must not already have an inventory row (UNIQUE(product_id))

**Response (201 CREATED):** InventoryResponse

**Errors:**
- 400 — validation failed
- 409 — inventory already exists for this product

**Side effect:** every successful update publishes `InventoryEvent(INVENTORY_QUANTITY_CHANGED)` after commit, and `INVENTORY_LOW_STOCK` when the quantity is at or below the threshold.

#### Update Inventory

```http
PUT /api/inventory/{inventoryId}
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "quantity": 95,
  "reservedQuantity": 5,
  "warehouseCode": "US-WEST",
  "lowStockThreshold": 15,
  "version": 1
}
```

**Required:** `version` field for optimistic locking

**Response (200 OK):** Updated InventoryResponse

**Errors:**
- 400 — validation failed
- 404 — inventory not found
- 409 — version mismatch

**Side Effect:** Publishes `InventoryEvent(INVENTORY_QUANTITY_CHANGED)` to Kafka.

#### Delete Inventory

```http
DELETE /api/inventory/{inventoryId}
Authorization: Bearer <jwt>
```

**Response (204 No Content)**

**Errors:**
- 404 — inventory not found

---

## Rating Service API

### Base Path: `/api/ratings` (Port 8084)

#### List Ratings for Product

```http
GET /api/ratings/products/{productId}?page=0&size=20&sort=createdAt,desc
```

**Response (200 OK):**
```json
{
  "content": [
    {
      "id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
      "productId": "11111111-1111-4111-8111-111111111111",
      "userId": null,
      "userDisplayName": "Alice",
      "ratingValue": 5,
      "reviewTitle": "Excellent keyboard!",
      "reviewText": "Great typing experience, highly recommend.",
      "isVerifiedPurchase": true,
      "helpfulCount": 42,
      "unhelpfulCount": 2,
      "version": 1,
      "createdAt": "2026-11-05T08:00:00.000Z",
      "updatedAt": "2026-11-05T08:00:00.000Z"
    }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 15,
    "totalPages": 1
  }
}
```

Note: `userId` is **not** included in list responses (only on detail/edit).

#### Get Rating Summary

```http
GET /api/ratings/products/{productId}/summary
```

**Response (200 OK):**
```json
{
  "productId": "11111111-1111-4111-8111-111111111111",
  "averageRating": 4.6,
  "totalRatings": 15,
  "totalReviews": 12,
  "ratingDistribution": {
    "1": 0,
    "2": 1,
    "3": 2,
    "4": 5,
    "5": 7
  }
}
```

#### Get Rating by ID

```http
GET /api/ratings/{ratingId}
```

**Response (200 OK):** RatingResponse (includes `userId`)

#### Create Rating

```http
POST /api/ratings/products/{productId}
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "ratingValue": 5,
  "reviewTitle": "Excellent keyboard!",
  "reviewText": "Great typing experience, highly recommend."
}
```

**Fields:** `ratingValue` (required, 1–5), `reviewTitle` (at most 200 characters), `reviewText` (at most 5000). These are the only request fields.
**Rules:** the caller must have bought the product; one review per user per product (unique constraint, Flyway `V3__rating_one_review_per_user`).

**Response (201 CREATED):** RatingResponse

**Errors:**
- 400 — validation failed
- 401 — not authenticated
- 403 `REVIEW_NOT_ELIGIBLE` — the caller has not bought this product
- 409 `REVIEW_ALREADY_EXISTS` — the caller already reviewed this product

**Side Effect:** Publishes `RatingEvent(RATING_ADDED)` to `rating.events` Kafka topic.

#### Update Rating

```http
PUT /api/ratings/{ratingId}
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "ratingValue": 4,
  "reviewTitle": "Very good keyboard",
  "reviewText": "Changed my mind slightly after more use.",
  "version": 1
}
```

**Authorization:** Author of the rating OR `ADMIN` role

**Response (200 OK):** Updated RatingResponse

**Errors:**
- 400 — validation failed
- 403 — not the author and not admin
- 404 — rating not found
- 409 — version mismatch

**Side Effect:** Publishes `RatingEvent(RATING_UPDATED)` to Kafka.

#### Delete Rating

```http
DELETE /api/ratings/{ratingId}
Authorization: Bearer <jwt>
```

**Authorization:** Author of the rating OR `ADMIN` role

**Response (204 No Content)**

**Errors:**
- 403 — not the author and not admin
- 404 — rating not found

**Side Effect:** Publishes `RatingEvent(RATING_DELETED)` to Kafka. Associated votes are cascaded-deleted.

#### Add Helpful/Unhelpful Vote

```http
POST /api/ratings/{ratingId}/votes/{voteType}
Authorization: Bearer <jwt>
```

**Path Parameters:**
- `voteType` — `HELPFUL` or `UNHELPFUL`

**Response (204 No Content)**

**Constraints:**
- One vote per user per rating (UNIQUE(rating_id, user_id))
- If user changes vote (HELPFUL → UNHELPFUL), the old vote is deleted and new one inserted

**Errors:**
- 400 — invalid voteType
- 401 — not authenticated
- 404 — rating not found

---

## Customer services: user, cart, order

Introduced in [Phase 5](../phases/phase-05-customer-accounts.md). All endpoints need a token (any
role) and act on the JWT `sub`. The tables below are the Phase 5 core. Membership, referrals,
lists, alerts, subscriptions, returns, invoices and the rest are listed in [endpoints.md](endpoints.md).

| Service | Port | Base paths | Database |
|---|---|---|---|
| user-service | 8085 | `/api/users` | `user_db` |
| cart-service | 8086 | `/api/cart`, `/api/wishlist` | `cart_db` |
| order-service | 8087 | `/api/orders` | `order_db` |

### User service

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/api/users/me` | | `UserProfileResponse {userId, email, displayName, phone, createdAt}` (created on first call) |
| PUT | `/api/users/me` | `{displayName, phone}` | profile |
| GET | `/api/users/me/addresses` | | `AddressResponse[]`, default first |
| POST | `/api/users/me/addresses` | `AddressRequest` | 201 `AddressResponse` (the first address becomes the default; at most 20, else 409 `ADDRESS_LIMIT`) |
| GET | `/api/users/me/addresses/{id}` | | `AddressResponse` |
| PUT | `/api/users/me/addresses/{id}` | `AddressRequest` (full replace) | `AddressResponse` |
| DELETE | `/api/users/me/addresses/{id}` | | 204. If it was the default, the most recently created remaining address becomes the default. |
| PUT | `/api/users/me/addresses/{id}/default` | | `AddressResponse` |

`AddressRequest`: `{label?, fullName, phone?, line1, line2?, city, state?, postalCode, country (ISO-2)}`.
`AddressResponse` adds `id`, `isDefault`, `createdAt`, `updatedAt`.

### Cart service

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/api/cart` | | `CartResponse {items, currency\|null, itemCount, subtotal}`; `itemCount` is the total quantity |
| POST | `/api/cart/items` | `{productId, quantity, name, unitPrice, currency, imageUrl?}` | `CartResponse`; quantity is added and capped at 99 |
| PUT | `/api/cart/items/{productId}` | `{quantity}` 1–99 | `CartResponse`; 404 if the item is not in the cart |
| DELETE | `/api/cart/items/{productId}` | | 204 (idempotent) |
| DELETE | `/api/cart` | | 204 |
| POST | `/api/cart/merge` | `{items: [...]}`, at most 100 | `CartResponse`; quantities summed and capped at 99; all or nothing |
| GET | `/api/wishlist` | | `WishlistItemResponse[] {productId, name, price, currency, imageUrl, addedAt}` |
| PUT | `/api/wishlist/{productId}` | `{name, price, currency, imageUrl?}` | `WishlistItemResponse` (idempotent) |
| DELETE | `/api/wishlist/{productId}` | | 204 |
| POST | `/api/wishlist/merge` | `{items: [...]}`, at most 200 | `WishlistItemResponse[]` (union) |

A cart holds one currency; adding an item in another currency returns 409 `CURRENCY_MISMATCH`.
Names and prices in the cart are display copies only: checkout re-prices every line.

### Order service: place, list, view, cancel

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/orders` | Header `Idempotency-Key` (required). Body below. 201 for a new order, 200 when the same key and body are replayed. |
| GET | `/api/orders?page&size` | Newest first, at most 50 per page; returns summaries |
| GET | `/api/orders/{id}` | 404 if the order belongs to someone else |
| POST | `/api/orders/{id}/cancel` | See cancel rules below |

Place-order body (`PlaceOrderRequest`):

| Field | Required | Meaning |
|---|---|---|
| `addressId` | yes | One of the caller's addresses (404 `ADDRESS_NOT_FOUND` otherwise) |
| `paymentMethod` | yes | `CARD`, `COD` or `WALLET` |
| `items` | no | 1–50 lines for Buy Now; when omitted the server cart is used (409 `CART_EMPTY` if it is empty) |
| `couponCode` | no | At most 40 characters; validated by promotion-service |
| `useWallet` | no | Apply wallet balance first |
| `coinsToRedeem` | no | Reward coins to spend (at most 20% of the subtotal) |
| `exchangeQuoteId` | no | A trade-in quote from promotion-service (Phase 10) |

Pricing: each line is re-read from product-service (sale price when above 0, else list price; the
buy-box offer when the product has several sellers), then coupon, coins, wallet, shipping and GST are
applied. Placement reserves stock and runs the risk score: a score of 70 or more rejects COD,
90 or more holds the order.

**Cancel rules.** A user (or an admin, via `/api/admin/orders/{id}/cancel`) can cancel only while the
order and all of its shipments are still cancellable, which ends at `PACKED`; later states return 409
`ORDER_NOT_CANCELLABLE`. An already-cancelled order returns 200 unchanged. On cancel:
a captured payment is refunded, then stock is restored and the coupon, coins and wallet amount are
released. The release steps are best-effort, so a failure is logged and not retried
([TD-P6-01](../techdebt/phase6.md#td-p6-01)).
