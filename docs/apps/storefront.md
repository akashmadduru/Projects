# Storefront (`apps/product-catalog`)

The customer-facing SPA, branded "Cartwise". React 19, Vite 6, TypeScript 5, Tailwind 4, Zustand 5,
React Router 7; tests use Vitest and MSW. Rewritten from the code on 2026-11-14. The previous README
described a much earlier version.

## Run it

```bash
cd apps/product-catalog
npm install
npm run dev          # http://localhost:3000 (strictPort; /api is proxied to the gateway on :8080)
npm run build        # tsc -p tsconfig.build.json && vite build (excludes tests from type-checking)
npm run type-check   # tsc --noEmit, including tests
npm run lint
npm test             # vitest
```

Port 3000 is pinned because it is an allow-listed Google sign-in callback origin. If it is taken,
free it; do not move the app to another port.

## Environment variables

| Variable | Default | Use |
|---|---|---|
| `VITE_AUTH_BASE_URL` | `http://localhost:8081` | Where "Continue with Google" starts. It goes straight to auth-service, not through the gateway (see [Phase 5 D4](../phases/phase-05-customer-accounts.md#decisions)). |
| `VITE_GOOGLE_SSO_ENABLED` | on | Set to `false` to hide the Google button. |
| `VITE_ADMIN_URL` | — | Link to the admin console / seller portal. |

## Layout

```
src/
├── pages/        route components; pages/account/* is the signed-in account area, pages/help/* the help centre
├── components/   UI building blocks (cards, gallery, filters, checkout panels, chat widget, cookie banner…)
├── services/     one API client per backend area (authApi, cartApi, orderApi, paymentApi, sellerApi, supportApi, trustApi…)
├── stores/       Zustand stores: auth, cart, wishlist, compare, filter, pagination, theme, ui
├── hooks/        data hooks (useProducts, useFacets, useDeals…) and UI hooks
├── lib/          http client, consent, i18n, currency, push, theme, recently viewed, redirect helpers
├── locales/      translations (hi.ts)
├── router/       route table
├── types/        API and domain types
└── test/         MSW handlers, fixtures, test setup
public/           manifest.webmanifest, sw.js, offline.html, icons, robots.txt
```

## Routes

| Area | Paths |
|---|---|
| Browse | `/`, `/products/:id`, `/p/:slug`, `/seller/:slug`, `/compare`, `/wishlist`, `/lists/shared/:token` |
| Help | `/help`, `/help/:slug` |
| Auth | `/login`, `/register`, `/forgot-password`, `/reset-password`, `/verify-email`, `/auth/callback` |
| Purchase | `/checkout`, `/orders/:id` |
| Account (`/account/…`) | `profile`, `addresses`, `orders`, `recent`, `alerts`, `wallet`, `gift-cards`, `coins`, `membership`, `referrals`, `subscriptions`, `lists`, `buy-again`, `notifications`, `notification-settings`, `security`, `privacy`, `support`, `support/new`, `support/:id`, `settings` |

## How it talks to the backend

- **Session:** the access token is kept in memory only; the refresh token is an HttpOnly cookie. On a
  401 the client makes one single-flight `POST /api/auth/refresh` and replays the request once.
  Details: [Phase 5](../phases/phase-05-customer-accounts.md#how-it-works).
- **Errors:** the backend returns RFC 9457 problem+json; `lib/problem.ts` and `lib/errors.ts` map it to UI messages.
- **Checkout** sends an `Idempotency-Key` that it keeps across retries.
- **PWA:** `public/sw.js` and `offline.html` (Phase 10); web push through `lib/push.ts` (Phase 8).

## Known issues

- Tests: 10 failing, plus `tsc --noEmit` errors in `http.test.ts` and `OverviewPage.test.tsx`
  ([CI/CD baseline](../guides/ci-cd.md#baseline-to-clear-before-ci_enforcetrue)).
- The main chunk is large ([TD-P7-18](../techdebt/phase7.md#td-p7-18)).
- More: [techdebt/frontend.md](../techdebt/frontend.md).
