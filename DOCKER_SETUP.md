# Docker Setup Guide - Full Stack (Backend + React Frontend)

## Quick Start

### 1. Generate JWT Keystore (First Time Only)

```bash
cd infra
bash scripts/generate-keystore.sh
```

### 2. Set Up Environment Variables

```bash
cp infra/.env.example infra/.env
```

Then edit `infra/.env` and fill in the required values:
- `AUTH_DB_PASSWORD` — password for auth_service PostgreSQL user
- `PRODUCT_DB_PASSWORD` — password for product_service PostgreSQL user
- `INVENTORY_DB_PASSWORD` — password for inventory_service PostgreSQL user
- `JWT_KEYSTORE_PASSWORD` — password for JWT signing keystore

(Development defaults are pre-filled; for production, use strong passwords)

### 3. Build and Start the Full Stack

```bash
# From project root
docker compose -f infra/docker-compose.yml up -d
```

This starts all services:
- **PostgreSQL** (port 5432) — database backing for all services
- **Kafka** (internal port 9092) — event streaming for async operations
- **Redis** (port 6379) — session cache for auth-service
- **Eureka Registry** (port 8761) — service discovery
- **Auth Service** (port 8081) — identity provider and JWT issuer
- **Gateway** (port 8080) — API gateway, single public entry point
- **Product Service** (port 8082) — catalog service
- **Inventory Service** (port 8083) — inventory tracking
- **Vue Admin SPA** (port 3001) — admin product management interface
- **React Product Catalog** (port 3000) — customer-facing SPA

### 4. Verify All Services Are Running

```bash
docker compose -f infra/docker-compose.yml ps
```

Expected output: all services in `running` state.

### 5. Access the Applications

| Service | URL | Purpose |
|---------|-----|---------|
| **React Product Catalog** | http://localhost:3000 | Customer-facing product browsing (Phase 1G) |
| **Vue Admin SPA** | http://localhost:3001 | Admin product management (Phase 1F) |
| **API Gateway** | http://localhost:8080 | Backend API (used by both frontends) |
| **Eureka Dashboard** | http://localhost:8761 | Service registry (dev only) |
| **PostgreSQL** | localhost:5432 | Database (psql client only) |

### 6. Example: Login and View Products

```bash
# 1. Login to get JWT token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password"}'

# Response includes access_token (JWT) and refresh_token (HttpOnly cookie)

# 2. View products (using the token)
curl http://localhost:8080/api/products \
  -H "Authorization: Bearer <ACCESS_TOKEN>"

# 3. Or just open the React app at http://localhost:3000
#    The browser will display the product catalog
```

---

## Development Workflow

### Option 1: Local Dev with Hot-Reload (Recommended for Development)

Run both frontend apps locally with Vite hot-reload while backend runs in Docker:

```bash
# Terminal 1: Start backend services
docker compose -f infra/docker-compose.yml down  # Clean start (optional)
docker compose -f infra/docker-compose.yml up -d

# Terminal 2: Start Vue admin app
cd apps/admin-product-catalog
npm install
npm run dev
# Access at http://localhost:5173 (Vite dev server)

# Terminal 3: Start React product catalog
cd apps/product-catalog
npm install
npm run dev
# Access at http://localhost:5174 (Vite dev server)

# Both apps will proxy /api/* to http://localhost:8080 (gateway)
```

### Option 2: Everything in Docker (Production-like)

All services in containers, same as production:

```bash
# Build and start everything
docker compose -f infra/docker-compose.yml up -d

# Access the apps:
# - Vue admin:        http://localhost:3001
# - React catalog:    http://localhost:3000
# 
# Browser makes API calls directly to http://localhost:8080 (gateway)
```

### Option 3: Mixed (Backend in Docker, One App Local)

```bash
# Terminal 1: Start backend services
docker compose -f infra/docker-compose.yml up -d

# Terminal 2: Start just the React app locally
cd apps/product-catalog
npm run dev
# Access at http://localhost:5174

# Vue admin runs in Docker at http://localhost:3001
```

---

## Troubleshooting

### Services Won't Start

