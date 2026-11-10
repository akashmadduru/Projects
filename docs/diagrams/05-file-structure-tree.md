# 5. File Structure Tree

## Directory Structure with File Types

```
admin-product-catalog/
├── 📄 package.json ..................... NPM metadata, dependencies
├── 📄 package-lock.json ............... Locked versions
├── 📄 tsconfig.json ................... TypeScript compiler config
├── 📄 vite.config.ts ................. Vite build config
├── 📄 vitest.config.ts ............... Test runner config
├── 📄 env.d.ts ....................... TypeScript environment types
├── 📄 index.html ..................... Entry point, theme bootstrap
├── 📄 .env.example ................... Environment variable template
├── 📄 .gitignore ..................... Git ignore patterns
├── 📄 README.md ...................... Project documentation
│
├── 📁 src/
│   ├── 📄 main.ts ................... App bootstrap, Pinia + Router
│   ├── 📄 style.css ................. Tailwind + daisyUI themes
│   ├── 📄 App.vue ................... Root component (RouterView only)
│   │
│   ├── 📁 router/
│   │   ├── 📄 index.ts .............. Route definitions, lazy imports
│   │   ├── 📄 guards.ts ............ Auth guards, role checks
│   │   └── 📄 guards.spec.ts ....... Unit tests for guards
│   │
│   ├── 📁 views/  ................... Pages (lazy-loaded route components)
│   │   ├── 📄 LoginView.vue ........ Email/password login
│   │   ├── 📄 LoginView.spec.ts .... Unit tests
│   │   ├── 📄 RegisterView.vue ..... New account registration
│   │   ├── 📄 RegisterView.spec.ts . Unit tests
│   │   ├── 📄 AuthCallbackView.vue . OAuth2 callback handler
│   │   ├── 📄 AuthCallbackView.spec.ts . Unit tests
│   │   ├── 📄 DashboardView.vue .... Welcome/metrics dashboard
│   │   ├── 📄 ProductsView.vue ..... Product listing & inline edit
│   │   ├── 📄 ProductsView.spec.ts . Unit tests
│   │   ├── 📄 NewProductView.vue ... Create product (standalone page)
│   │   ├── 📄 NewProductView.spec.ts . Unit tests
│   │   ├── 📄 InventoryView.vue .... Stock management
│   │   └── 📄 SettingsView.vue ..... User settings & logout
│   │
│   ├── 📁 components/ ............... Reusable components
│   │   ├── 📄 AppShell.vue ......... Main layout (sidebar + header)
│   │   ├── 📄 ThemeToggle.vue ...... Light/dark mode toggle
│   │   ├── 📄 ProductFormModal.vue  . Create/edit product modal
│   │   ├── 📄 InventoryFormModal.vue . Stock level editor modal
│   │   ├── 📄 ProblemAlert.vue ..... Error display (RFC 9457)
│   │   └── 📄 ConfirmDialog.vue .... Delete confirmation modal
│   │
│   ├── 📁 stores/ .................. Pinia store (state management)
│   │   ├── 📄 auth.ts .............. Session, JWT, refresh token
│   │   ├── 📄 auth.storage.spec.ts . Storage behavior tests
│   │   └── 📄 auth.canManageCatalog.spec.ts . Permission logic tests
│   │
│   ├── 📁 composables/ ............. Composition API functions (hooks)
│   │   ├── 📄 useProducts.ts ....... Paginated product list
│   │   ├── 📄 useProductMutations.ts . Create/update/delete product
│   │   ├── 📄 useInventory.ts ...... Stock list pagination
│   │   ├── 📄 useInventoryMutations.ts . Stock level updates
│   │   └── 📄 useTheme.ts ......... Light/dark mode state
│   │
│   ├── 📁 lib/ ..................... Utility functions & HTTP client
│   │   ├── 📄 http.ts ............. Single-flight HTTP, auth, retry
│   │   ├── 📄 http.singleFlight.spec.ts . Cache dedup tests
│   │   ├── 📄 problem.ts .......... RFC 9457 error parsing
│   │   ├── 📄 problem.spec.ts ..... Error formatting tests
│   │   └── 📄 oauthRedirect.ts .... OAuth2 flow helpers
│   │
│   ├── 📁 types/ ................... TypeScript interfaces
│   │   └── 📄 api.ts .............. All API wire types (request/response)
│   │
│   └── 📁 test/
│       └── 📄 support.ts .......... Test utilities, mocks, fixtures
│
└── 📁 node_modules/ ................. Dependencies (not in Git)
```

## File Count Breakdown

| Category | Count | Examples |
|----------|-------|----------|
| **Views (Pages)** | 8 | LoginView, ProductsView, InventoryView, ... |
| **Components** | 6 | AppShell, ThemeToggle, ProductFormModal, ... |
| **Composables** | 5 | useProducts, useProductMutations, useTheme, ... |
| **Stores (Pinia)** | 1 | useAuthStore |
| **Utilities (lib/)** | 5 | http.ts, problem.ts, oauthRedirect.ts, ... |
| **Tests (*.spec.ts)** | 8 | guards.spec.ts, auth.spec.ts, problem.spec.ts, ... |
| **Configuration** | 7 | package.json, tsconfig.json, vite.config.ts, ... |
| **Types** | 1 | api.ts (all request/response contracts) |
| **Total Source Files** | **26** | TypeScript, Vue, CSS only |

