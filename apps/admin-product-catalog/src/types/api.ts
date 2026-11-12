/**
 * Wire types for the platform API, mirroring the published Java contracts:
 *   - services/auth-service/auth-service-api  (TokenResponse, UserResponse, AuthErrorCode)
 *   - services/product-service/product-service-api (ProductResponse, CreateProductRequest, ...)
 *   - services/inventory-service/inventory-service-api (InventoryResponse, ...)
 *   - libs/common-core ProblemPayload (RFC 9457)
 *
 * These are the ONLY shapes that cross the network boundary. Nothing else in the
 * app should describe an API payload inline.
 */

/** RFC 9457 problem document. Every error the platform emits has this shape. */
export interface ProblemDocument {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  /** Stable machine-readable code, e.g. AUTH_INVALID_CREDENTIALS. */
  readonly code?: string;
  readonly correlationId?: string;
  readonly timestamp?: string;
  /** Problem documents may carry extension members (resourceType, etc.). */
  readonly [extension: string]: unknown;
}

/**
 * `authMethod` is serialised as the Java enum constant name (the enum has no
 * `@JsonValue`), so the wire form is upper-case even though the JWT `auth_method`
 * claim is lower-case.
 */
export type AuthMethod = 'PASSWORD' | 'GOOGLE';

/**
 * Result of login / refresh.
 *
 * The refresh token is deliberately absent: it only ever arrives as an HttpOnly
 * cookie. If a field for it ever appears here, that is a defect in the backend,
 * not a feature to consume.
 */
export interface TokenResponse {
  readonly accessToken: string;
  readonly tokenType: string;
  readonly expiresIn: number;
  readonly authMethod: AuthMethod;
}

/** `GET /api/auth/me` and the `201` body of `POST /api/auth/register`. */
export interface UserResponse {
  readonly id: string;
  readonly email: string;
  readonly emailVerified: boolean;
  readonly displayName: string;
  readonly status: string;
  /** Unprefixed role names (`VIEWER`, not `ROLE_VIEWER`). */
  readonly roles: readonly string[];
  readonly createdAt: string;
}

/**
 * One product image, mirroring `ProductImageResponse(UUID id, String url, int
 * displayOrder)` / the request-side `ProductImageRequest(String url, int
 * displayOrder)` (no `id` — the backend assigns it).
 */
export interface ProductImage {
  readonly id: string;
  readonly url: string;
  readonly displayOrder: number;
}

/** Body shape for one row of `CreateProductRequest.images` / `UpdateProductRequest.images`. */
export interface ProductImageRequest {
  readonly url: string;
  readonly displayOrder: number;
}

/**
 * A catalog product.
 *
 * `priceAmount` is a Java `BigDecimal`. Jackson emits it as a JSON number by
 * default but as a string when `WRITE_BIGDECIMAL_AS_PLAIN`/`ToStringSerializer`
 * is configured, so the client accepts both rather than assuming and silently
 * rendering `NaN` if the backend's serialisation config ever changes.
 */
export interface Product {
  readonly id: string;
  readonly sku: string;
  readonly name: string;
  readonly priceAmount: number | string;
  readonly priceCurrency: string;
  readonly active: boolean;
  /**
   * Optimistic-locking version. Must be echoed back unchanged (as read) in the
   * body of `PUT /api/products/{id}`; a stale value is rejected with a `409`.
   */
  readonly version: number;
  /** Nullable: no category-picker UI exists yet (there is no listing endpoint). */
  readonly categoryId: string | null;
  readonly sellerName: string | null;
  /** Arbitrary attribute bag; Jackson serialises `Map<String,Object>` as a JSON object. */
  readonly specifications: Record<string, unknown> | null;
  /**
   * Populated on `GET /api/products/{id}`, but always `[]` on the paged
   * `GET /api/products` list (the backend deliberately skips the join there to
   * avoid N+1). An empty array from the list endpoint therefore does NOT mean
   * "this product has no images" — only a fetch of the single-product endpoint
   * is authoritative for that.
   */
  readonly images: readonly ProductImage[];
  /**
   * Denormalised/advisory stock hint kept in product-service via the event
   * stream — may be `null` forever if nothing has provisioned inventory yet.
   * Display as a soft hint only; `Inventory.quantity` is the source of truth.
   */
  readonly lastKnownQuantity: number | null;
  /** Phase 7. Optional: older responses / list endpoint may omit them. */
  readonly brand?: string | null;
  readonly attributes?: Readonly<Record<string, string>> | null;
  /** Only authoritative on `GET /api/products/{id}`. */
  readonly variants?: readonly ProductVariant[];
}

/** Phase 7 variant as returned by product-service. */
export interface ProductVariant {
  readonly id: string;
  readonly sku: string;
  readonly attributes: Readonly<Record<string, string>>;
  readonly priceAmount: number | null;
  readonly salePrice: number | null;
  readonly imageUrl: string | null;
  readonly active: boolean;
  readonly sortOrder?: number;
  readonly availableQuantity?: number | null;
}

