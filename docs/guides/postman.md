# Postman collection

The files live in [`postman/`](../../postman/). The collection covers the Phase 1–2 services only
(gateway, auth, product, inventory, eureka). For every other endpoint see
[endpoints.md](../architecture/endpoints.md).

- `postman/ecommerce-platform.postman_collection.json` — requests for every service, in folders:
  Gateway, Auth Service (+ Direct bypass), Product Service (+ Direct bypass),
  Inventory Service (+ Direct bypass), Eureka Server.
- `postman/ecommerce-platform-local.postman_environment.json` — base URLs for local dev
  (gateway `:8080`, auth-service `:8081`, product-service `:8082`, inventory-service `:8083`,
  eureka `:8761`).

## Use

1. Import both files into Postman, select the "E-commerce Platform - Local" environment.
2. Start the platform (`docker compose -f infra/docker-compose.yml up -d`, then the Gradle
   services / `apps/admin-product-catalog` as needed).
3. Run **Auth Service → Login** first — it captures `accessToken` into a collection variable
   used by every Bearer-protected request, and Postman's cookie jar picks up the
   `refresh_token` cookie for **Refresh Token** / **Logout**.
4. `Register` requires `AUTH_REGISTRATION_ENABLED=true` on auth-service (compose defaults it to true since Phase 5).
5. Product/Inventory **write** requests (`Create`/`Update`/`Delete`) need a `CATALOG_MANAGER` or
   `ADMIN` role on the logged-in user — a plain `ROLE_VIEWER` token (the default for
   self-registered users) gets `403`. Provision a manager/admin user directly in `auth_db` for
   local testing, or adjust the seed data.
6. **Create Product** and **Create Inventory (manual)** capture `productId`/`inventoryId` into
   collection variables, reused by the `:id`-path requests below them (`Update`/`Delete`/`Get By
   Id`) — run them in order the first time.

All client-facing requests route through the gateway (`{{gatewayBaseUrl}}`), matching how the
platform is meant to be used; each service folder also has a "Direct (bypass gateway)"
sub-folder for local debugging against the service directly.

**Event-driven note**: `POST`/`PUT`/`DELETE /api/products` publish to Kafka topic `product.events`
after the write commits; inventory-service consumes it asynchronously (idempotent, at-least-once)
and provisions/removes its own `inventory.stock_levels` row a moment later. A `GET
/api/inventory` run immediately after `Create Product` may still 404 for that `productId` until
the consumer catches up — this is expected, prototype-scope eventual consistency, not a bug. See
[HLD: event-driven inventory](../architecture/design/HLD-event-driven-inventory.md) for the full design.
