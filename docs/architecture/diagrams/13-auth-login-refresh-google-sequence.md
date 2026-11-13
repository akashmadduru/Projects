# 13. Storefront Login, Refresh and Google SSO

Source: `apps/product-catalog/src/lib/http.ts`, `stores/authStore.ts`, `services/authApi.ts`, `pages/AuthCallbackPage.tsx`, `lib/redirect.ts`.

The access token is held in memory only. The refresh token is an HttpOnly cookie sent via `credentials: 'include'`.

## Email/password login and silent refresh

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant SPA as Storefront (3000)
    participant GW as Gateway (8080)
    participant AU as auth-service (8081)
    participant CS as cart-service

    U->>SPA: submit email + password
    SPA->>GW: POST /api/auth/login
    GW->>AU: forward
    AU-->>SPA: 200 {accessToken} + Set-Cookie refresh (HttpOnly)
    SPA->>GW: GET /api/auth/me (Bearer)
    SPA->>CS: POST /api/cart/merge, /api/wishlist/merge (local items)
    CS-->>SPA: server cart (replaces local copy)

    Note over SPA,GW: Later, the access token expires
    SPA->>GW: GET /api/cart (Bearer expired)
    GW-->>SPA: 401
    SPA->>GW: POST /api/auth/refresh (cookie) - single-flight
    AU-->>SPA: 200 {accessToken} + rotated cookie
    SPA->>GW: replay GET /api/cart once (same headers)
    GW-->>SPA: 200

    Note over SPA: If refresh fails: forceLogout, redirect /login?next=
```

## Google SSO

The flow starts directly at auth-service on 8081, not through the gateway (see DecisionLog D4).

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant SPA as Storefront (3000)
    participant AU as auth-service (8081)
    participant G as Google

    U->>SPA: click "Continue with Google"
    SPA->>SPA: sessionStorage pc.post-login-redirect = next
    SPA->>AU: navigate GET http://localhost:8081/oauth2/authorization/google?redirect_uri=http://localhost:3000/auth/callback
    AU->>AU: check redirect_uri against AUTH_ALLOWED_REDIRECT_URIS
    AU-->>G: 302 authorize (redirect_uri=http://localhost:8081/login/oauth2/code/google)
    G-->>AU: 302 /login/oauth2/code/google?code=...
    AU->>AU: resolve/create user, set refresh cookie
    AU-->>SPA: 302 http://localhost:3000/auth/callback (no token in URL)
    SPA->>AU: POST /api/auth/refresh (cookie) via bootstrap
    AU-->>SPA: {accessToken}
    SPA->>SPA: GET /api/auth/me, merge cart, navigate to remembered path
    Note over SPA: On error: /auth/callback?error=AUTH_* -> /login?error=...
```
