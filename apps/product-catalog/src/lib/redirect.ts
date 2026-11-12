/**
 * Post-login redirect handling
 */

/** Accepts only same-origin absolute paths so `?next=` cannot become an open redirect. */
export function safeNext(next: string | null | undefined, fallback = '/'): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return fallback
  return next
}

const POST_LOGIN_KEY = 'pc.post-login-redirect'

/**
 * Remembers the in-app destination across the Google round trip. A full-page navigation to Google
 * and back loses memory, and auth-service only redirects to its exact-match allow-listed callback,
 * so the destination is kept client-side. It is an in-app path, not a credential.
 */
export function rememberPostLoginRedirect(path: string): void {
  try {
    sessionStorage.setItem(POST_LOGIN_KEY, safeNext(path))
  } catch {
    // Storage unavailable: the user lands on the home page instead. Never block sign-in.
  }
}

/** Reads and clears the remembered destination. */
export function takePostLoginRedirect(): string {
  try {
    const value = sessionStorage.getItem(POST_LOGIN_KEY)
    sessionStorage.removeItem(POST_LOGIN_KEY)
    return safeNext(value)
  } catch {
    return '/'
  }
}
