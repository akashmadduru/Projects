import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router';

import { useAuthStore } from '@/stores/auth';

/**
 * The ONLY authentication decision point in the app.
 *
 * No component performs its own "am I logged in" check: two places expressing one
 * rule is how a view ends up protected by neither, and an in-component check runs
 * after the component has already mounted and started fetching.
 */

declare module 'vue-router' {
  interface RouteMeta {
    /** Navigation is refused (or deferred to a silent refresh) without a session. */
    requiresAuth?: boolean;
    /** Sign-in surfaces: pointless for an already-authenticated user. */
    guestOnly?: boolean;
    /** Role names (unprefixed, as they appear in the token's `roles` claim). */
    requiresAnyRole?: readonly string[];
  }
}

/** Where an authenticated user lands when they had no specific destination. */
export const DEFAULT_AUTHENTICATED_ROUTE = '/dashboard';

/**
 * Narrower than vue-router's `NavigationGuard`: it takes only what it reads and
 * returns only what it decides, which makes it directly callable from a test
 * without fabricating a `next` callback that the guard never uses.
 */
export type AuthGuard = (to: RouteLocationNormalized) => Promise<boolean | RouteLocationRaw>;

export function createAuthGuard(): AuthGuard {
  return async function authGuard(
    to: RouteLocationNormalized,
  ): Promise<boolean | RouteLocationRaw> {
    const auth = useAuthStore();

    if (to.meta.requiresAuth === true) {
      // On a cold start the store is empty because the access token was
      // memory-only — but the HttpOnly refresh cookie survived the reload. One
      // silent refresh decides between "live session" and "anonymous visitor".
      const authenticated = auth.isAuthenticated || (await auth.ensureSession());
      if (!authenticated) {
        return { path: '/login', query: { redirect: to.fullPath } };
      }

      const required = to.meta.requiresAnyRole;
      if (required !== undefined && required.length > 0) {
        const held = auth.user?.roles ?? [];
        if (!required.some((role) => held.includes(role))) {
          return { path: DEFAULT_AUTHENTICATED_ROUTE };
        }
      }
      return true;
    }

    if (to.meta.guestOnly === true && auth.isAuthenticated) {
      return { path: DEFAULT_AUTHENTICATED_ROUTE };
    }

    return true;
  };
}
