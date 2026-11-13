# React Product Catalog

A modern, customer-facing product catalog SPA built with React 19, Vite, TailwindCSS, and Zustand.

## Features

- **Product Browsing** — Paginated catalog view of all products
- **Product Details** — Individual product pages with inventory status
- **Dark/Light Theme** — Toggle between light and dark modes with localStorage persistence
- **Responsive Design** — Mobile-first layout (1-4 columns based on viewport)
- **Error Handling** — RFC 9457 problem documents with correlation IDs
- **Single-Flight Deduplication** — Prevents duplicate concurrent API calls
- **JWT Authentication** — Automatic token refresh on 401 responses

## Tech Stack

- **React 19** — Latest React with Suspense and automatic batching
- **Vite 6** — Lightning-fast build tool and dev server
- **TailwindCSS 4.1** — Utility-first CSS with custom theme tokens
- **Zustand 5** — Lightweight state management (theme, auth, pagination)
- **TypeScript 5** — Strict mode, full type safety
- **React Router 7** — Client-side routing
- **Vitest** — Unit testing with jsdom and Mock Service Worker
- **MSW 2** — Mock Service Worker for API mocking in tests

## Getting Started

### Prerequisites

- Node.js 20+ (LTS recommended)
- npm or yarn

### Installation

```bash
cd apps/product-catalog
npm install
```

### Development

Run the Vite dev server with hot-reload:

```bash
npm run dev
```

The app will be available at **http://localhost:5174** (Vite default, or shown in terminal).

**Note:** The dev server is configured to proxy `/api/*` requests to `http://localhost:8080` (the API gateway). Make sure the backend services are running:

```bash
# In another terminal
docker compose -f infra/docker-compose.yml up -d
```

### Building

Build the production bundle:

```bash
npm run build
```

Output is in `dist/` directory (~75KB gzipped).

### Testing

Run unit tests with watch mode:

```bash
npm run test
```

Run tests once (CI mode):

```bash
npm run test -- --run
```

Generate coverage report:

```bash
npm run test -- --coverage
```

### Type Checking

Check for TypeScript errors:

```bash
npm run type-check
```

### Code Quality

Lint and fix code:

```bash
npm run lint
npm run lint -- --fix
```

---

## Project Structure

```
apps/product-catalog/
├── src/
│   ├── stores/           # Zustand stores (theme, auth, pagination)
│   ├── components/       # Reusable UI components
│   ├── pages/            # Route-level pages
│   ├── hooks/            # Custom React hooks
│   ├── services/         # API client wrappers
│   ├── lib/              # Utilities (HTTP, theme, errors)
│   ├── types/            # TypeScript types and interfaces
│   ├── test/             # Testing utilities, mocks, fixtures
│   ├── router/           # React Router configuration
│   ├── App.tsx           # Root component
│   ├── main.tsx          # React entry point
│   └── style.css         # Global styles, Tailwind directives
│
├── public/               # Static assets (favicon, etc.)
├── index.html            # HTML entry point
├── package.json          # Dependencies and scripts
├── tsconfig.json         # TypeScript configuration
├── vite.config.ts        # Vite build config (React plugin, dev proxy)
├── tailwind.config.ts    # TailwindCSS theme tokens
├── vitest.config.ts      # Test runner configuration
├── Dockerfile            # Multi-stage Docker build for production
├── .dockerignore          # Files excluded from Docker build
└── README.md             # This file
```

---

## Key Files & What They Do

### State Management (Zustand Stores)

- **`useThemeStore`** — Light/dark mode toggle, localStorage persistence
- **`useAuthStore`** — JWT token management, automatic sync with HTTP client
- **`usePaginationStore`** — Current page and page size for catalog

### Custom Hooks

- **`useProducts(pageSize)`** — Fetch paginated products, returns state (loading/error/success)
- **`useTheme()`** — Access theme mode, toggle, setMode
- **`useApi()`** — Access HTTP client and token management

### API Client

- **`apiFetch(path, options)`** — HTTP client with:
  - Single-flight deduplication (identical concurrent requests share one Promise)
  - JWT token injection in `Authorization: Bearer` header
  - Automatic 401 token refresh with single-flight dedup
  - RFC 9457 problem document parsing
  - Response body caching for GET requests

### Components

- **`ProductCard`** — React.memo'd card (name, price, image, stock status)
- **`ProductGrid`** — Responsive 1-4 column grid layout
- **`Pagination`** — Prev/next buttons and page indicator
- **`Header`** — Logo + ThemeToggle
- **`ErrorBoundary`** — Catches render-time exceptions
- **`ErrorAlert`** — Displays API errors with correlation IDs
- **`Skeleton`** — Loading placeholder with pulse animation

### Theme System

