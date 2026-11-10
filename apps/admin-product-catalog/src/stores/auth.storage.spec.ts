import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';

import { apiFetch } from '@/lib/http';
import { useAuthStore } from '@/stores/auth';
import {
  installFetch,
  jsonResponse,
  noContentResponse,
  problemResponse,
  resetModuleState,
  TEST_USER,
  tokenResponse,
} from '@/test/support';

/**
 * The access token must never reach persistent browser storage.
 *
 * An XSS that can read localStorage/sessionStorage turns a 10-minute exposure
 * into a permanent account takeover, because the value it finds keeps working
 * (and, if a refresh token were there too, keeps renewing itself) long after the
 * tab is gone. Memory-only is the control; this test is what stops a well-meaning
 * "persist the session so reload doesn't log me out" change from removing it.
 */

const ACCESS_TOKEN = 'header.payload.signature-access-token';

describe('access token is never persisted', () => {
  let setItemSpy: MockInstance<(key: string, value: string) => void>;

  beforeEach(() => {
    setActivePinia(createPinia());
    resetModuleState();
    localStorage.clear();
    sessionStorage.clear();
    setItemSpy = vi.spyOn(Storage.prototype, 'setItem');
  });

  afterEach(() => {
    setItemSpy.mockRestore();
    resetModuleState();
  });

  function assertNothingTokenShapedWasStored(): void {
    for (const [key, value] of setItemSpy.mock.calls) {
      expect(value).not.toContain(ACCESS_TOKEN);
      expect(value).not.toContain('refreshed-access-token');
      // A key that merely looks token-ish is also a smell worth failing on.
      expect(key.toLowerCase()).not.toContain('token');
    }
    expect(localStorage.length).toBe(0);
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(ACCESS_TOKEN);
  }

  it('stores nothing during a full password login', async () => {
    installFetch(async (request) => {
      if (request.url.includes('/api/auth/login')) {
        return jsonResponse(200, tokenResponse(ACCESS_TOKEN));
      }
      if (request.url.includes('/api/auth/me')) {
        return jsonResponse(200, TEST_USER);
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
    });

    const auth = useAuthStore();
    await auth.login({ email: TEST_USER.email, password: 'a-correct-horse-battery' });

    expect(auth.accessToken).toBe(ACCESS_TOKEN);
    expect(auth.isAuthenticated).toBe(true);
    assertNothingTokenShapedWasStored();
  });

  it('stores nothing during a silent refresh triggered by a 401', async () => {
    installFetch(async (request) => {
      if (request.url.includes('/api/auth/refresh')) {
        return jsonResponse(200, tokenResponse('refreshed-access-token'));
      }
      if (request.url.includes('/api/products')) {
        return request.authorization === 'Bearer refreshed-access-token'
          ? jsonResponse(200, { content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } })
          : problemResponse(401, 'UNAUTHENTICATED', 'Authentication is required.');
      }
      return problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call');
    });

    const auth = useAuthStore();
    auth.accessToken = ACCESS_TOKEN;

    await apiFetch('/api/products');

    expect(auth.accessToken).toBe('refreshed-access-token');
    assertNothingTokenShapedWasStored();
  });

  it('drops the in-memory token on logout', async () => {
    installFetch(async (request) =>
      request.url.includes('/api/auth/logout')
        ? noContentResponse()
        : problemResponse(404, 'RESOURCE_NOT_FOUND', 'unexpected call'),
    );

    const auth = useAuthStore();
    auth.accessToken = ACCESS_TOKEN;
    auth.user = { ...TEST_USER };

    await auth.logout();

    expect(auth.accessToken).toBeNull();
    expect(auth.user).toBeNull();
    assertNothingTokenShapedWasStored();
  });
});
