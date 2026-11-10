import { ApiError, apiErrorFrom } from '@/lib/problem';

/**
 * The one HTTP path in the application.
 *
 * Responsibilities, and nothing else:
 *  - build the URL against the gateway origin
 *  - send cookies (`credentials: 'include'`) so the HttpOnly refresh cookie rides along
 *  - attach the in-memory bearer token
 *  - on 401, trigger ONE shared refresh and retry the request EXACTLY once
 *  - turn every failure into an `ApiError` carrying the RFC 9457 problem document
 *
 * It deliberately knows nothing about Pinia, routing or Vue. The auth store hands
 * it a provider (see `registerAuthProvider`), which is what keeps the store ->
 * http dependency one-directional and the module free of import cycles.
 */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export type QueryValue = string | number | boolean | undefined | null;

export interface RequestOptions {
  readonly method?: HttpMethod;
  readonly body?: unknown;
  readonly query?: Readonly<Record<string, QueryValue>>;
  readonly headers?: Readonly<Record<string, string>>;
  readonly signal?: AbortSignal;
  /**
   * Attach the bearer token and participate in refresh-on-401.
   * `false` for the auth endpoints themselves — see NON_RETRYABLE_PATHS.
   */
  readonly authenticated?: boolean;
}

/**
 * Implemented by the auth store. The http layer holds the interface, not the store,
 * so `stores/auth.ts` -> `lib/http.ts` is the only edge between them.
 */
export interface AuthProvider {
  /** Current in-memory access token, or null. Never read from any storage. */
  getAccessToken(): string | null;
  /** Single-flight refresh. Repeated concurrent calls MUST share one network call. */
  refreshAccessToken(): Promise<string>;
}

let authProvider: AuthProvider | null = null;

export function registerAuthProvider(provider: AuthProvider): void {
  authProvider = provider;
}

/** Test seam: drops the provider so a fresh store can register its own. */
export function clearAuthProvider(): void {
  authProvider = null;
}

/**
 * Refreshing in response to a 401 from one of these would recurse: a failed login
 * is not an expired session, and a 401 from /refresh is the refresh itself failing.
 */
const NON_RETRYABLE_PATHS: readonly string[] = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
];

export const apiBaseUrl: string = (
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
).replace(/\/+$/, '');

export const appOrigin: string = (
  import.meta.env.VITE_APP_ORIGIN ?? 'http://localhost:5173'
).replace(/\/+$/, '');

/**
 * Top-level navigation target that starts the Google flow.
 *
 * This is a full-page navigation, never a fetch: the OAuth2 dance involves
 * cross-origin redirects to Google and back, which XHR cannot follow and CORS
 * would block. There is no `redirect_uri` parameter here on purpose — auth-service
 * validates that value against an exact-match allow-list, so the SPA lets the
 * server's configured default win and remembers its own intended destination
 * locally instead (see `oauthRedirect.ts`).
 */
export function googleAuthorizationUrl(): string {
  return `${apiBaseUrl}/oauth2/authorization/google`;
}

function buildUrl(path: string, query?: Readonly<Record<string, QueryValue>>): string {
  const url = new URL(`${apiBaseUrl}${path}`);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null) {
        url.searchParams.set(key, String(value));
      }
    }
  }
  return url.toString();
}

function isRetryablePath(path: string): boolean {
  return !NON_RETRYABLE_PATHS.some((blocked) => path.startsWith(blocked));
}

async function readBody(response: Response): Promise<unknown> {
  if (response.status === 204) {
    return undefined;
  }
  let text: string;
  try {
    text = await response.text();
  } catch {
    return undefined;
  }
  if (text.length === 0) {
    return undefined;
  }
  const contentType = response.headers.get('content-type') ?? '';
  if (contentType.includes('json')) {
    try {
      return JSON.parse(text) as unknown;
    } catch {
      // A body that claims to be JSON and is not means something upstream of the
      // application produced it (a proxy error page). Surfacing the raw text would
      // put HTML on screen, so it is dropped and the status carries the message.
      return undefined;
    }
  }
  return undefined;
}

async function send(
  url: string,
  options: RequestOptions,
  token: string | null,
): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (token !== null) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const init: RequestInit = {
    method: options.method ?? 'GET',
    headers,
    // Non-negotiable: the refresh cookie is HttpOnly and only travels when the
    // request opts into credentials. The gateway's CORS config sets
    // allow-credentials: true and an exact origin to make this legal.
    credentials: 'include',
  };
  if (options.body !== undefined) {
    init.body = JSON.stringify(options.body);
  }
  if (options.signal) {
    init.signal = options.signal;
  }

  try {
    return await fetch(url, init);
  } catch (cause) {
    // fetch only rejects for transport-level failures (DNS, connection refused,
    // CORS preflight rejection, abort). status 0 marks "never reached the server".
    if (cause instanceof DOMException && cause.name === 'AbortError') {
      throw new ApiError('The request was cancelled.', { status: 0, code: 'ABORTED', cause });
    }
    throw new ApiError(
      'Could not reach the server. Check that the API gateway is running.',
      { status: 0, code: 'NETWORK_ERROR', cause },
    );
  }
}

/**
 * Performs a request, refreshing and retrying AT MOST ONCE on a 401.
 *
 * The "at most once" guarantee is structural rather than flag-based: the retry is
 * a single straight-line statement with no loop, so an endpoint that keeps
 * returning 401 after a successful refresh surfaces as a real auth failure instead
 * of spinning.
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const authenticated = options.authenticated ?? true;
  const url = buildUrl(path, options.query);

  const initialToken = authenticated && authProvider ? authProvider.getAccessToken() : null;
  let response = await send(url, options, initialToken);

  if (response.status === 401 && authenticated && authProvider && isRetryablePath(path)) {
    let refreshedToken: string;
    try {
      // Every concurrent 401 lands on this call, and it hands them all the same
      // promise — one network refresh, N waiters.
      refreshedToken = await authProvider.refreshAccessToken();
    } catch (refreshFailure) {
      // The refresh is the authoritative failure; re-throwing it (rather than the
      // original 401) is what lets callers distinguish reuse-detection from a race.
      throw refreshFailure instanceof ApiError
        ? refreshFailure
        : apiErrorFrom(401, undefined, refreshFailure);
    }
    response = await send(url, options, refreshedToken);
  }

  const body = await readBody(response);
  if (!response.ok) {
    throw apiErrorFrom(response.status, body);
  }
  return body as T;
}