## Layer Organization

### Presentation Layer (Views + Components)
- **Views:** Each route has its own component, lazy-loaded
- **Components:** Reusable UI pieces (modals, alerts, toggles)
- **Responsibility:** Render UI, handle user events, call composables/stores

### Logic Layer (Composables)
- **Composables:** Pure data/state management functions
- **Responsibility:** Fetch data, manage pagination, handle mutations, coordinate API calls
- **Pattern:** Composition API (Vue 3 hooks)

### State Layer (Stores)
- **Pinia stores:** Single source of truth for session, user, theme
- **Responsibility:** Centralized state (auth token, user profile, app settings)
- **Access:** Direct import/call (no providers)

### HTTP Layer (lib/http.ts)
- **Responsibility:** Single-flight dedup, JWT attachment, 401 refresh, RFC 9457 error parsing
- **Access:** Called by composables only (not directly from components)

### Type Definitions (types/api.ts)
- **Responsibility:** All wire types mirroring backend contracts
- **Scope:** Request bodies, response envelopes, error documents
- **NOT:** Component props, internal state types (defined inline in .ts files)

## Key Design Decisions

### 1. No Shared State Providers
- No `provide()` in App.vue
- No Context API equivalent
- Stores are imported directly where needed
- Simpler testing (no provider setup)

### 2. Lazy-Loaded Routes
Every route except login is code-split:
```javascript
component: () => import('@/views/ProductsView.vue')
```
Result: Anonymous user never downloads ProductsView's bundle.

### 3. Single `api.ts` Type File
All request/response types live in one file for:
- Single source of truth
- Easy audit of wire contracts
- Prevents prop drilling of types

### 4. Test Colocated with Source
`guards.ts` + `guards.spec.ts` in same directory
Result: Easier to find and maintain tests

### 5. `src/` Root Alias (`@/`)
tsconfig.json registers `@` as root for all imports:
```typescript
import { useProducts } from '@/composables/useProducts'; // not ../../../...
```

## File Size Estimates

| File | Lines | Purpose |
|------|-------|---------|
| `lib/http.ts` | ~100 | HTTP client + auth + single-flight |
| `stores/auth.ts` | ~250 | Session management, token refresh |
| `composables/useProducts.ts` | ~100 | Pagination + fetch |
| `composables/useProductMutations.ts` | ~80 | CRUD mutations |
| `views/ProductsView.vue` | ~150 | Product list page |
| `components/ProductFormModal.vue` | ~180 | Create/edit modal |
| `router/index.ts` | ~70 | Route definitions |
| `types/api.ts` | ~250 | All API contracts |
| **Total** | **~1,600** | Whole app |

## Import Dependencies

### No Cycles

```
Views → Composables → Stores (one-directional)
Components → Stores (direct)
lib/http.ts → lib/problem.ts (utility layer)
```

### Example Import Chain

**ProductsView.vue**
```typescript
import { useProducts } from '@/composables/useProducts'
import { useProductMutations } from '@/composables/useProductMutations'
import { useAuthStore } from '@/stores/auth'
import type { Product } from '@/types/api'
```

**useProducts.ts**
```typescript
import { apiFetch } from '@/lib/http'
import { ApiError } from '@/lib/problem'
import type { PagedModel, Product } from '@/types/api'
```

**lib/http.ts**
```typescript
import { ApiError, apiErrorFrom } from '@/lib/problem'
// No imports from stores or composables
```

### Test Setup

**guards.spec.ts** (testing router guards)
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createAuthGuard } from './guards'
import { useAuthStore } from '@/stores/auth'

// Mock the store
vi.mock('@/stores/auth')

// Tests...
```

## Tailwind + daisyUI CSS Architecture

**src/style.css**
```css
@import 'tailwindcss';  /* Tailwind base + utilities */

@plugin "daisyui" {      /* Enable daisyUI components */
  themes: false;         /* Don't load stock themes */
}

@plugin "daisyui/theme" {  /* Register catalogLight theme */
  name: 'catalogLight';
  --color-base-100: oklch(...);
  ...
}

@plugin "daisyui/theme" {  /* Register catalogDark theme */
  name: 'catalogDark';
  --color-base-100: oklch(...);
  ...
}

@layer components {      /* Custom components */
  .app-sidebar { ... }
  .nav-item { ... }
  .stat-card { ... }
}
```

**index.html**
```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <script>
      // Bootstrap script runs BEFORE styles load
      const theme = localStorage.getItem('theme') ?? 'catalogLight';
      document.documentElement.dataset.theme = theme;
    </script>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

This ensures no FOUC (Flash of Unstyled Content) on reload.

