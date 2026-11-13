# Cartwise Admin (admin-product-catalog, Phase 1F)

The platform's **Vue 3 admin SPA**. It is the first client of the whole Phase 1 request path:
browser → gateway (`:8080`) → auth-service / product-service, with a real JWT and a real
rotating refresh cookie.

Deliberately **not** a Gradle module and absent from `settings.gradle.kts` — it has its own
JavaScript toolchain (npm + Vite).

| | |
|---|---|
| Stack | Vue 3.5 (Composition API, `<script setup lang="ts">` only), TypeScript **strict**, Pinia (setup stores), Vue Router 4, Tailwind CSS 4 + daisyUI 5, Vite |
| Tests | Vitest + Vue Test Utils + jsdom |
| Dev port | **5173** (`strictPort`) |
| Backend | The gateway, via `VITE_API_BASE_URL` |

---

## The session model, which is the whole point of this phase

Three rules, none of them negotiable. They are the reason the code is shaped the way it is.

### 1. The access token lives in memory only

It is a `ref` in `src/stores/auth.ts` and it is written **nowhere else** — not `localStorage`,
not `sessionStorage`, not a cookie this app sets, not IndexedDB. An XSS that can read persistent
storage turns a 10-minute exposure into a permanent account takeover; memory-only bounds the
damage to the life of the tab.

The cost is real and accepted: a hard reload empties the store, so every cold start pays for one
silent refresh (rule 3).

`src/stores/auth.storage.spec.ts` spies on `Storage.prototype.setItem` across a full login,
a 401-triggered refresh and a logout, and fails if anything token-shaped is ever written.

### 2. The refresh token is never touched by JavaScript

It arrives as `Set-Cookie: refresh_token=...; HttpOnly; SameSite=Lax; Path=/api/auth`. There is no
code path in this app that reads `document.cookie`, because there could not be one — that is what
`HttpOnly` means. Every request sets `credentials: 'include'` so the browser attaches it; the
gateway's CORS config sets `allow-credentials: true` against the exact origin `http://localhost:5173`,
which is why the dev port is pinned.

### 3. Single-flight refresh — the most important logic in the SPA

The backend **rotates** the refresh token on every use and runs **reuse detection** on the rotated
value. So the naive "on 401, refresh" interceptor is not merely wasteful, it is wrong: with N
concurrent 401s, N−1 refreshes replay an already-consumed cookie. Inside auth-service's 10-second
grace window that is a benign `AUTH_REFRESH_RACE`; outside it, it is indistinguishable from a stolen
token and **the entire token family is revoked** — the app logs its own user out.

The implementation, in `src/stores/auth.ts`:

| Concern | Mechanism |
|---|---|
| Same tab | A **module-level, non-reactive** `let refreshPromise: Promise<string> \| null`. Concurrent callers get the *same* promise. |
| Across tabs | `navigator.locks.request('auth-refresh', ...)` (Web Locks). Two tabs hitting 401 at the same instant serialise. |
| No Web Locks | Degrades to the in-tab latch. A rare cross-tab race is recoverable; no refresh at all is not. |
| Retry storms | The retry in `src/lib/http.ts` is a single straight-line statement, not a loop — **at most one retry per original request**, structurally. |
| Recursion | `/api/auth/{login,register,refresh,logout}` never trigger a refresh-retry. |
| `AUTH_REFRESH_RACE` | Explicitly **not** treated as a logout: the family is alive, so local state is left intact. |

`src/lib/http.singleFlight.spec.ts` fires five concurrent requests that all 401 and asserts
**exactly one** `POST /api/auth/refresh`, five retries carrying the new token, and five resolved
callers.

---

## Layout

```
src/
├── types/api.ts             Wire types mirroring the published Java contracts
├── lib/
│   ├── problem.ts           RFC 9457 parsing + ApiError. ONE error path for the app.
│   ├── http.ts              fetch client: credentials, bearer, 401 -> refresh -> retry-once
│   └── oauthRedirect.ts     Post-OAuth2 destination memory (a path, never a credential)
├── stores/auth.ts           Session: memory-only token, single-flight refresh, Web Locks
├── composables/useProducts.ts   Catalog paging as a discriminated-union state machine
├── router/
│   ├── index.ts             Lazy routes
│   └── guards.ts            The ONLY authentication decision point in the app
├── components/              AppShell (navbar + logout), ProblemAlert
└── views/                   LoginView, RegisterView, AuthCallbackView, ProductsView
```

