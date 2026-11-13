# Backend Data Model

**Coverage:** product, inventory and rating schemas as of Phase 4. The platform now has 12 databases (`auth_db`, `product_db`, `inventory_db`, `rating_db`, `user_db`, `cart_db`, `order_db`, `payment_db`, `promotion_db`, `notification_db`, `seller_db`, `support_db`). Later schemas are defined by each service’s Flyway migrations under `src/main/resources/db/migration`.  
**Last Updated:** November 2026

## Table of Contents

1. [Database Overview](#database-overview)
2. [Product Service Schema](#product-service-schema)
3. [Inventory Service Schema](#inventory-service-schema)
4. [Rating Service Schema](#rating-service-schema)
5. [Denormalization Strategy](#denormalization-strategy)
6. [Indexes & Performance](#indexes--performance)
7. [Seed Data](#seed-data)

---

## Database Overview

The platform uses three independent Postgres databases, each owned by a service role. No foreign keys cross databases (impossible in Postgres); cross-database references are event-linked only.

| Database | Schema | Owner | Migrations |
|----------|--------|-------|------------|
| product_db | product | product_service | V1–V5 |
| inventory_db | inventory | inventory_service | V1–V3 |
| rating_db | rating | rating_service | V1–V2 |

---

## Product Service Schema

**Database:** `product_db`  
**Schema:** `product`  
**Migrations:** V1 (baseline), V2 (writes), V3 (catalog enrichment), V4 (amazon enrichment), V5 (seed data)

### products Table

The core catalog product entity.

```sql
CREATE TABLE product.products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sku varchar(64) NOT NULL UNIQUE,
    name varchar(200) NOT NULL,
    price_amount numeric(19, 2) NOT NULL CHECK (price_amount >= 0),
    price_currency char(3) NOT NULL CHECK (price_currency ~ '^[A-Z]{3}$'),
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    category_id uuid,
    seller_name varchar(200),
    specifications jsonb,
    last_known_quantity integer,
    description text,
    list_price numeric(19, 2),
    sale_price numeric(19, 2),
    discount_percent numeric(5, 2),
    thumbnail_url varchar(2048),
    video_urls jsonb,
    view_360_url varchar(2048),
    weight_grams integer,
    dimensions_length numeric(10, 2),
    dimensions_width numeric(10, 2),
    dimensions_height numeric(10, 2),
    color varchar(100),
    material varchar(100),
    average_rating numeric(3, 2),
    rating_count integer NOT NULL DEFAULT 0,
    review_count integer NOT NULL DEFAULT 0,
    rating_distribution jsonb,
    is_featured boolean NOT NULL DEFAULT false,
    seller_rating numeric(3, 2),
    is_seller_verified boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

**Key Columns:**
- `id` — UUID, generated, immutable
- `sku` — unique, natural key (external systems quote SKU, not UUID)
- `price_amount`, `price_currency` — separated; BigDecimal for exact money arithmetic
- `version` — optimistic concurrency; incremented on every update
- `category_id` — nullable FK to categories table (same database, real FK)
- `specifications` — JSONB, unstructured attributes (e.g., `{"processor": "Intel i7", "ram_gb": 16}`)
- `last_known_quantity` — nullable, denormalized from inventory service (non-authoritative)
- `average_rating`, `rating_count`, `review_count`, `rating_distribution` — denormalized from rating service
- `created_at`, `updated_at` — maintained by the service layer (not `@PrePersist`)

**Constraints:**
- PRIMARY KEY (id)
- UNIQUE (sku)
- CHECK (price_amount >= 0)
- CHECK (price_currency ~ '^[A-Z]{3}$')
- FOREIGN KEY (category_id) → categories(id)

**Indexes:**
- id (PRIMARY KEY)
- sku (UNIQUE constraint provides index)
- category_id (for joins; used in reads by category)
- is_featured (partial index on true values)
- average_rating (for sorting by rating)
- sale_price (for price range filtering)
- created_at (for temporal queries)

### categories Table

One level of product categorization.

```sql
CREATE TABLE product.categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(120) NOT NULL,
    parent_category_id uuid REFERENCES product.categories(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

**Key Columns:**
- `id` — UUID, generated
- `name` — category display name
- `parent_category_id` — nullable self-referencing FK (NULL for top-level categories)

**Constraints:**
- PRIMARY KEY (id)
- FOREIGN KEY (parent_category_id) → categories(id)

**Note:** Depth is enforced in the application layer, not via a CHECK constraint.

### product_images Table

Product image URLs, ordered per-product.

```sql
CREATE TABLE product.product_images (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES product.products(id) ON DELETE CASCADE,
    url varchar(2048) NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX product_images_product_id_idx ON product.product_images(product_id);
```

**Key Columns:**
- `id` — UUID, generated
- `product_id` — FK to products (cascade-deleted if product is deleted)
- `url` — image URL
- `display_order` — sort order within the product's images (0, 1, 2, ...)

**Constraints:**
- PRIMARY KEY (id)
- FOREIGN KEY (product_id) → products(id) ON DELETE CASCADE

---

## Inventory Service Schema

**Database:** `inventory_db`  
**Schema:** `inventory`  
**Migrations:** V1 (baseline), V2 (enrichment), V3 (amazon enrichment)

### stock_levels Table

One row per product, tracking quantity and status.

```sql
CREATE TABLE inventory.stock_levels (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL UNIQUE,
    quantity integer NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    warehouse_code varchar(32),
    product_name varchar(200),
    product_sku varchar(64),
    low_stock_threshold integer NOT NULL DEFAULT 10,
    reserved_quantity integer NOT NULL DEFAULT 0,
    status varchar(32),
    last_updated_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

**Key Columns:**
- `id` — UUID, generated
- `product_id` — unique (one row per product); no FK (product_db is separate database)
- `quantity` — total units on hand
- `reserved_quantity` — units allocated but not shipped
- `available_quantity` — computed as (quantity - reserved_quantity)
- `status` — IN_STOCK, LOW_STOCK, OUT_OF_STOCK (calculated/updated on writes)
- `low_stock_threshold` — quantity at/below which status is LOW_STOCK (default: 10)
- `warehouse_code` — nullable, forward-compatible single-warehouse label
- `product_name`, `product_sku` — denormalized from product service (updated on PRODUCT_UPDATED events)
- `version` — optimistic concurrency
- `created_at`, `updated_at` — timestamps

**Constraints:**
- PRIMARY KEY (id)
- UNIQUE (product_id) — idempotency guard for PRODUCT_CREATED event redelivery
- CHECK (quantity >= 0)

**Indexes:**
- product_id (UNIQUE constraint provides index)

**Why No FK to product_db:**
- Postgres cannot enforce FK across databases
- The unique constraint on product_id is the idempotency guard

---

## Rating Service Schema

**Database:** `rating_db`  
**Schema:** `rating`  
**Migrations:** V1 (baseline), V2 (seed data)

### ratings Table

Product ratings and reviews.

```sql
CREATE TABLE rating.ratings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL,
    user_id uuid NOT NULL,
    user_display_name varchar(200),
    rating_value integer NOT NULL CHECK (rating_value >= 1 AND rating_value <= 5),
    review_title varchar(200),
    review_text text,
    is_verified_purchase boolean NOT NULL DEFAULT false,
    helpful_count integer NOT NULL DEFAULT 0,
    unhelpful_count integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ratings_product_id_idx ON rating.ratings(product_id);
CREATE INDEX ratings_user_id_idx ON rating.ratings(user_id);
CREATE INDEX ratings_created_at_idx ON rating.ratings(created_at);
CREATE INDEX ratings_rating_value_idx ON rating.ratings(rating_value);
```

**Key Columns:**
- `id` — UUID, generated
- `product_id` — no FK (product_db is separate); used in list queries
- `user_id` — no FK (auth_db is separate); denormalization source: user_display_name
- `rating_value` — 1–5 scale (enforced by CHECK and domain logic)
- `review_title`, `review_text` — optional; a rating can exist without review text
- `is_verified_purchase` — placeholder for order-service integration
- `helpful_count`, `unhelpful_count` — aggregated vote counts
- `version` — optimistic concurrency
- `created_at`, `updated_at` — timestamps

**Constraints:**
- PRIMARY KEY (id)
- CHECK (rating_value >= 1 AND rating_value <= 5)

**Indexes:**
- product_id (queries: "get all ratings for product X")
- user_id (queries: "get user's ratings", future phase)
- created_at (queries: "recent ratings")
- rating_value (queries: "filter by star rating")

### rating_votes Table

Helpful/unhelpful votes on a rating.

```sql
CREATE TABLE rating.rating_votes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    rating_id uuid NOT NULL REFERENCES rating.ratings(id) ON DELETE CASCADE,
    user_id uuid NOT NULL,
    vote_type varchar(32) NOT NULL CHECK (vote_type IN ('HELPFUL', 'UNHELPFUL')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (rating_id, user_id)
);

CREATE INDEX rating_votes_rating_id_idx ON rating.rating_votes(rating_id);
CREATE INDEX rating_votes_user_id_idx ON rating.rating_votes(user_id);
```

**Key Columns:**
- `id` — UUID, generated
- `rating_id` — FK to ratings (cascade-deleted if rating is deleted)
- `user_id` — no FK; user who cast this vote
- `vote_type` — HELPFUL or UNHELPFUL
- `created_at` — when vote was cast

**Constraints:**
- PRIMARY KEY (id)
- UNIQUE (rating_id, user_id) — idempotency: one vote per user per rating
- CHECK (vote_type IN ('HELPFUL', 'UNHELPFUL'))
- FOREIGN KEY (rating_id) → ratings(id) ON DELETE CASCADE

**Behavior:** If a user changes their vote (HELPFUL → UNHELPFUL), the old row is deleted and a new one is inserted (thanks to the unique constraint).

---

## Denormalization Strategy

### Product ← Inventory

**Denormalized field:** `Product.last_known_quantity`  
**Source of truth:** `Inventory.quantity`  
**Consistency window:** Event-based (eventual; usually <1s in dev)  
**Update mechanism:** ProductEventListener consumes `INVENTORY_QUANTITY_CHANGED` event  
**Idempotency:** Field is overwritten with the same value; safe to replay

**Why denormalize?**
- Aggregate read endpoint needs to show inventory without a join (cross-database)
- Feign call to inventory-service is synchronous; denormalization provides a fallback display value

### Product ← Rating

**Denormalized fields:** `Product.average_rating`, `rating_count`, `review_count`, `rating_distribution`  
**Source of truth:** `Rating` entities in rating_db  
**Consistency window:** Event-based (eventual)  
**Update mechanism:** RatingEventListener consumes `RATING_ADDED`, `RATING_UPDATED`, `RATING_DELETED` events  
**Idempotency:** Recalculated from rating service's aggregate (future implementation)

**Why denormalize?**
- Product detail page displays aggregate ratings without a cross-service call
- Enables sorting/filtering by average_rating without querying rating-service

### Inventory ← Product

**Denormalized fields:** `Inventory.product_name`, `product_sku`  
**Source of truth:** `Product.name`, `Product.sku`  
**Consistency window:** Event-based (eventual)  
**Update mechanism:** ProductEventListener consumes `PRODUCT_UPDATED` event  
**Idempotency:** Fields are overwritten with the same values; safe to replay

**Why denormalize?**
- Inventory list can display product context without joining across databases
- Useful for human-readable inventory reports

---

## Indexes & Performance

### Product Service

```sql
-- PRIMARY KEY (automatic)
CREATE INDEX products_category_id_idx ON product.products(category_id);
CREATE INDEX products_is_featured_idx ON product.products(is_featured) WHERE is_featured = true;
CREATE INDEX products_average_rating_idx ON product.products(average_rating);
CREATE INDEX products_sale_price_idx ON product.products(sale_price);
CREATE INDEX products_created_at_idx ON product.products(created_at);
CREATE INDEX product_images_product_id_idx ON product.product_images(product_id);
```

**Query patterns:**
- `SELECT * FROM products ORDER BY sku` — uses sku UNIQUE constraint
- `SELECT * FROM products WHERE category_id = ?` — uses category_id index
- `SELECT * FROM products WHERE is_featured = true ORDER BY ...` — partial index on is_featured
- `SELECT * FROM products WHERE average_rating > ? ORDER BY average_rating` — uses average_rating index
- `SELECT * FROM products WHERE sale_price BETWEEN ? AND ?` — uses sale_price index
- `SELECT * FROM product_images WHERE product_id = ? ORDER BY display_order` — uses product_id index

### Inventory Service

```sql
-- PRIMARY KEY (automatic)
-- UNIQUE (product_id) provides index for by-product queries
```

**Query patterns:**
- `SELECT * FROM stock_levels ORDER BY product_id` — uses product_id UNIQUE constraint
- `SELECT * FROM stock_levels WHERE product_id = ?` — UNIQUE constraint index

### Rating Service

```sql
-- PRIMARY KEY (automatic)
CREATE INDEX ratings_product_id_idx ON rating.ratings(product_id);
CREATE INDEX ratings_user_id_idx ON rating.ratings(user_id);
CREATE INDEX ratings_created_at_idx ON rating.ratings(created_at);
CREATE INDEX ratings_rating_value_idx ON rating.ratings(rating_value);
CREATE INDEX rating_votes_rating_id_idx ON rating.rating_votes(rating_id);
CREATE INDEX rating_votes_user_id_idx ON rating.rating_votes(user_id);
```

**Query patterns:**
- `SELECT * FROM ratings WHERE product_id = ? ORDER BY created_at DESC` — uses product_id index
- `SELECT * FROM ratings WHERE rating_value >= ?` — uses rating_value index
- `SELECT * FROM rating_votes WHERE rating_id = ?` — uses rating_id index
- `SELECT * FROM rating_votes WHERE user_id = ?` — uses user_id index

### No N+1 Risk

Domain entities **have no associations** (@ManyToOne, @OneToMany). All reads are direct table scans or indexed lookups; no lazy loading. A page of 20 products is exactly one SELECT query.

---

## Seed Data

### Products (Phase 1E baseline)

Five seed products inserted in V1 migration:

| SKU | Name | Price | Active |
|-----|------|-------|--------|
| SKU-KEYBOARD-01 | Mechanical Keyboard | 129.99 USD | true |
| SKU-MOUSE-01 | Wireless Mouse | 49.50 USD | true |
| SKU-MONITOR-27 | 27-inch 4K Monitor | 449.00 USD | true |
| SKU-DOCK-USBC | USB-C Docking Station | 199.95 USD | true |
| SKU-HEADSET-01 | Noise-Cancelling Headset | 279.00 USD | false |

**Purpose:** Enable end-to-end testing on first boot; avoid an empty catalog.

### Inventory (Phase 2)

Auto-provisioned via `PRODUCT_CREATED` events; no explicit seed needed if events flow correctly. In early development, manually created via POST /api/inventory if needed.

### Ratings (Phase 4)

Seed data in V2 migration creates 50+ ratings across the products to provide realistic aggregates and test rating denormalization.

---

## Consistency Notes

### Transactional Boundaries

Each service's write is ACID within its database:
- Product create/update/delete in product_db
- Inventory adjust in inventory_db
- Rating add/update/delete in rating_db

### Cross-Database Consistency

Not guaranteed. If event delivery fails:
- Product created but inventory never provisioned
- Inventory updated but product's lastKnownQuantity stays stale
- Rating added but product's averageRating stays stale

Recovery mechanisms (out of scope for this prototype):
- Periodic cache refresh/reconciliation jobs
- Explicit sync endpoints (e.g., POST /admin/sync-all)
- Consumer lag monitoring with alerts

---

## References

- [Backend-Architecture.md](overview.md) — Service architecture and deployment
- [Backend-EventSystem.md](events.md) — Event types and denormalization updates
- [Backend-API.md](api.md) — API endpoints returning these entities
- Database migrations: `services/*/src/main/resources/db/migration/`
