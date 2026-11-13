# 10. API Contract Matrix

## Endpoint Reference Table

### Product Endpoints

| Endpoint | Method | Query Params | Request Body | Response Type | Status Codes | Auth Required | Notes |
|----------|--------|--------------|--------------|---------------|--------------|---------------|-------|
| `/api/products` | GET | `page=0` (0-indexed)<br/>`size=20` (default) | N/A | `PagedModel<Product>` | 200, 400, 401, 500 | Yes | Paginated list of all products.<br/>Images array is always `[]`<br/>(use GET /api/products/{id} for images).<br/>Stock hint (`lastKnownQuantity`)<br/>denormalized from inventory-service. |
| `/api/products/{id}` | GET | N/A | N/A | `Product` | 200, 401, 404, 500 | Yes | Single product by ID.<br/>Includes full `images[]` array.<br/>Use this endpoint to fetch<br/>product detail with all images. |
| `/api/products` | POST | N/A | `CreateProductRequest` | `Product` | 201, 400, 401, 409, 422, 500 | Yes (CATALOG_MANAGER or ADMIN) | Create new product.<br/>`sku` must be unique (409 if duplicate).<br/>If `active=true`, requires `initialQuantity >= 1`.<br/>Publishes `ProductCreated` event<br/>(inventory-service auto-provisions<br/>zero-quantity row). |
| `/api/products/{id}` | PUT | N/A | `UpdateProductRequest` | `Product` | 200, 400, 401, 403, 404, 409, 422, 500 | Yes (CATALOG_MANAGER or ADMIN) | Update existing product.<br/>`version` must match current<br/>(optimistic locking, 409 if stale).<br/>REPLACE semantics: omitted fields<br/>from request are cleared (not merged).<br/>Cannot update `sku` (immutable). |
| `/api/products/{id}` | DELETE | N/A | N/A | 204 (no content) | 204, 401, 403, 404, 500 | Yes (CATALOG_MANAGER or ADMIN) | Soft-delete product (sets `active=false`).<br/>Hard-delete is not supported<br/>for audit trail integrity. |

### Inventory Endpoints

| Endpoint | Method | Query Params | Request Body | Response Type | Status Codes | Auth Required | Notes |
|----------|--------|--------------|--------------|---------------|--------------|---------------|-------|
| `/api/inventory` | GET | `page=0`<br/>`size=20` | N/A | `PagedModel<Inventory>` | 200, 400, 401, 500 | Yes | Paginated stock levels.<br/>Both `productName` and `productSku`<br/>are denormalized (nullable until<br/>first product event is consumed). |
| `/api/inventory/{id}` | GET | N/A | N/A | `Inventory` | 200, 401, 404, 500 | Yes | Single stock row by inventory ID<br/>(not product ID). |
| `/api/inventory` | POST | N/A | `CreateInventoryRequest` | `Inventory` | 201, 400, 401, 409, 500 | Yes (INVENTORY_MANAGER or ADMIN) | Manually provision stock row<br/>for a product.<br/>`productId` must not already have<br/>a stock row (409 if duplicate).<br/>Normal path is event-driven<br/>(PRODUCT_CREATED event auto-provisions). |
| `/api/inventory/{id}` | PUT | N/A | `UpdateInventoryRequest` | `Inventory` | 200, 400, 401, 403, 404, 409, 422, 500 | Yes (INVENTORY_MANAGER or ADMIN) | Update stock level, warehouse, threshold.<br/>`version` must match current (optimistic locking).<br/>REPLACE semantics: omitting `warehouseCode`<br/>clears it; omitting `lowStockThreshold`<br/>resets to server default (10). |

### Authentication Endpoints

