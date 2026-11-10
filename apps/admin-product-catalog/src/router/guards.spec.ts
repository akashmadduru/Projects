import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { RouteLocationNormalized, RouteRecordRaw } from 'vue-router';

import { routes } from '@/router';
import { createAuthGuard } from '@/router/guards';
import { useAuthStore } from '@/stores/auth';
import {
  installFetch,
  jsonResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
  tokenResponse,
} from '@/test/support';

/**
 * The guard is exercised directly rather than through a mounted router: the
 * decision, not the navigation machinery, is what carries the security weight,
 * and calling it directly lets the test assert the exact redirect location —
 * including the `redirect` query the user is bounced back to after signing in.
 */

function targetRoute(fullPath: string, meta: Record<string, unknown> = {}): RouteLocationNormalized {
  // Only the members the guard reads are supplied. The cast documents that the
  // fixture is deliberately partial instead of fabricating a whole route object.
  return { fullPath, path: fullPath, meta } as unknown as RouteLocationNormalized;
}

function childrenOf(record: RouteRecordRaw | undefined): readonly RouteRecordRaw[] {
  return record !== undefined && 'children' in record ? (record.children ?? []) : [];
}

describe('authentication route guard', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    resetModuleState();
  });

  afterEach(() => {
    resetModuleState();
  });

  it('protects /products in the real router configuration', () => {
    const shell = routes.find((route) => route.path === '/');
    const products = childrenOf(shell).find((child) => child.path === 'products');

    expect(products).toBeDefined();
    expect(products?.meta?.requiresAuth).toBe(true);
  });

  it('lazy-loads every route component', () => {
    const componentFactories = [...routes, ...childrenOf(routes.find((r) => r.path === '/'))]
      .map((record) => ('component' in record ? record.component : undefined))
      .filter((component) => component !== undefined);

    expect(componentFactories.length).toBeGreaterThan(0);
    for (const component of componentFactories) {
      // A dynamic import is a function; an eagerly imported SFC is an object.
      expect(typeof component).toBe('function');
    }
  });

  it('redirects an anonymous visitor to /login and remembers where they were going', async () => {
    installFetch(async () =>
      problemResponse(401, 'AUTH_REFRESH_INVALID', 'No refresh token was presented.'),
    );
    const guard = createAuthGuard();

    const result = await guard(targetRoute('/products', { requiresAuth: true }));

    expect(result).toEqual({ path: '/login', query: { redirect: '/products' } });
    expect(useAuthStore().isAuthenticated).toBe(false);
  });

  it('allows access after a page reload when the refresh cookie is still valid', async () => {
    // The page-reload scenario exactly: the Pinia store is empty because the
    // access token was memory-only, but the HttpOnly cookie survived.
    const recorder = installFetch(async (request) => {
      if (request.url.includes('/api/auth/refresh')) {
        return jsonResponse(200, tokenResponse('token-from-silent-refresh'));
      }
      if (request.url.includes('/api/auth/me')) {
        return jsonResponse(200, TEST_USER);
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
    });

    const auth = useAuthStore();
    expect(auth.accessToken).toBeNull();

    const result = await createAuthGuard()(targetRoute('/products', { requiresAuth: true }));

    expect(result).toBe(true);
    expect(auth.accessToken).toBe('token-from-silent-refresh');
    expect(auth.user?.email).toBe(TEST_USER.email);
    expect(recorder.countTo('/api/auth/refresh')).toBe(1);
  });

  it('attempts the silent refresh only once per tab', async () => {
    const recorder = installFetch(async () =>
      problemResponse(401, 'AUTH_REFRESH_INVALID', 'No refresh token was presented.'),
    );
    const guard = createAuthGuard();
    const to = targetRoute('/products', { requiresAuth: true });

    await guard(to);
    await guard(to);
    await guard(to);

    // Three guarded navigations, one doomed refresh: an anonymous visitor must
    // not hammer the gateway on every click.
    expect(recorder.countTo('/api/auth/refresh')).toBe(1);
  });

  it('keeps an authenticated user away from the sign-in screen', async () => {
    installFetch(async () => jsonResponse(200, {}));
    const auth = useAuthStore();
    auth.accessToken = 'live-token';

    const result = await createAuthGuard()(targetRoute('/login', { guestOnly: true }));

    expect(result).toEqual({ path: '/dashboard' });
  });

  it('lets an unauthenticated visitor reach a public route untouched', async () => {
    const recorder = installFetch(async () => jsonResponse(200, {}));

    const result = await createAuthGuard()(targetRoute('/login', { guestOnly: true }));

    expect(result).toBe(true);
    expect(recorder.calls).toHaveLength(0);
  });
});