`lib/http.ts` knows nothing about Pinia, Vue or routing: the auth store registers an
`AuthProvider` with it, so the dependency runs store → http and there is no import cycle.

### Routes

| Path | Guard | Notes |
|---|---|---|
| `/` | — | redirects to `/products` |
| `/products` | `requiresAuth` | Paginated catalog: loading / empty / error / success states all implemented |
| `/login` | `guestOnly` | Email+password, client-side validation, "Sign in with Google" |
| `/register` | `guestOnly` | Disabled by default server-side; the API's own response drives the UI |
| `/auth/callback` | — | Post-Google landing: calls `/api/auth/refresh`, then enters the app |

Every route component is a dynamic import — the sign-in screen does not ship the catalog's code.

### Errors

Every failure becomes an `ApiError` carrying the parsed problem document, and the message shown is
**the server's `detail`** whenever it exists. Code-keyed friendly copy is a *fallback* for when it
does not, never an override: only the server can describe *this* occurrence. `correlationId` is
rendered next to the message, because that is the only thing that ties a screenshot to a log line.

---

## Running it

```bash
npm install
npm run dev            # http://localhost:5173
npm run test           # Vitest
npm run build          # vue-tsc --noEmit && vite build
```

Configuration (`.env`, see `.env.example`):

| Variable | Default | Notes |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Gateway origin. Nothing talks to auth/product-service directly. |
| `VITE_APP_ORIGIN` | `http://localhost:5173` | Must match the gateway's CORS allow-list. |

The backend has to be up for anything beyond the login form to work:

```bash
POSTGRES_PORT=15432 docker compose -f infra/docker-compose.yml up -d postgres
java -jar services/eureka-server/build/libs/eureka-server-0.1.0-SNAPSHOT.jar
PLATFORM_JWT_KEYSTORE_PATH=infra/secrets/jwt-keystore.p12 \
PLATFORM_JWT_KEYSTORE_PASSWORD=devkeystorepw \
PLATFORM_JWT_KEYSTORE_ALIAS=jwt-signing \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/auth_db \
java -jar services/auth-service/auth-service-app/build/libs/auth-service-app-0.1.0-SNAPSHOT.jar
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/product_db \
java -jar services/product-service/product-service-app/build/libs/product-service-app-0.1.0-SNAPSHOT.jar
java -jar services/gateway/build/libs/gateway-0.1.0-SNAPSHOT.jar
```

> If the gateway is not on `:8080` locally (another project may hold the port), set
> `VITE_API_BASE_URL` accordingly **and** update `GATEWAY_CORS_ALLOWED_ORIGINS` if the SPA moves
> off `:5173`. Those two allow-lists are exact-match and fail with browser CORS errors that name
> neither side.

---

## Testing

```bash
npm run test
```

| Spec | Proves |
|---|---|
| `lib/http.singleFlight.spec.ts` | **One** refresh for five concurrent 401s; retry-once; no refresh for auth endpoints; Web Locks used when present and degraded when absent; reuse-detection clears the session, `AUTH_REFRESH_RACE` does not |
| `stores/auth.storage.spec.ts` | The access token never reaches `localStorage`/`sessionStorage` |
| `router/guards.spec.ts` | Anonymous `/products` → `/login?redirect=/products`; page-reload + valid cookie → allowed; one silent refresh per tab; routes are lazy |
| `views/LoginView.spec.ts` | Successful login redirects (honouring `?redirect=`, rejecting off-site ones); `AUTH_INVALID_CREDENTIALS` shows the server's message and does not navigate |
| `views/ProductsView.spec.ts` | loading / empty / error / success; pagination sends the right `page`/`size`; bearer attached |
| `views/RegisterView.spec.ts` | `AUTH_REGISTRATION_DISABLED` and `AUTH_EMAIL_TAKEN` surfaced verbatim |
| `views/AuthCallbackView.spec.ts` | Cookie-only refresh on arrival, then entry into the app |
| `lib/problem.spec.ts` | `detail` beats local copy; non-problem bodies never leak to the UI |

`fetch` is replaced with a recording double (`src/test/support.ts`) rather than MSW. The
assertions here are about *how many* calls were made and *which token* each carried; a
request-level recorder states that directly, and MSW's service-worker layer would add a moving
part between the test and the property under test.

---

## Google OAuth2 — built to contract, not verified live

`GET /oauth2/authorization/google` is a **top-level navigation** (`window.location.assign`), never
a fetch: the flow redirects cross-origin to Google and back, which XHR cannot follow and CORS
blocks. auth-service completes the login by setting the refresh cookie and redirecting to
`/auth/callback` with **nothing in the URL** — no token, no code — so the callback view obtains its
first access token from an ordinary `POST /api/auth/refresh`.

