# 4. Deployment Topology — Container Diagram

## C4 Container Diagram

```mermaid
graph TB
    User["👤 User/Browser"]
    
    subgraph Client["Client Tier"]
        direction TB
        SPA["📦 React/Vue SPA<br/>admin-product-catalog<br/>(dist/)<br/>Static Files: JS, CSS, HTML"]
    end
    
    subgraph Network["Network Tier"]
        direction TB
        Gateway["🚪 API Gateway<br/>Port: 8080<br/>Spring Cloud Gateway<br/>CORS Enabled<br/>HttpOnly Cookie Handler"]
    end
    
    subgraph Services["Service Tier (Eureka Registered)"]
        direction TB
        AuthService["🔐 Auth Service<br/>Port: 8081<br/>- OAuth2 Provider<br/>- JWT Issuer<br/>- Token Refresh<br/>- User Registry"]
        ProductService["📦 Product Service<br/>Port: 8082<br/>- Product CRUD<br/>- Inventory Events<br/>- Catalog API<br/>- Pagination Handler"]
        InventoryService["📊 Inventory Service<br/>Port: 8083<br/>- Stock Management<br/>- Warehouse Tracking<br/>- Event Consumer"]
    end
    
    subgraph Discovery["Service Discovery"]
        direction TB
        Eureka["🗺️ Eureka Server<br/>Service Registry<br/>Health Checks<br/>Dynamic Routing"]
    end
    
    subgraph Data["Data Tier"]
        direction TB
        AuthDB["🗄️ Auth Database<br/>PostgreSQL<br/>Users, Sessions,<br/>Tokens"]
        ProductDB["🗄️ Product Database<br/>PostgreSQL<br/>Products, Categories,<br/>Images, Specs"]
        InventoryDB["🗄️ Inventory Database<br/>PostgreSQL<br/>Stock Levels,<br/>Warehouses"]
    end
    
    subgraph Messaging["Event Bus"]
        direction TB
        Kafka["📨 Kafka<br/>Event Streaming<br/>Topics:<br/>- product.created<br/>- product.updated<br/>- inventory.adjusted"]
    end
    
    User -->|HTTPS| SPA
    User -->|Fetch/XMLHttpRequest| Gateway
    
    SPA -->|HTTP(S) Requests| Gateway
    
    Gateway -->|Route:/api/auth/*| AuthService
    Gateway -->|Route:/api/products/*| ProductService
    Gateway -->|Route:/api/inventory/*| InventoryService
    
    AuthService -->|Service Lookup| Eureka
    ProductService -->|Service Lookup| Eureka
    InventoryService -->|Service Lookup| Eureka
    Gateway -->|Service Discovery| Eureka
    
    AuthService -->|JDBC| AuthDB
    ProductService -->|JDBC| ProductDB
    InventoryService -->|JDBC| InventoryDB
    
    ProductService -->|Publish Events| Kafka
    InventoryService -->|Consume Events| Kafka
    AuthService -->|Publish Login Events| Kafka
    
    classDef browser fill:#e3f2fd,stroke:#1976d2,stroke-width:2px,color:#000
    classDef gateway fill:#fff3e0,stroke:#f57c00,stroke-width:2px,color:#000
    classDef service fill:#e8f5e9,stroke:#388e3c,stroke-width:2px,color:#000
    classDef database fill:#fce4ec,stroke:#c2185b,stroke-width:2px,color:#000
    classDef discovery fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px,color:#000
    classDef messaging fill:#fff8e1,stroke:#fbc02d,stroke-width:2px,color:#000
    
    class SPA browser
    class Gateway gateway
    class AuthService,ProductService,InventoryService service
    class AuthDB,ProductDB,InventoryDB database
    class Eureka discovery
    class Kafka messaging
```

## Narrative Description

### Deployment Architecture Overview

The admin product catalog SPA is deployed as **static files** (dist/) served by a CDN or static file server. The application communicates exclusively with an **API Gateway** at port 8080, which routes requests to microservices registered with **Eureka service discovery**.

### Client Tier

**Vue 3 SPA (admin-product-catalog)**
- **Deployment:** Vite-built static files in `dist/` folder
  - `dist/index.html` — entry point
  - `dist/js/*.js` — lazy-loaded route chunks (ProductsView, InventoryView, etc.)
  - `dist/css/*.css` — Tailwind + daisyUI compiled styles
  - `dist/assets/` — images, fonts
- **Hosting:** CDN or nginx static server (no backend build required)
- **CORS:** Requests to `http://localhost:8080` (or production API domain) include `credentials: include` to send HttpOnly cookies

### Network Tier

**Spring Cloud Gateway (Port 8080)**
- **CORS Configuration:**
  - `Allow-Credentials: true`
  - `Allow-Origin: <exact origin of SPA>`
  - `Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS`
  - `Allow-Headers: Content-Type, Authorization`
- **Responsibilities:**
  - Route `/api/auth/*` → Auth Service (8081)
  - Route `/api/products/*` → Product Service (8082)
  - Route `/api/inventory/*` → Inventory Service (8083)
  - Load balance across multiple instances of each service
  - Handle service discovery via Eureka (automatic routing to healthy instances)
  - Rate limiting, request logging, audit trails
  - HttpOnly cookie handling for refresh tokens (transparent passthrough)

