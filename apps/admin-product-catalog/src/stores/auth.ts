import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { apiFetch, registerAuthProvider } from '@/lib/http';
import { ApiError } from '@/lib/problem';
import type { LoginRequest, RegisterRequest, TokenResponse, UserResponse } from '@/types/api';

/**
 * Session state.
 *
 * THE ACCESS TOKEN LIVES IN THIS MODULE'S MEMORY AND NOWHERE ELSE. Not
 * localStorage, not sessionStorage, not a cookie this code writes, not IndexedDB.
 * An XSS that can read persistent storage would otherwise own the account
 * permanently; memory-only bounds the exposure to the life of the tab, at the cost
 * of one silent refresh after every page reload. That trade is deliberate.
 *
 * The refresh token is never visible here at all — it is an HttpOnly cookie scoped
 * to `Path=/api/auth`, sent by the browser because every request opts into
 * credentials. There is no code path in this app that reads `document.cookie`.
 */

/**
 * The single-flight latch.
 *
 * MODULE-LEVEL AND NOT REACTIVE, ON PURPOSE. Making it a `ref` would put a pending
 * promise into the reactivity graph (and into devtools serialisation) for no gain;
 * more importantly it must be shared by every consumer of the store, including the
 * http layer, and must survive being read outside a component context.
 *
 * Why it matters: the backend ROTATES the refresh token on every use and runs
 * reuse detection. If five concurrent 401s each fired their own refresh, four of
 * them would be replaying an already-consumed cookie. Inside the 10s grace window
 * that is a harmless AUTH_REFRESH_RACE; outside it, it is indistinguishable from
 * a stolen token and the whole family gets revoked — i.e. the app logs itself out.
 */
let refreshPromise: Promise<string> | null = null;

/** Test seam. Module state outlives `createPinia()`, so tests must clear it. */
export function resetRefreshLatch(): void {
  refreshPromise = null;
}

/** Name of the cross-tab Web Lock guarding refresh. */
export const REFRESH_LOCK_NAME = 'auth-refresh';

interface LockManagerLike {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>;
}

function lockManager(): LockManagerLike | null {
  // Cast via `unknown` is safe here because the result is immediately feature-
  // detected: `navigator` itself may be absent (non-browser test runner) and
  // `navigator.locks` is absent on older browsers and in insecure contexts, and
  // the DOM lib types both as always-present.
  const nav = globalThis.navigator as unknown as { locks?: LockManagerLike } | undefined;
  const candidate = nav?.locks;
  if (candidate !== undefined && typeof candidate.request === 'function') {
    return candidate;
  }
  return null;
}

/**
 * Serialises refresh across browser tabs.
 *
 * The in-tab latch only covers one JS context; two tabs both hitting a 401 at the
 * same moment are two contexts sharing one cookie jar. Web Locks is the only
 * primitive that coordinates them without a server round trip. Where it is
 * unavailable the app degrades to the in-tab latch rather than losing refresh
 * entirely — a rare cross-tab race is recoverable, no refresh at all is not.
 */
