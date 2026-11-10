import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { apiFetch } from '@/lib/http';
import { ApiError } from '@/lib/problem';
import { REFRESH_LOCK_NAME, useAuthStore } from '@/stores/auth';
import {
  installFetch,
  jsonResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
  tokenResponse,
  type FetchRecorder,
} from '@/test/support';

/**
 * THE most important test in this app.
 *
 * The backend rotates the refresh token on every use and runs reuse detection on
 * the rotated value. If N concurrent 401s each fired their own refresh, N-1 of
 * them would be replaying an already-consumed cookie — harmless inside the 10s
 * grace window, and a full family revocation (i.e. a forced logout of a legitimate
 * user) outside it. "Exactly one refresh" is a correctness property, not an
 * optimisation.
 */

const emptyPage = {
  content: [],
  page: { size: 20, number: 0, totalElements: 0, totalPages: 0 },
};

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe('single-flight refresh', () => {
  let recorder: FetchRecorder;
  let refreshCalls: number;

  beforeEach(() => {
    setActivePinia(createPinia());
    resetModuleState();
    refreshCalls = 0;
  });

  afterEach(() => {
    resetModuleState();
  });

  function arrangeHappyRefresh(): void {
    recorder = installFetch(async (request) => {
      if (request.url.includes('/api/auth/refresh')) {
        refreshCalls += 1;
        // Real latency: without it the first refresh would resolve before the
        // other four requests ever reached the latch, and the test would pass
        // for the wrong reason.
        await delay(10);
        return jsonResponse(200, tokenResponse('fresh-token'));
      }
      if (request.url.includes('/api/products')) {
        return request.authorization === 'Bearer fresh-token'
          ? jsonResponse(200, emptyPage)
          : problemResponse(401, 'UNAUTHENTICATED', 'Authentication is required.');
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'No stub for ' + request.url);
    });
  }

  it('fires exactly ONE refresh for five concurrent 401s and retries all five', async () => {
    arrangeHappyRefresh();
    const auth = useAuthStore();
    auth.accessToken = 'stale-token';

    const results = await Promise.all(
      Array.from({ length: 5 }, () => apiFetch<typeof emptyPage>('/api/products')),
    );

    // One refresh. Not five, not two.
    expect(refreshCalls).toBe(1);
    expect(recorder.countTo('/api/auth/refresh')).toBe(1);

    // Five originals + five retries, and nothing else.
    expect(recorder.countTo('/api/products')).toBe(10);

    const productCalls = recorder.callsTo('/api/products');
    expect(productCalls.filter((call) => call.authorization === 'Bearer stale-token')).toHaveLength(
      5,
    );
    expect(productCalls.filter((call) => call.authorization === 'Bearer fresh-token')).toHaveLength(
      5,
    );

    // Every original request resolved with real data, not an error.
    expect(results).toHaveLength(5);
    for (const result of results) {
      expect(result.page.size).toBe(20);
    }

    // The new token was adopted for subsequent work.
    expect(auth.accessToken).toBe('fresh-token');
  });

  it('sends credentials on every request so the HttpOnly refresh cookie travels', async () => {
    arrangeHappyRefresh();
    const auth = useAuthStore();
    auth.accessToken = 'stale-token';

    await apiFetch('/api/products');

    expect(recorder.calls.length).toBeGreaterThan(0);
    for (const call of recorder.calls) {
      expect(call.credentials).toBe('include');
    }
  });

  it('retries at most once — a still-401 endpoint surfaces as a real failure', async () => {
    recorder = installFetch(async (request) => {
      if (request.url.includes('/api/auth/refresh')) {
        refreshCalls += 1;
        return jsonResponse(200, tokenResponse('fresh-token'));
      }
      // Never accepts any token: models a genuine authorization failure rather
      // than an expired one.
      return problemResponse(401, 'UNAUTHENTICATED', 'Authentication is required.');
    });
    const auth = useAuthStore();
    auth.accessToken = 'stale-token';

    await expect(apiFetch('/api/products')).rejects.toBeInstanceOf(ApiError);

    expect(refreshCalls).toBe(1);
    expect(recorder.countTo('/api/products')).toBe(2); // original + exactly one retry
  });

  it('never refreshes in response to a failed login, register or refresh', async () => {
    recorder = installFetch(async (request) => {
      if (request.url.includes('/api/auth/refresh')) {
        refreshCalls += 1;
        return jsonResponse(200, tokenResponse('fresh-token'));
      }
      return problemResponse(401, 'AUTH_INVALID_CREDENTIALS', 'Invalid email or password.');
    });
    const auth = useAuthStore();

    await expect(auth.login({ email: 'a@b.co', password: 'wrong-password' })).rejects.toBeInstanceOf(
      ApiError,
    );

    // A rejected login is not an expired session; refreshing here would recurse.
    expect(refreshCalls).toBe(0);
    expect(recorder.countTo('/api/auth/login')).toBe(1);
  });

  it('uses the Web Locks API to serialise refresh across tabs when available', async () => {
    const lockNames: string[] = [];
    const request = vi.fn(async <T>(name: string, work: () => Promise<T>): Promise<T> => {
      lockNames.push(name);
      return work();
    });
    Object.defineProperty(globalThis.navigator, 'locks', {
      value: { request },
      configurable: true,
    });

    try {
      arrangeHappyRefresh();
      const auth = useAuthStore();
      auth.accessToken = 'stale-token';

      await Promise.all(Array.from({ length: 3 }, () => apiFetch('/api/products')));

      expect(lockNames).toEqual([REFRESH_LOCK_NAME]);
      expect(refreshCalls).toBe(1);
    } finally {
      Reflect.deleteProperty(globalThis.navigator, 'locks');
    }
  });

  it('falls back to the in-tab latch when Web Locks is unavailable', async () => {
    // jsdom provides no navigator.locks, so this is the un-stubbed default path.
    expect((globalThis.navigator as { locks?: unknown }).locks).toBeUndefined();

    arrangeHappyRefresh();
    const auth = useAuthStore();
    auth.accessToken = 'stale-token';

    await Promise.all(Array.from({ length: 4 }, () => apiFetch('/api/products')));

    expect(refreshCalls).toBe(1);
  });

  it('clears the session when the refresh token was reused (family revoked)', async () => {
    installFetch(async (request) =>
      request.url.includes('/api/auth/refresh')
        ? problemResponse(401, 'AUTH_REFRESH_REUSE_DETECTED', 'Refresh token reuse detected.')
        : problemResponse(401, 'UNAUTHENTICATED', 'Authentication is required.'),
    );
    const auth = useAuthStore();
    auth.accessToken = 'stale-token';
    auth.user = { ...TEST_USER };

    await expect(apiFetch('/api/products')).rejects.toMatchObject({
      code: 'AUTH_REFRESH_REUSE_DETECTED',
    });

    expect(auth.accessToken).toBeNull();
    expect(auth.user).toBeNull();
  });

  it('does NOT tear down the session on AUTH_REFRESH_RACE', async () => {
    installFetch(async (request) =>
      request.url.includes('/api/auth/refresh')
        ? problemResponse(401, 'AUTH_REFRESH_RACE', 'Refresh token already rotated.')
        : problemResponse(401, 'UNAUTHENTICATED', 'Authentication is required.'),
    );
    const auth = useAuthStore();
    auth.accessToken = 'stale-token';
    auth.user = { ...TEST_USER };

    await expect(apiFetch('/api/products')).rejects.toMatchObject({ code: 'AUTH_REFRESH_RACE' });

    // The backend keeps the token family alive inside its 10s grace window; a
    // concurrent legitimate refresh must not log the user out.
    expect(auth.accessToken).toBe('stale-token');
    expect(auth.user).not.toBeNull();
  });

  it('releases the latch so a later 401 can refresh again', async () => {
    arrangeHappyRefresh();
    const auth = useAuthStore();
    auth.accessToken = 'stale-token';

    await apiFetch('/api/products');
    expect(refreshCalls).toBe(1);

    // Simulate the new token expiring in turn.
    auth.accessToken = 'stale-token';
    await apiFetch('/api/products');
    expect(refreshCalls).toBe(2);
  });
});