| Endpoint | Method | Query Params | Request Body | Response Type | Status Codes | Auth Required | Notes |
|----------|--------|--------------|--------------|---------------|--------------|---------------|-------|
| `/api/auth/login` | POST | N/A | `LoginRequest`<br/>(`email`, `password`) | `TokenResponse` | 200, 400, 401, 429, 500 | No | Authenticate user.<br/>Response includes `accessToken`<br/>(valid 15 min).<br/>Also sets HttpOnly refresh token cookie.<br/>401 if credentials invalid.<br/>429 if too many failed attempts.<br/>Response body does NOT include<br/>refresh token (it's cookie-only). |
| `/api/auth/register` | POST | N/A | `RegisterRequest`<br/>(`email`, `password`,<br/>`displayName`) | `UserResponse` | 201, 400, 409, 422, 500 | No | Create new account.<br/>Does NOT return access token<br/>(user must login separately).<br/>409 if email already registered.<br/>422 if password fails strength validation. |
| `/api/auth/refresh` | POST | N/A | N/A | `TokenResponse` | 200, 401, 500 | No (refresh cookie only) | Refresh access token using<br/>HttpOnly refresh cookie.<br/>Cookie must be valid and not<br/>already rotated.<br/>Response includes new `accessToken`<br/>and rotates the refresh cookie. |
| `/api/auth/me` | GET | N/A | N/A | `UserResponse` | 200, 401, 500 | Yes | Current authenticated user profile.<br/>Fetch after login or refresh<br/>to populate displayName, roles, etc. |
| `/api/auth/logout` | POST | N/A | N/A | 204 (no content) | 204, 401, 500 | Yes (or no, idempotent) | Sign out user.<br/>Clears refresh cookie server-side.<br/>Client clears memory-only access token.<br/>Idempotent (doesn't error if not logged in). |
| `/oauth2/authorization/google` | GET | `redirect_uri` (optional) | N/A | 302 Redirect to Google | 302, 500 | No | Start Google OAuth2 flow.<br/>Browser does full-page navigation.<br/>Google issues code, Auth Service<br/>exchanges for token, sets refresh cookie,<br/>redirects to `/auth/callback`. |

### Error Responses (RFC 9457)

All endpoints return standardized error responses:

```json
{
  "type": "https://catalog.example.com/problems/out-of-stock",
  "title": "Product Out of Stock",
  "status": 409,
  "detail": "SKU ABC123 is no longer available.",
  "instance": "/api/products/abc-id-123",
  "code": "PRODUCT_OUT_OF_STOCK",
  "correlationId": "req-67890-abcdef",
  "timestamp": "2025-11-08T14:30:00Z"
}
```

| Status | Code | Meaning | Typical Cause | Client Action |
|--------|------|---------|--------------|---------------|
| 400 | INVALID_REQUEST | Malformed request | Bad JSON, missing field | Show validation error, retry after fix |
| 401 | AUTH_TOKEN_INVALID | Access token expired/invalid | Stale token, tampered JWT | Auto-refresh, retry; if fails, redirect to login |
| 401 | AUTH_REFRESH_INVALID | Refresh token expired | Cookie older than 7 days | Redirect to login, ask user to sign in again |
| 401 | AUTH_REFRESH_REUSE_DETECTED | Token family revoked (reuse attack) | Concurrent refresh on stolen cookie | Redirect to login, notify user of suspicious activity |
| 401 | UNAUTHENTICATED | No token provided | Request missing Authorization header | Redirect to login |
| 403 | FORBIDDEN | User lacks required role | User not CATALOG_MANAGER, ADMIN, etc. | Show "Access Denied" message |
| 404 | RESOURCE_NOT_FOUND | Product/inventory not found | ID doesn't exist or was deleted | Show 404 page or empty state |
| 409 | RESOURCE_CONFLICT | Optimistic lock violation | Another user updated the product | Show "Item changed by someone else" message, reload |
| 422 | INVALID_INPUT | Validation failed | Product name too long, invalid price | Show form validation errors |
| 429 | RATE_LIMITED | Too many requests | User exceeded rate limit | Show "Try again later" message |
| 500 | INTERNAL_ERROR | Server error | Unhandled exception in backend | Show "Service unavailable" message, show correlationId for support |
| 503 | UPSTREAM_UNAVAILABLE | Downstream service down | Product Service, Inventory Service unavailable | Show "Service temporarily unavailable" message |

### Request/Response Type Definitions

#### CreateProductRequest
```typescript
{
  sku: string;                    // Required, unique
  name: string;                   // Required
  priceAmount: number | string;   // Required, BigDecimal on server
  priceCurrency: string;          // Required (e.g., "USD")
  active: boolean;                // Required
  initialQuantity?: number;       // Optional; required if active=true
  categoryId?: string | null;     // Optional category reference
  sellerName?: string | null;     // Optional seller name
  specifications?: Record<string, unknown> | null;  // Optional key-value pairs
  images?: Array<{ url: string; displayOrder: number }>;  // Optional
}
```

#### UpdateProductRequest
```typescript
{
  name: string;                   // Required
  priceAmount: number | string;   // Required
  priceCurrency: string;          // Required
  active: boolean;                // Required
  version: number;                // Required (optimistic lock)
  categoryId?: string | null;     // Optional (REPLACE, omit to clear)
  sellerName?: string | null;     // Optional (REPLACE, omit to clear)
  specifications?: Record<string, unknown> | null;  // Optional (REPLACE)
  images?: Array<{ url: string; displayOrder: number }>;  // Optional (replaces all)
}
```

#### Product Response
```typescript
{
  id: string;                     // UUID
  sku: string;
  name: string;
  priceAmount: number | string;   // May be BigDecimal (string) or number
  priceCurrency: string;
  active: boolean;
  version: number;                // Optimistic lock counter
  categoryId: string | null;
  sellerName: string | null;
  specifications: Record<string, unknown> | null;
  images: Array<{ id: string; url: string; displayOrder: number }>;  // [] on list endpoints
  lastKnownQuantity: number | null;  // Denormalized from inventory
}
```

#### CreateInventoryRequest
```typescript
{
  productId: string;              // Required, must not exist yet
  quantity: number;               // Required, >= 0
  warehouseCode?: string | null;  // Optional warehouse identifier
  lowStockThreshold?: number | null;  // Optional, default 10 if omitted
}
```

#### UpdateInventoryRequest
```typescript
{
  quantity: number;               // Required, >= 0
  version: number;                // Required (optimistic lock)
  warehouseCode?: string | null;  // Optional (REPLACE, omit to clear)
  lowStockThreshold?: number | null;  // Optional (REPLACE, omit to default 10)
}
```

#### LoginRequest
```typescript
{
  email: string;
  password: string;
}
```

#### TokenResponse
```typescript
{
  accessToken: string;            // JWT
  tokenType: string;              // "Bearer"
  expiresIn: number;              // Seconds (e.g., 900 for 15 min)
  authMethod: 'PASSWORD' | 'GOOGLE';
}
```

#### UserResponse
```typescript
{
  id: string;                     // UUID
  email: string;
  emailVerified: boolean;
  displayName: string;
  status: string;                 // "ACTIVE", "LOCKED", etc.
  roles: string[];                // ["CATALOG_MANAGER", "ADMIN", etc.]
  createdAt: string;              // ISO 8601 timestamp
}
```

### Query Parameter Conventions

**Pagination:**
- `page` — 0-indexed page number (default 0)
- `size` — items per page (default 20, max 100)

**Example:** `GET /api/products?page=2&size=50` → items 100-149

**Sorting (future):**
- `sort` — comma-separated fields with optional direction
- Example: `?sort=name,asc;createdAt,desc` (if implemented)

**Filtering (future):**
- `filter` — JSON-encoded filter conditions (if implemented)

### Rate Limiting

All endpoints are subject to:
- **Per-user limit:** 1000 requests / hour
- **Per-IP limit:** 10,000 requests / hour
- Response header: `X-RateLimit-Remaining: 999`
- When exceeded: 429 with `Retry-After` header

### Response Headers

All successful responses (2xx) include:
- `Content-Type: application/json; charset=UTF-8`
- `Cache-Control: private, no-cache, no-store, must-revalidate`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`

Authenticated endpoints also include:
- `X-Request-ID: <correlation-id>` (for logging)

### Cookie Handling

**HttpOnly Refresh Token Cookie**
```
Set-Cookie: refresh_token=<jwt>; 
  Path=/api/auth; 
  HttpOnly; 
  Secure; 
  SameSite=Strict; 
  Max-Age=604800
```

- Sent **only** to `/api/auth/*` endpoints
- **Not** sent to `/api/products/*` or `/api/inventory/*`
- Rotates on every use (new rotation issued on `/api/auth/refresh`)
- 7-day expiration (604,800 seconds)

### OAuth2 Google Flow

1. User clicks "Sign in with Google"
2. SPA navigates to `GET /oauth2/authorization/google`
3. Gateway redirects to Google's authorization endpoint
4. User authenticates with Google
5. Google redirects back to Auth Service's callback URL with authorization `code`
6. Auth Service exchanges code for ID token, creates account if needed, sets refresh cookie, redirects to SPA's `/auth/callback`
7. SPA calls `GET /api/auth/me` to fetch user profile
8. SPA is now logged in

### Versioning & Stability

- **No version in URL** (e.g., NOT `/api/v1/products`)
- Backward-incompatible changes come from a separate API path (e.g., `/api/v2/`)
- Current stable API has no suffix

### Response Envelope

Product service uses **flat envelope** (not wrapped in `data` key):
```json
// List endpoint
{
  "content": [...],
  "page": { "number": 0, "totalPages": 5 }
}

// Single resource
{
  "id": "...",
  "name": "..."
}

// Error
{
  "code": "...",
  "detail": "..."
}
```

**Not:**
```json
{
  "data": { "content": [...] },  // NOT this
  "error": null
}
```

This keeps JSON payloads lean and avoids extra nesting.