No `redirect_uri` parameter is sent, because auth-service validates it against an **exact-match**
allow-list holding one entry. The in-app destination is remembered client-side in `sessionStorage`
instead (a path, not a credential).

**This flow has not been exercised against Google**: `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` are
not configured in this environment, so `/oauth2/authorization/google` returns 404 and the OAuth2
leg is not even installed in auth-service. `AuthCallbackView.spec.ts` covers both outcomes against
mocked responses, including the "no cookie was ever set" failure this environment actually produces.

---

## Dashboard shell and sections

`AppShell.vue` is a sidebar-driven dashboard, not a single flat page: a collapsible left sidebar
(264px expanded / 72px icon rail, collapse state persisted to `localStorage['sidebar-collapsed']`;
becomes a daisyUI `drawer` below 768px) plus a slim 56px sticky header over the content column. Four
sections:

- **Overview** (`/dashboard`, default landing route) — catalog summary stat cards (total/active/
  inactive), built from data `useProducts()` already fetches.
- **Products** (`/products`) — the existing create/edit/delete CRUD (unchanged from the Phase 2
  slice below), re-housed here.
- **Inventory** (`/inventory`) — read + inline quantity edit only (`useInventory.ts`/
  `useInventoryMutations.ts`, mirroring the Products composable pattern), gated by
  `auth.canManageCatalog`. Deliberately does not expose create/delete: inventory rows are normally
  event-provisioned from product lifecycle (`docs/HLD-event-driven-inventory.md`), and manual
  create/delete has no specified workflow behind it yet — added only once a real ops requirement
  exists.
- **Settings** (`/settings`) — session info (signed-in-as, roles, sign-out) and the theme toggle,
  relocated from the old header. No other settings exist in this app yet; the page says so rather
  than presenting rows that submit nowhere.

## Product CRUD (Phase 2 slice)

`ProductsView` has full create/edit/delete, backed by product-service's write endpoints
(`docs/HLD-event-driven-inventory.md`). Create is a dedicated page (`NewProductView.vue`, routed at
`/products/new`) rather than a modal — bookmarkable/back-button-navigable, and it's the flow most
likely to want room to grow (e.g. more fields later). Edit stays a modal (`ProductFormModal.vue`,
SKU locked — it's immutable server-side), since that flow benefits from staying anchored to the row
it's changing; Delete opens the generic `ConfirmDialog`. All three are gated by
`auth.canManageCatalog` (`CATALOG_MANAGER`/`ADMIN` role) — a `VIEWER` sees the read-only table with
no write affordances, and `/products/new` itself redirects away for that role too (route-level
`requiresAnyRole`), matching the backend's own RBAC rather than duplicating a policy that could
drift from it. Edit sends the `version` last read; a stale value surfaces as a `409` with a "someone
else changed this" message rather than silently overwriting a concurrent edit.

## Design system: minimalist, light/dark theme

`src/style.css` defines two daisyUI 5 CSS-based themes (`catalogLight` default, `catalogDark`,
following `prefers-color-scheme` on first visit) — near-monochrome surfaces with exactly **one**
functional accent color (used only for links/focus rings/primary actions/active-nav state, never
decoratively), hairline 1px borders instead of shadows or blur for depth, and no decorative
background layer at all (an earlier glassmorphism pass needed one for its blur to read as
translucent; this design has nothing to blur). Reusable classes: `.data-card` (the default panel
wrapper — tables, non-modal content), `.data-card-elevated` (adds one soft shadow, for
modals/dropdowns only), `.app-sidebar`/`.nav-item`/`.nav-item-active`/`.app-header` (shell chrome),
`.stat-card` (dashboard KPI cards), `.status-dot` + modifiers (dot+label status, not filled badge
pills), `.table-minimal` (hairline row dividers, no zebra striping). Typography: Inter (UI/body),
JetBrains Mono (SKUs/prices/quantities/IDs — loaded via `index.html`, referenced through
`--font-sans`/`--font-mono`). Icons: `@lucide/vue`. Theme choice persists to `localStorage['theme']`,
applied before first paint via an inline script in `index.html` (avoids a flash of the wrong theme),
with `useTheme.ts`/`ThemeToggle.vue` handling in-app switching.

## Out of scope for Phase 1F (deliberately)

User management, i18n, E2E framework (Playwright/Cypress), PWA/service worker, and any state
persistence beyond the in-memory session above.