1. **Port conflict** — Check if ports (3000, 5432, 8080, etc.) are in use:
   ```bash
   netstat -an | grep LISTENING  # on Windows: netstat -ano
   ```
   
   Override ports in `.env`:
   ```
   POSTGRES_PORT=15432
   PRODUCT_CATALOG_PORT=3001
   ```
   Then restart:
   ```bash
   docker compose -f infra/docker-compose.yml up -d
   ```

2. **Out of memory** — Increase Docker's memory allocation (Docker Desktop → Settings → Resources)

3. **Service won't become healthy** — Check logs:
   ```bash
   docker compose -f infra/docker-compose.yml logs <service-name>
   # e.g., docker compose -f infra/docker-compose.yml logs product-service
   ```

### Frontend Can't Connect to Backend

1. Check the gateway is healthy:
   ```bash
   curl http://localhost:8080/actuator/health
   ```

2. Check CORS headers in gateway logs:
   ```bash
   docker compose -f infra/docker-compose.yml logs gateway
   ```

3. Ensure the frontend's origin is in `GATEWAY_CORS_ALLOWED_ORIGINS` (in `.env`):
   ```
   GATEWAY_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost:5173,http://localhost:5174
   ```

### Database Schema Issues

If database migrations fail:

```bash
# Wipe and reset
docker compose -f infra/docker-compose.yml down -v

# Rebuild
docker compose -f infra/docker-compose.yml up -d
```

This deletes all data volumes and reinitializes from `infra/postgres/init/01-schemas.sql`.

---

## Stopping and Cleaning Up

### Stop All Services (Keep Data)

```bash
docker compose -f infra/docker-compose.yml down
```

### Stop and Wipe All Data

```bash
docker compose -f infra/docker-compose.yml down -v
```

### Rebuild Frontend Apps

If you update either frontend app code:

```bash
# Rebuild React Product Catalog
docker compose -f infra/docker-compose.yml build --no-cache product-catalog
docker compose -f infra/docker-compose.yml up -d product-catalog

# Rebuild Vue Admin SPA
docker compose -f infra/docker-compose.yml build --no-cache admin-product-catalog
docker compose -f infra/docker-compose.yml up -d admin-product-catalog

# Rebuild both
docker compose -f infra/docker-compose.yml build --no-cache product-catalog admin-product-catalog
docker compose -f infra/docker-compose.yml up -d product-catalog admin-product-catalog
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│ Browser                                                 │
├─────────────────────────────────────────────────────────┤
│ Admin Portal (http://localhost:3001)                    │ ← Vue 3 admin SPA
│ Customer Portal (http://localhost:3000)                 │ ← React customer SPA
├─────────────────────────────────────────────────────────┤
│ Both apps make HTTP calls to /api/*                     │
│           ↓                                              │
├─────────────────────────────────────────────────────────┤
│ API Gateway (Spring Cloud Gateway, port 8080)           │
│ - CORS enabled for both frontend apps                   │
│ - JWT token validation                                  │
│ - Routes to backend services                            │
│           ↓                                              │
├─────────────────────────────────────────────────────────┤
│ Backend Services:                                        │
│ - Auth Service (port 8081)      → JWT issuer            │
│ - Product Service (port 8082)   → Catalog data          │
│ - Inventory Service (port 8083) → Stock levels          │
│           ↓                                              │
├─────────────────────────────────────────────────────────┤
│ Data Layer:                                              │
│ - PostgreSQL (port 5432)  → Authoritative database      │
│ - Kafka (port 9092)       → Event streaming             │
│ - Redis (port 6379)       → User session cache          │
└─────────────────────────────────────────────────────────┘
```

All services communicate over the Docker compose network. Only the gateway and two SPA ports (3000, 3001) are published to the host.

---

## Next Steps

1. **Test the React app** — Open http://localhost:3000 and browse the product catalog
2. **Customize the React theme** — Edit `apps/product-catalog/src/lib/theme.ts` to change colors
3. **Add more features** — See `apps/product-catalog/README.md` for development guide
4. **Deploy to cloud** — Update `docker-compose.yml` with production environment variables and deploy to K8s/ECS/etc.