async function withCrossTabLock<T>(name: string, work: () => Promise<T>): Promise<T> {
  const locks = lockManager();
  if (locks === null) {
    return work();
  }
  return locks.request(name, work);
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null);
  const user = ref<UserResponse | null>(null);

  /**
   * Whether this tab has already spent its one silent-refresh attempt. Without
   * it, every guarded navigation for an anonymous visitor would fire a doomed
   * refresh at the gateway.
   */
  const silentRefreshAttempted = ref(false);

  const isAuthenticated = computed<boolean>(() => accessToken.value !== null);
  const displayName = computed<string>(() => user.value?.displayName ?? user.value?.email ?? '');
  /** Product writes require CATALOG_MANAGER or ADMIN; reads need any authenticated user. */
  const canManageCatalog = computed<boolean>(
    () => user.value?.roles.some((role) => role === 'CATALOG_MANAGER' || role === 'ADMIN') ?? false,
  );

  function clearSession(): void {
    accessToken.value = null;
    user.value = null;
  }

  async function loadCurrentUser(): Promise<UserResponse> {
    const me = await apiFetch<UserResponse>('/api/auth/me');
    user.value = me;
    return me;
  }

  async function performRefresh(): Promise<string> {
    // authenticated: false — the refresh endpoint is cookie-only, and sending a
    // stale bearer would make a 401 ambiguous between "bad token" and "bad cookie".
    const tokens = await apiFetch<TokenResponse>('/api/auth/refresh', {
      method: 'POST',
      authenticated: false,
    });
    accessToken.value = tokens.accessToken;
    return tokens.accessToken;
  }

  /**
   * Refresh, at most one network call at a time per tab and (where supported) per
   * browser profile. Callers get the same promise, so N waiters produce 1 request.
   */
  function refreshAccessToken(): Promise<string> {
    if (refreshPromise !== null) {
      return refreshPromise;
    }

    const inFlight: Promise<string> = withCrossTabLock(REFRESH_LOCK_NAME, performRefresh)
      .catch((error: unknown) => {
        // AUTH_REFRESH_RACE means a legitimate concurrent refresh already rotated
        // the cookie; the family is intact and the user is still signed in, so the
        // session must NOT be torn down. Every other failure is terminal.
        if (!(error instanceof ApiError && error.isRefreshRace)) {
          clearSession();
        }
        throw error;
      })
      .finally(() => {
        // Guard against clearing a newer latch if one was somehow installed.
        if (refreshPromise === inFlight) {
          refreshPromise = null;
        }
      });

    refreshPromise = inFlight;
    return inFlight;
  }

  async function login(credentials: LoginRequest): Promise<void> {
    const tokens = await apiFetch<TokenResponse>('/api/auth/login', {
      method: 'POST',
      body: credentials,
      authenticated: false,
    });
    accessToken.value = tokens.accessToken;
    silentRefreshAttempted.value = true;
    await loadCurrentUser();
  }

  async function register(payload: RegisterRequest): Promise<UserResponse> {
    // No token results from registration: the backend creates the account and the
    // user then signs in normally. Auto-login here would hide a disabled-account
    // or unverified-email state behind a working session.
    return apiFetch<UserResponse>('/api/auth/register', {
      method: 'POST',
      body: payload,
      authenticated: false,
    });
  }

  async function logout(): Promise<void> {
    try {
      await apiFetch<void>('/api/auth/logout', { method: 'POST', authenticated: false });
    } catch {
      // Logout is idempotent server-side and always 204. If the network is down,
      // dropping local state is still the right outcome — never leave a user
      // looking signed in because a request failed.
    } finally {
      clearSession();
      silentRefreshAttempted.value = false;
      resetRefreshLatch();
    }
  }

  /**
   * Used by the route guard on a cold start (hard reload, bookmark, new tab).
   *
   * The Pinia store is empty because the token was memory-only, but the refresh
   * cookie survived the reload — so exactly one silent refresh decides whether
   * this is a live session or an anonymous visitor. This is what makes "press F5"
   * not equivalent to logging out.
   */
  async function ensureSession(): Promise<boolean> {
    if (accessToken.value !== null) {
      return true;
    }
    if (silentRefreshAttempted.value) {
      return false;
    }
    silentRefreshAttempted.value = true;
    try {
      await refreshAccessToken();
      await loadCurrentUser();
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Completes the Google OAuth2 flow. auth-service has already set the refresh
   * cookie and redirected here with NOTHING in the URL — no token, no code — so
   * the first access token comes from a normal refresh call.
   */
  async function completeFederatedLogin(): Promise<void> {
    silentRefreshAttempted.value = true;
    await refreshAccessToken();
    await loadCurrentUser();
  }

  // Registering here (rather than in main.ts) means the http layer is wired the
  // moment anything touches the store, including in tests, and keeps the
  // dependency pointing store -> http only.
  registerAuthProvider({
    getAccessToken: () => accessToken.value,
    refreshAccessToken,
  });

  return {
    accessToken,
    user,
    silentRefreshAttempted,
    isAuthenticated,
    displayName,
    canManageCatalog,
    login,
    register,
    logout,
    refreshAccessToken,
    ensureSession,
    completeFederatedLogin,
    loadCurrentUser,
    clearSession,
  };
});