- **`src/lib/theme.ts`** — Color constants (light/dark palettes), CSS variable names
- **`src/style.css`** — CSS custom properties (--color-bg, --color-text, etc.)
- **`tailwind.config.ts`** — TailwindCSS config extending CSS variables
- **`[data-theme="dark"]`** selector switches palettes on `<html>` element

---

## Environment Variables

### Development

Create `.env.local` (gitignored) for local overrides:

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_APP_ORIGIN=http://localhost:5174
```

These are optional; defaults work with the backend running at `http://localhost:8080`.

### Production (Docker)

No environment variables needed; the built app runs as static files served by Node.js http-server.

---

## Authentication Flow

1. **Login** — User submits credentials at `/api/auth/login` (via gateway)
2. **Token Issuance** — Auth-service returns access token + refresh token (HttpOnly cookie)
3. **API Calls** — React app includes access token in `Authorization: Bearer` header
4. **Token Refresh** — On 401, apiFetch automatically calls `/api/auth/refresh` (single-flight dedup)
5. **Retry** — Original request retried with fresh token
6. **Logout** — Browser clears refresh cookie, app clears token from Zustand store

---

## Error Handling

All API errors are mapped to RFC 9457 problem documents:

```typescript
{
  "type": "about:blank",
  "status": 404,
  "code": "RESOURCE_NOT_FOUND",
  "detail": "Product not found",
  "instance": "GET /api/products/unknown-id"
}
```

Errors are caught and displayed with:
- Human-readable messages (`detail` field)
- Machine-readable codes (`code` field)
- Correlation IDs for support triage (in `X-Correlation-Id` header)

See `src/components/ErrorAlert.tsx` for UI display.

---

## Testing

### Unit Tests

Tests use Vitest + jsdom + Mock Service Worker (MSW):

```bash
npm run test -- --watch
```

Fixtures in `src/test/fixtures.ts` provide realistic API responses.

MSW handlers in `src/test/msw.ts` intercept `/api/products/**` requests.

### Example Test

```typescript
it('should render product grid', async () => {
  render(<ProductCatalogPage />);
  
  // MSW intercepts GET /api/products, returns fixture data
  const cards = await screen.findAllByRole('heading', { name: /product name/i });
  expect(cards).toHaveLength(20);
});
```

---

## Performance

- **Bundle Size** — ~75KB gzipped (React 40KB, TailwindCSS 12KB, app 25KB)
- **Single-Flight Dedup** — Prevents wasteful duplicate requests
- **ProductCard.memo** — Skips re-renders when props unchanged
- **Lazy Image Loading** — `loading="lazy"` attribute on images
- **CSS Variable Theme** — No React re-renders on dark/light toggle

---

## Docker

### Build Image

```bash
docker build -t ecommerce-product-catalog:latest apps/product-catalog/
```

### Run Container

```bash
docker run -p 3000:3000 ecommerce-product-catalog:latest
```

Access at **http://localhost:3000**.

### Docker Compose

The full stack (backend + React app) runs via:

```bash
docker compose -f infra/docker-compose.yml up -d
```

See `DOCKER_SETUP.md` in the project root for full instructions.

---

## Future Enhancements

- **Search & Filter** — Full-text search on product name/SKU
- **Product Detail Page** — Full images, specs, inventory status
- **Related Products** — "Customers also viewed" section
- **Shopping Cart** — Add to cart, checkout flow (Phase 3)
- **Reviews & Ratings** — Customer reviews on product detail
- **Server-Side Rendering** — Next.js for SEO optimization
- **Analytics** — Sentry error tracking, Web Vitals monitoring
- **PWA** — Service worker for offline catalog browsing

---

## Troubleshooting

### `npm install` Fails

Clear npm cache and reinstall:

```bash
npm cache clean --force
rm -rf node_modules package-lock.json
npm install
```

### `npm run dev` Shows "Port 5174 is in use"

Change the port:

```bash
VITE_PORT=5175 npm run dev
```

Or kill the process using that port.

### Backend API Calls Fail with CORS Error

1. Check the gateway is running: `curl http://localhost:8080/actuator/health`
2. Check your origin is in gateway's `GATEWAY_CORS_ALLOWED_ORIGINS` (in `infra/.env`)
3. Verify the dev server proxy is set up (check `vite.config.ts`)

### Dark Mode Toggle Doesn't Persist

Check that localStorage is enabled:

```javascript
// In browser console
localStorage.setItem('test', '1'); // Should work
localStorage.getItem('theme'); // Check stored theme
```

---

## Contributing

1. Create a feature branch: `git checkout -b feature/my-feature`
2. Make changes and run tests: `npm run test`
3. Lint and type-check: `npm run lint && npm run type-check`
4. Commit with a clear message
5. Push and create a pull request

---

## License

Part of the eCommerce Platform. See LICENSE in project root.