/** One row of `PUT /api/products/{id}/variants` (full replace). */
export interface ProductVariantRequest {
  readonly id?: string;
  readonly sku: string;
  readonly attributes: Readonly<Record<string, string>>;
  readonly priceAmount?: number | null;
  readonly salePrice?: number | null;
  readonly imageUrl?: string | null;
  readonly active: boolean;
  readonly sortOrder: number;
}

/** The `page` member of Spring's `PagedModel` envelope. */
export interface PageMetadata {
  readonly size: number;
  readonly number: number;
  readonly totalElements: number;
  readonly totalPages: number;
}

/** `PagedModel<T>` — `{ content: [...], page: { ... } }`. */
export interface PagedModel<T> {
  readonly content: readonly T[];
  readonly page: PageMetadata;
}

/** Body of `POST /api/auth/login`. */
export interface LoginRequest {
  readonly email: string;
  readonly password: string;
}

/** Body of `POST /api/auth/register`. */
export interface RegisterRequest {
  readonly email: string;
  readonly password: string;
  readonly displayName: string;
}

/**
 * Body of `POST /api/products`.
 *
 * `initialQuantity` is THE saga entry point: the backend's `@AssertTrue`
 * rejects the request with a `400` unless `!active || initialQuantity >= 1`.
 * `categoryId`/`sellerName`/`specifications`/`images` are all optional.
 */
export interface CreateProductRequest {
  readonly sku: string;
  readonly name: string;
  readonly priceAmount: number | string;
  readonly priceCurrency: string;
  readonly active: boolean;
  readonly initialQuantity?: number | null;
  readonly categoryId?: string | null;
  readonly sellerName?: string | null;
  readonly specifications?: Record<string, unknown> | null;
  readonly images?: readonly ProductImageRequest[];
  readonly brand?: string | null;
  readonly attributes?: Readonly<Record<string, string>> | null;
}

/**
 * Body of `PUT /api/products/{id}`.
 *
 * No `sku`: it is immutable once a product exists. No `initialQuantity`
 * either — that field only exists at creation time. `version` must be the
 * value last read for this product — the backend rejects a stale value with
 * a `409`. `images`, when present, REPLACES the product's images wholesale
 * (not a diff/merge) — a caller must send the complete desired list, not just
 * additions.
 */
export interface UpdateProductRequest {
  readonly name: string;
  readonly priceAmount: number | string;
  readonly priceCurrency: string;
  readonly active: boolean;
  readonly version: number;
  readonly categoryId?: string | null;
  readonly sellerName?: string | null;
  readonly specifications?: Record<string, unknown> | null;
  readonly images?: readonly ProductImageRequest[];
  readonly brand?: string | null;
  readonly attributes?: Readonly<Record<string, string>> | null;
}

/**
 * A stock-level row, mirroring `inventory-service-api`'s `InventoryResponse
 * (UUID id, UUID productId, int quantity, Long version, String warehouseCode,
 * String productName, String productSku, int lowStockThreshold)`.
 *
 * No longer shallow: `productName`/`productSku` are denormalised into
 * inventory-service via Kafka event consumption (product-service is still the
 * system of record; inventory-service has no synchronous dependency on it).
 * Both are nullable until the relevant product event has been processed at
 * least once for this row — fall back to `productId` when they're `null`
 * rather than treating that as an error state. `warehouseCode` is nullable
 * (never set). `lowStockThreshold` always has a value (defaults to 10
 * server-side).
 */
export interface Inventory {
  readonly id: string;
  readonly productId: string;
  readonly quantity: number;
  readonly version: number;
  readonly warehouseCode: string | null;
  readonly productName: string | null;
  readonly productSku: string | null;
  readonly lowStockThreshold: number;
}

/**
 * Body of `PUT /api/inventory/{id}`.
 *
 * `version` must be the value last read for this row — the backend rejects a
 * stale value with a `409`, the same optimistic-locking contract as products.
 * Full-replace semantics apply to `warehouseCode`/`lowStockThreshold` exactly
 * as they already do to `quantity`: omitting `warehouseCode` clears it, and
 * omitting `lowStockThreshold` resets it to the server default (10) — this is
 * NOT a partial patch, so a caller editing one field must still send the
 * row's current values for the others.
 */
export interface UpdateInventoryRequest {
  readonly quantity: number;
  readonly version: number;
  readonly warehouseCode?: string | null;
  readonly lowStockThreshold?: number | null;
}

/**
 * Body of `POST /api/inventory`.
 *
 * Manual provisioning for ops-correction cases — the normal path is
 * event-driven (a `PRODUCT_CREATED` event provisions a zero-quantity row
 * automatically). The backend rejects a `productId` that already has a row
 * with a `409` (the same `UNIQUE(product_id)` constraint the event consumer
 * relies on for its own idempotency). `warehouseCode` is optional (max 32
 * chars server-side). `lowStockThreshold` is optional and `null` means "use
 * the default 10" — that is NOT the same as an explicit `0`, so the client
 * must not default this field to `10` itself.
 */
export interface CreateInventoryRequest {
  readonly productId: string;
  readonly quantity: number;
  readonly warehouseCode?: string | null;
  readonly lowStockThreshold?: number | null;
}