### Service Tier (Eureka-Registered)

**Auth Service (Port 8081)**
- Handles user registration, login (password + Google OAuth2), session validation
- Maintains JWT access tokens and HttpOnly refresh cookies
- Provides `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh`, `/api/auth/me`, `/api/auth/logout`
- Implements single-flight refresh latch (returns same token to concurrent refresh requests)
- Database: separate `auth` schema in PostgreSQL

**Product Service (Port 8082)**
- Full CRUD for catalog products
- Handles pagination, search, filtering, category hierarchies
- Endpoints: `GET /api/products`, `POST /api/products`, `PUT /api/products/{id}`, `DELETE /api/products/{id}`
- Publishes `ProductCreated`, `ProductUpdated`, `ProductDeleted` events to Kafka
- Maintains denormalized stock levels (from inventory-service via event stream)
- Database: separate `product` schema in PostgreSQL

**Inventory Service (Port 8083)**
- Manages stock levels per product + warehouse
- Endpoints: `GET /api/inventory`, `PUT /api/inventory/{id}`, `POST /api/inventory`
- Consumes `ProductCreated` events → auto-provisions zero-quantity stock rows
- Publishes `StockAdjusted` events consumed by analytics, reorder systems
- Database: separate `inventory` schema in PostgreSQL

**Service Discovery (Eureka)**
- All services register themselves on startup with `serviceId` (e.g., `PRODUCT-SERVICE`)
- Gateway queries Eureka for the current healthy instances of each service
- Automatic failover if a service instance dies
- Health checks every 30s; instance marked down after 90s of failed pings

### Data Tier

**PostgreSQL Databases** (3 independent schemas or 3 instances)
- `auth` schema — users, roles, refresh token families (for reuse detection)
- `product` schema — products, categories, images, specifications, denormalized stock hint
- `inventory` schema — stock rows per product + warehouse, version counters for optimistic locking

Each database is independently backed up and scalable.

### Event Bus (Kafka)

**Topics:**
- `product.created` — consumed by inventory-service (provision stock) and analytics
- `product.updated` — consumed by search-indexing, inventory-service (denormalization)
- `product.deleted` — consumed by cleanup jobs
- `stock.adjusted` — consumed by reorder systems, dashboards

**Event Schema:** RFC 9457 + custom fields (productId, oldState, newState, timestamp, correlationId)

This ensures eventual consistency between services without direct synchronous calls, enabling independent scaling and deployment.

### Network Flows

**1. SPA → Browser HTTP Request**
```
GET http://localhost:8080/api/products?page=0&size=20
Headers: {
  Authorization: Bearer <access_token>,
  Cookie: <refresh_token (HttpOnly)>,
  Content-Type: application/json
}
```

**2. Gateway → Service via Eureka**
```
Gateway queries: "Give me 3 healthy instances of PRODUCT-SERVICE"
Eureka responds: [
  { host: 10.0.1.2, port: 8082 },
  { host: 10.0.1.3, port: 8082 },
  { host: 10.0.1.4, port: 8082 }
]
Gateway load-balances (round-robin) and forwards the request.
```

**3. Service → Database**
```
Product Service executes:
SELECT * FROM products WHERE 1=1 LIMIT 20 OFFSET 0;
SELECT COUNT(*) FROM products;
```

**4. Service → Kafka (Async Notification)**
```
ProductService publishes event:
Topic: product.created
Value: { productId: "abc", name: "...", sku: "...", timestamp, correlationId }

InventoryService consumes and executes:
INSERT INTO inventory (product_id, quantity, version) VALUES ('abc', 0, 1);
```

### Deployment Modes

**Development**
- All services on localhost:
  - SPA: http://localhost:5173 (Vite dev server)
  - Gateway: http://localhost:8080
  - Auth: http://localhost:8081
  - Product: http://localhost:8082
  - Inventory: http://localhost:8083
  - Eureka: http://localhost:8761
  - PostgreSQL: localhost:5432
  - Kafka: localhost:9092

**Production**
- SPA: Deployed to CDN (Cloudfront, CloudFlare, etc.) or static S3 bucket
- Services: Kubernetes cluster with:
  - HPA (Horizontal Pod Autoscaling) based on CPU/memory
  - Service mesh (Istio) for traffic management, retry, circuit breaking
  - Persistent volumes for databases
  - Eureka/Consul alternative (K8s native service discovery via DNS)

### Scaling Considerations

**Stateless Services:** Auth, Product, Inventory services are stateless and can scale horizontally.
- Token refresh is guarded by single-flight latch (in-memory per instance) + cross-tab Web Lock (browser-side) + backend token rotation detection
- No affinity required; requests can be routed to any healthy instance

**Databases:** Can be scaled via:
- Read replicas for product/inventory queries
- Write replicas with async replication for auth DB
- Connection pooling via PgBouncer (per-service)

**Kafka:** Partition topics by productId to maintain per-product ordering and enable per-partition consumer group scaling.

