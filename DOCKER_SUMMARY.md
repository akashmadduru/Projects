# Docker Configuration Summary

## ✅ Complete Setup for Both Frontend Apps + Backend Services

### Files Created

#### Dockerfiles
1. **`apps/product-catalog/Dockerfile`** — React 19 + Vite (port 3000)
2. **`apps/admin-product-catalog/Dockerfile`** — Vue 3 + Vite (port 3001)

#### Docker Ignore Files
3. **`apps/product-catalog/.dockerignore`** — Optimize React build context
4. **`apps/admin-product-catalog/.dockerignore`** — Optimize Vue build context

#### Configuration
5. **`infra/docker-compose.yml`** — Updated with:
   - ✅ `admin-product-catalog` service (Vue admin app, port 3001)
   - ✅ `product-catalog` service (React customer app, port 3000)
   - ✅ Updated gateway CORS origins for all 4 frontend ports:
     - Dev: 5173 (Vue), 5174 (React)
     - Docker: 3001 (Vue), 3000 (React)

6. **`infra/.env.example`** — Added port override options:
   - `ADMIN_CATALOG_PORT=3001`
   - `PRODUCT_CATALOG_PORT=3000`

#### Documentation
7. **`DOCKER_SETUP.md`** — Complete guide (updated with both apps)
8. **`DOCKER_SUMMARY.md`** — This file

---

## 🚀 Quick Start

### Full Stack (Everything in Docker)

```bash
# Setup
cp infra/.env.example infra/.env
bash infra/scripts/generate-keystore.sh

# Start all services
docker compose -f infra/docker-compose.yml up -d

# Access apps
echo "Vue Admin:        http://localhost:3001"
echo "React Catalog:    http://localhost:3000"
echo "API Gateway:      http://localhost:8080"
echo "Eureka Registry:  http://localhost:8761"
```

### Local Development (Recommended)

```bash
# Terminal 1: Start backend only
docker compose -f infra/docker-compose.yml up -d

# Terminal 2: Vue admin app
cd apps/admin-product-catalog
npm install && npm run dev
# → http://localhost:5173

# Terminal 3: React catalog
cd apps/product-catalog
npm install && npm run dev
# → http://localhost:5174
```

---

## 📦 Docker Compose Services

### Frontend Applications

| Service | Technology | Port | Purpose |
|---------|-----------|------|---------|
| **admin-product-catalog** | Vue 3 + Vite | 3001 | Admin product management |
| **product-catalog** | React 19 + Vite | 3000 | Customer product browsing |

### Backend Services

| Service | Technology | Port | Purpose |
|---------|-----------|------|---------|
| **eureka-server** | Spring Cloud | 8761 | Service registry |
| **auth-service** | Spring Boot | 8081 | JWT identity provider |
| **gateway** | Spring Cloud | 8080 | API gateway + CORS |
| **product-service** | Spring Boot | 8082 | Product catalog data |
| **inventory-service** | Spring Boot | 8083 | Stock level management |

### Infrastructure Services

| Service | Technology | Port | Purpose |
|---------|-----------|------|---------|
| **postgres** | PostgreSQL | 5432 | Databases for all services |
| **kafka** | Apache Kafka | 9092 | Event streaming (internal) |
| **redis** | Redis | 6379 | Session cache (internal) |
| **zookeeper** | ZooKeeper | 2181 | Distributed coordination |

---

## 🔄 Frontend App Details

### React Product Catalog
- **Location:** `apps/product-catalog/`
- **Build:** Vite 6 multi-stage Docker build
- **Port (Docker):** 3000
- **Port (Dev):** 5174
- **Size:** ~327KB gzipped
- **Features:** Zustand state, single-flight dedup, theme toggle

### Vue Admin SPA
- **Location:** `apps/admin-product-catalog/`
- **Build:** Vite 7 multi-stage Docker build
- **Port (Docker):** 3001
- **Port (Dev):** 5173
- **Features:** Pinia state, admin dashboard, product management

---

## 🌐 CORS Configuration

Both frontend apps can reach the backend through the API Gateway:

```
Frontend (browser)  →  API Gateway (8080)  →  Backend Services
     ↓
CORS allowed origins:
- http://localhost:5173  (Vue dev)
- http://localhost:5174  (React dev)
- http://localhost:3001  (Vue Docker)
- http://localhost:3000  (React Docker)
```

---

## 📝 Environment Variables

### Setup (Infra)

```bash
# infra/.env (copy from .env.example)
AUTH_DB_PASSWORD=auth_service_local_pwd
PRODUCT_DB_PASSWORD=product_service_local_pwd
INVENTORY_DB_PASSWORD=inventory_service_local_pwd
JWT_KEYSTORE_PASSWORD=change-me
```

### Optional Port Overrides

```bash
# If default ports conflict locally
ADMIN_CATALOG_PORT=3001      # Default: 3001
PRODUCT_CATALOG_PORT=3000    # Default: 3000
POSTGRES_PORT=5432           # Default: 5432
```

---

## 🐳 Docker Commands

### Manage the Full Stack

```bash
# Start all services
docker compose -f infra/docker-compose.yml up -d

# See status
docker compose -f infra/docker-compose.yml ps

# View logs
docker compose -f infra/docker-compose.yml logs -f

# Stop services (keep data)
docker compose -f infra/docker-compose.yml down

# Stop and wipe data
docker compose -f infra/docker-compose.yml down -v
```

### Rebuild Frontend Apps

```bash
# Rebuild React app
docker compose -f infra/docker-compose.yml build --no-cache product-catalog

# Rebuild Vue app
docker compose -f infra/docker-compose.yml build --no-cache admin-product-catalog

# Restart services after rebuild
docker compose -f infra/docker-compose.yml up -d product-catalog admin-product-catalog
```

---

## ✅ Verification Checklist

After running `docker compose up -d`:

- [ ] All services show `running` status: `docker compose ps`
- [ ] Vue admin app loads at http://localhost:3001
- [ ] React catalog app loads at http://localhost:3000
- [ ] Both apps can fetch products: check browser Network tab
- [ ] API Gateway health: `curl http://localhost:8080/actuator/health`
- [ ] Theme toggle works (light/dark mode)
- [ ] Pagination navigates between pages
- [ ] No console errors in browser DevTools

---

## 🚨 Troubleshooting

### Port Already in Use

Change the port in `.env`:
```bash
ADMIN_CATALOG_PORT=3001
PRODUCT_CATALOG_PORT=3000
```

### Apps Can't Connect to Backend

1. Check gateway is healthy: `curl http://localhost:8080/actuator/health`
2. Check CORS headers: `docker compose logs gateway | grep -i cors`
3. Verify origins in `infra/.env` match browser URL

### Docker Build Fails

```bash
# Clean rebuild
docker compose -f infra/docker-compose.yml down
docker compose -f infra/docker-compose.yml up --build -d
```

---

## 📚 Related Documentation

- **Full Setup Guide:** [DOCKER_SETUP.md](./DOCKER_SETUP.md)
- **React App:** [apps/product-catalog/README.md](./apps/product-catalog/README.md)
- **Vue App:** Check `apps/admin-product-catalog/README.md`
- **Architecture:** See `docs/` folder for HLD/LLD

---

## 🎯 Next Steps

1. **Test locally:**
   ```bash
   docker compose -f infra/docker-compose.yml up -d
   ```

2. **Verify both apps work:**
   - Admin: http://localhost:3001
   - Customer: http://localhost:3000

3. **Continue with /plan-feature pipeline** (if needed)

4. **Deploy to cloud** (K8s, ECS, etc. with production environment variables)
