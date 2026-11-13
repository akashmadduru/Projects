# Backend API Reference

**Status:** Complete (Phases 1–4 slice)  
**Last Updated:** November 2026

## Table of Contents

1. [Overview](#overview)
2. [Authentication & Authorization](#authentication--authorization)
3. [Error Handling](#error-handling)
4. [Product Service API](#product-service-api)
5. [Inventory Service API](#inventory-service-api)
6. [Rating Service API](#rating-service-api)
7. [Rate Limiting](#rate-limiting)
8. [Pagination](#pagination)
9. [Customer Services API (Phase 5)](#customer-services-api-phase-5)

---

## Overview

The platform exposes three independent service APIs, each with its own port and database. All APIs follow REST conventions, return JSON, and use RFC 9457 problem documents for errors.

| Service | Port | Base Path | Database |
|---------|------|-----------|----------|
| Product | 8082 | `/api/products` | `product_db` |
| Inventory | 8083 | `/api/inventory` | `inventory_db` |
| Rating | 8084 | `/api/ratings` | `rating_db` |

---

## Authentication & Authorization

### Token-Based (JWT)

All write endpoints and authenticated reads require an `Authorization: Bearer <jwt>` header.

The JWT is issued by auth-service and contains:
- `sub` — user UUID (principal)
- `scope` — comma-separated list of roles: `ROLE_VIEWER`, `ROLE_CATALOG_MANAGER`, `ROLE_ADMIN`
- `aud` — audience (must be `ecommerce-platform`)
- `iss` — issuer (must be `https://auth.acme-ecommerce.internal`)

### RBAC Rules

| Endpoint | Method | Required Role |
|----------|--------|----------------|
| Product list/detail | GET | None (public) |
| Product catalog aggregate | GET | None (public) |
| Product create | POST | `CATALOG_MANAGER`, `ADMIN` |
| Product update | PUT | `CATALOG_MANAGER`, `ADMIN` |
| Product delete | DELETE | `CATALOG_MANAGER`, `ADMIN` |
| Inventory list/detail | GET | None (authenticated) |
| Inventory by-product | GET | None (authenticated) |
| Inventory create | POST | `CATALOG_MANAGER`, `ADMIN` |
| Inventory update | PUT | `CATALOG_MANAGER`, `ADMIN` |
| Inventory delete | DELETE | `CATALOG_MANAGER`, `ADMIN` |
| Rating list/detail/summary | GET | None (public) |
| Rating create | POST | Authenticated (any role) |
| Rating update | PUT | Author or `ADMIN` |
| Rating delete | DELETE | Author or `ADMIN` |
| Rating vote | POST | Authenticated (any role) |

---

## Error Handling

### RFC 9457 Problem Document

All errors return a JSON problem document as specified in RFC 9457:

```json
{
  "type": "https://errors.acme-ecommerce.internal/validation-failed",
  "status": 400,
  "title": "Validation failed",
  "detail": "Product name cannot be empty",
  "instance": "/api/products",
  "code": "VALIDATION_FAILED"
}
```

**Standard Error Codes:**

| HTTP Status | Code | Meaning |
|-------------|------|---------|
| 400 | VALIDATION_FAILED | Request body/parameters failed validation |
| 401 | UNAUTHENTICATED | No token or invalid token |
| 403 | FORBIDDEN | Caller lacks required role |
| 404 | RESOURCE_NOT_FOUND | Resource does not exist |
| 409 | RESOURCE_CONFLICT | Unique constraint, optimistic lock, or duplicate |
| 422 | DOMAIN_RULE_VIOLATION | Domain rule rejected the request |
| 503 | UPSTREAM_UNAVAILABLE | Downstream service unreachable |
| 500 | INTERNAL_ERROR | Unhandled exception (details in logs) |

### Example Error Responses

**Validation Error (400):**
```json
{
  "type": "https://errors.acme-ecommerce.internal/validation-failed",
  "status": 400,
  "title": "Validation failed",
  "detail": "Price amount must be non-negative",
  "code": "VALIDATION_FAILED"
}
```

**Version Conflict (409):**
```json
{
  "type": "https://errors.acme-ecommerce.internal/resource-conflict",
  "status": 409,
  "title": "Resource conflict",
  "detail": "Product ... has version 5, not 3",
  "code": "RESOURCE_CONFLICT"
}
```

**Not Found (404):**
```json
{
  "type": "https://errors.acme-ecommerce.internal/resource-not-found",
  "status": 404,
  "title": "Resource not found",
  "detail": "Product not found",
  "code": "RESOURCE_NOT_FOUND"
}
```

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

**Side Effect:** Publishes `InventoryEvent(INVENTORY_QUANTITY_CHANGED)` to Kafka (if applicable).

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
  "reviewText": "Great typing experience, highly recommend.",
  "isVerifiedPurchase": false
}
```

**Required:** `ratingValue` (1–5)  
**Constraints:**
- `ratingValue` must be 1–5 inclusive
- One rating per user per product (enforced by service layer)

**Response (201 CREATED):** RatingResponse

**Errors:**
- 400 — validation failed
- 401 — not authenticated
- 409 — user already has a rating for this product (if enforced)

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

## Rate Limiting

Currently **not implemented**. The platform relies on:
- Gateway circuit breakers (1s slow-call threshold per route)
- Database connection pool limits (10 connections per service)
- Kafka consumer lag monitoring (no explicit backpressure)

Future implementation should add per-user or per-IP rate limiting on write endpoints.

---

## Pagination

All list endpoints return paginated results with the following structure:

```json
{
  "content": [ /* array of resource objects */ ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 100,
    "totalPages": 5
  }
}
```

**Query Parameters:**
- `page` — zero-indexed page number (default: 0)
- `size` — rows per page (default: 20, max: 100 to prevent table scans)
- `sort` — sort specification, e.g., `sku,asc` or `createdAt,desc` (default: `sku,asc` for products, `productId,asc` for inventory, `createdAt,desc` for ratings)

**Example:**
```http
GET /api/products?page=2&size=50&sort=name,desc
```

---

## Customer Services API (Phase 5)

Added with customer accounts; see [Phase5-Feature.md](Phase5-Feature.md). All endpoints go through the gateway (8080), require `Authorization: Bearer <jwt>` (any role), and use the JWT `sub` as the user. A resource owned by another user returns 404. Errors are RFC 9457 problem documents.

| Service | Port | Base Path | Database |
|---------|------|-----------|----------|
| User | 8085 | `/api/users` | `user_db` |
| Cart | 8086 | `/api/cart`, `/api/wishlist` | `cart_db` |
| Order | 8087 | `/api/orders` | `order_db` |

### Additional error codes

| HTTP | Code | When |
|------|------|------|
| 404 | ADDRESS_NOT_FOUND | Address missing or not owned (checkout) |
| 409 | ADDRESS_LIMIT | More than 20 addresses |
| 409 | CART_EMPTY | Checkout with an empty cart |
| 409 | PRODUCT_UNAVAILABLE | Product inactive, unpriced or gone |
| 409 | CURRENCY_MISMATCH | Mixed currencies in a cart/order |
| 422 | DOMAIN_RULE_VIOLATION | Idempotency-Key reused with a different `addressId` (rule `IDEMPOTENCY_KEY_REUSED`), or more than 50 order lines |
| 400 | VALIDATION_FAILED | Includes a missing or over-long `Idempotency-Key` |
| 503 | DEPENDENCY_UNAVAILABLE | cart/user/product service failed during checkout |

5xx problem details are generic; specifics are in service logs. Gateway returns 503 for up to ~30-40s after a downstream restart.

### User Service

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/api/users/me` | | `UserProfileResponse {userId, email, displayName, phone, createdAt}` (created on first call) |
| PUT | `/api/users/me` | `{displayName, phone}` | profile |
| GET | `/api/users/me/addresses` | | `AddressResponse[]`, default first |
| POST | `/api/users/me/addresses` | `AddressRequest` | 201 `AddressResponse` (first address becomes default; max 20) |
| GET | `/api/users/me/addresses/{id}` | | `AddressResponse` |
| PUT | `/api/users/me/addresses/{id}` | `AddressRequest` (full replace) | `AddressResponse` |
| DELETE | `/api/users/me/addresses/{id}` | | 204; most recent remaining address becomes default if the default was deleted |
| PUT | `/api/users/me/addresses/{id}/default` | | `AddressResponse` |

`AddressRequest`: `{label?, fullName, phone?, line1, line2?, city, state?, postalCode, country (ISO-2)}`. `AddressResponse` adds `id`, `isDefault`, `createdAt`, `updatedAt`.

### Cart Service

| Method | Path | Body | Response |
|--------|------|------|----------|
| GET | `/api/cart` | | `CartResponse {items, currency\|null, itemCount, subtotal}`; `itemCount` is total quantity |
| POST | `/api/cart/items` | `{productId, quantity, name, unitPrice, currency, imageUrl?}` | `CartResponse` (adds quantity, capped at 99) |
| PUT | `/api/cart/items/{productId}` | `{quantity}` 1..99 | `CartResponse`; 404 if absent |
| DELETE | `/api/cart/items/{productId}` | | 204 (idempotent) |
| DELETE | `/api/cart` | | 204 |
| POST | `/api/cart/merge` | `{items: [...]}` max 100 | `CartResponse` (quantities summed, capped at 99, all-or-nothing) |
| GET | `/api/wishlist` | | `WishlistItemResponse[] {productId, name, price, currency, imageUrl, addedAt}` |
| PUT | `/api/wishlist/{productId}` | `{name, price, currency, imageUrl?}` | `WishlistItemResponse` (idempotent) |
| DELETE | `/api/wishlist/{productId}` | | 204 |
| POST | `/api/wishlist/merge` | `{items: [...]}` max 200 | `WishlistItemResponse[]` (union) |

A cart holds one currency; adding another returns 409 `CURRENCY_MISMATCH`. `CartItemResponse`: `{productId, quantity, name, unitPrice, currency, imageUrl, lineTotal, addedAt}`. Cart names and prices are display snapshots only.

### Order Service

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/orders` | Body `{addressId}`; header `Idempotency-Key` required (max length enforced). 201 new order; 200 original order when the same key and address are replayed. |
| GET | `/api/orders?page&size` | Max size 50, newest first; paged summaries |
| GET | `/api/orders/{id}` | 404 if not owned |
| POST | `/api/orders/{id}/cancel` | PLACED to CANCELLED; already CANCELLED returns 200. No stock release or refund yet. |

`OrderResponse`: `{id, status, currency, totalAmount, placedAt, cancelledAt, shipTo{fullName, phone, line1, line2, city, state, postalCode, country}, items[{productId, sku, name, unitPrice, quantity, lineTotal, imageUrl}]}`. `OrderSummaryResponse`: `{id, status, currency, totalAmount, placedAt, itemCount}`.

Unit price is re-read from product-service at checkout: `salePrice` when positive, else `priceAmount`. Orders snapshot address and prices. Limitations: no payment, inventory reservation, shipping or tax ([techdebt/backend.md](../techdebt/backend.md)).

---

## References

- [Backend-Architecture.md](Backend-Architecture.md) — System design and service responsibilities
- [Backend-EventSystem.md](Backend-EventSystem.md) — Event types and choreography
- Error codes are documented in [`libs/common-core/src/main/java/com/acme/ecommerce/common/core/error/ErrorCode.java`](../libs/common-core/src/main/java/com/acme/ecommerce/common/core/error/ErrorCode.java)
