# Tech Debt: Infra and Security

Index: [README](README.md)

<a id="td-sec-01"></a>
## TD-SEC-01 Real GOOGLE_CLIENT_SECRET in local `infra/.env`
- **Severity / Effort:** High / S
- **Where:** `infra/.env` (a non-empty `GOOGLE_CLIENT_SECRET`). Gitignored (`.gitignore` lines 32-34, `.env.example` excepted), so not in the repo.
- **Risk:** The secret was pasted into plaintext and handled during this work; anyone with the machine or a shared copy has it.
- **Fix:** Rotate the secret in Google Cloud Console and update `infra/.env`; use a secrets manager outside local dev. Confirm with `git log -p -- infra/.env` that it was never committed.

<a id="td-sec-02"></a>
## TD-SEC-02 Admin SPA Google login starts via the gateway
- **Severity / Effort:** High / S
- **Where:** `apps/admin-product-catalog/src/lib/http.ts`, `googleAuthorizationUrl()` returns `${apiBaseUrl}/oauth2/authorization/google` with `apiBaseUrl` defaulting to `http://localhost:8080`.
- **Risk:** Through the gateway, auth-service builds the Google `redirect_uri` from the request host (`http://<container-ip>:8081/login/oauth2/code/google`), which Google rejects. Google login in the admin app is broken. The storefront avoids this by starting at `http://localhost:8081` (`VITE_AUTH_BASE_URL`).
- **Fix:** Same change as the storefront (auth base URL env defaulting to :8081), or configure auth-service to honor `X-Forwarded-Host` (`server.forward-headers-strategy=framework`) and route `/oauth2/**` and `/login/oauth2/**` through the gateway with forwarded headers.

<a id="td-sec-03"></a>
## TD-SEC-03 Dev DB passwords committed
- **Severity / Effort:** Med / S
- **Where:** `infra/.env.example` (`USER_DB_PASSWORD`, `CART_DB_PASSWORD`, `ORDER_DB_PASSWORD`), `infra/scripts/create-customer-dbs.sql`, `infra/postgres/init/01-schemas.sql`. Compose requires them via `:?`.
- **Risk:** Throwaway values, same pattern as sibling services; dangerous if reused outside local dev.
- **Fix:** Generate per-environment passwords; remove literals from SQL (use psql variables).

<a id="td-sec-04"></a>
## TD-SEC-04 Open registration by default, no email verification
- **Severity / Effort:** Med / M
- **Where:** `infra/docker-compose.yml` (`AUTH_REGISTRATION_ENABLED:-true`).
- **Risk:** Anyone can create accounts with unverified emails. Fine for dev, not for production.
- **Fix:** Default false outside dev; add email verification.

<a id="td-sec-05"></a>
## TD-SEC-05 Manual DB script for existing volumes
- **Severity / Effort:** Med / M
- **Where:** `infra/scripts/create-customer-dbs.sql`.
- **Risk:** Init scripts run only on an empty volume. Existing environments must run the script by hand, or user/cart/order services fail to start. No migration runner for infra.
- **Fix:** A one-shot `db-bootstrap` compose service that runs idempotent role/db creation on every `up`.

<a id="td-sec-06"></a>
## TD-SEC-06 Dev admin seed removed, no replacement
- **Severity / Effort:** Med / S
- **Where:** `infra/postgres/init/01-schemas.sql`. The old seed inserted into `auth.users` before Flyway had created it (broken).
- **Risk:** No documented way to get a first ADMIN account for the admin SPA.
- **Fix:** Dev-only bootstrap in auth-service (property-gated) or a documented SQL step.

<a id="td-sec-07"></a>
## TD-SEC-07 rating-service not runnable
- **Severity / Effort:** Med / M
- **Where:** included in `settings.gradle` (lines 53-54) but absent from `infra/docker-compose.yml` and gateway routes.
- **Risk:** `/api/ratings` is unreachable end to end; ratings UI cannot work.
- **Fix:** Add compose block, DB bootstrap and gateway route modeled on inventory-service.

<a id="td-sec-08"></a>
## TD-SEC-08 No account-linking UX
- **Severity / Effort:** Low / M
- **Where:** auth-service `FederatedIdentityResolver` (unchanged) and the storefront login page.
- **Risk:** A Google login whose email matches a password account is resolved by auth-service as-is; the storefront does not explain or confirm linking.
- **Fix:** Confirm-linking step requiring the existing password.

<a id="td-sec-09"></a>
## TD-SEC-09 Prod cookie/redirect defaults must be overridden
- **Severity / Effort:** Med / S
- **Where:** `services/auth-service/auth-service-app/src/main/resources/application.yml` (`AUTH_COOKIE_SECURE` default false), `infra/docker-compose.yml` (`AUTH_ALLOWED_REDIRECT_URIS` defaults to localhost 5173/3000).
- **Risk:** Carried into a real deployment, the refresh cookie travels over HTTP and localhost redirects are accepted.
- **Fix:** Set `AUTH_COOKIE_SECURE=true` and an HTTPS-only allow-list explicitly in prod; confirm the prod profile's `secure: true` is active.

<a id="td-sec-10"></a>
## TD-SEC-10 Feign token relay goes to any `services.*.url` override
- **Severity / Effort:** Low / S
- **Where:** order-service `CartFeignClient`, `UserFeignClient`, `ProductFeignClient` (`url=${services.x.url:}`); `libs/common-web/.../CorrelationAndAuthForwardingInterceptor.java` forwards `Authorization` to every Feign target.
- **Risk:** An operator-set override pointing outside the cluster would receive user tokens. Needs deployment config access.
- **Fix:** Never set the overrides outside local dev; optionally assert internal hosts at startup or relay only for known client names.

<a id="td-sec-11"></a>
## TD-SEC-11 `http.ts` attaches bearer token to absolute URLs
- **Severity / Effort:** Low / S
- **Where:** `apps/product-catalog/src/lib/http.ts` (`buildUrl` accepts absolute URLs; `send` always adds `Authorization`).
- **Risk:** All callers use relative `/api/*` today; a future absolute or user-influenced URL would leak the token.
- **Fix:** Attach the header only when the request origin equals `window.location.origin`.

<a id="td-sec-12"></a>
## TD-SEC-12 Non-UUID JWT `sub` returns 500
- **Severity / Effort:** Low / S
- **Where:** `UserController`, `CartController`, `OrderController` (`UUID.fromString(jwt.getSubject())`).
- **Risk:** Cannot happen with auth-service tokens; robustness only.
- **Fix:** Map `IllegalArgumentException` from subject parsing to 401.

<a id="td-sec-13"></a>
## TD-SEC-13 DB `CONNECT` not revoked from PUBLIC
- **Severity / Effort:** Low / S
- **Where:** `infra/postgres/init/01-schemas.sql`, `infra/scripts/create-customer-dbs.sql` (all service DBs, matching existing siblings).
- **Risk:** Any role can connect; tables are owner-only, so no data access today.
- **Fix:** `REVOKE CONNECT ON DATABASE <db> FROM PUBLIC` for every service database.
