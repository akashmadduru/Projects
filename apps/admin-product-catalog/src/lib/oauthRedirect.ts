/**
 * Remembers where the user was heading across the Google OAuth2 round trip.
 *
 * The redirect target cannot be passed to auth-service: it validates
 * `redirect_uri` against an EXACT-match allow-list (`AUTH_ALLOWED_REDIRECT_URIS`),
 * which contains one entry — this SPA's `/auth/callback`. So the in-app
 * destination is kept on the client instead.
 *
 * `sessionStorage` is correct here and does not contradict the memory-only token
 * rule: the value stored is an in-app path, not a credential. It has to survive a
 * full-page navigation to Google and back, which memory cannot.
 */

const KEY = 'apc.post-login-redirect';

/** Only same-app absolute paths. Anything else is an open-redirect attempt. */
function isSafeInternalPath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//');
}

export function rememberPostLoginRedirect(path: string): void {
  if (!isSafeInternalPath(path)) {
    return;
  }
  try {
    sessionStorage.setItem(KEY, path);
  } catch {
    // Storage can be unavailable (private mode, disabled cookies). Losing the
    // destination downgrades to the default landing page; it must never fail login.
  }
}

/** Reads and clears the remembered destination. */
export function takePostLoginRedirect(): string | null {
  try {
    const value = sessionStorage.getItem(KEY);
    sessionStorage.removeItem(KEY);
    return value !== null && isSafeInternalPath(value) ? value : null;
  } catch {
    return null;
  }
}

/**
 * Validates a `?redirect=` query value before it is used for navigation.
 * Returns null for anything that is not a same-app path.
 */
export function safeRedirectTarget(value: unknown): string | null {
  return typeof value === 'string' && isSafeInternalPath(value) ? value : null;
}
