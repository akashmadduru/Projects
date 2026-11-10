import { vi } from 'vitest';

import { clearAuthProvider } from '@/lib/http';
import { resetRefreshLatch } from '@/stores/auth';

/**
 * Test doubles for `fetch`.
 *
 * Hand-built response objects rather than the real `Response` constructor: the
 * http layer only ever touches `ok`, `status`, `headers.get('content-type')` and
 * `text()`, and constructing real Responses would tie the suite to whichever of
 * jsdom/undici happens to provide the global. This also keeps the shape of what
 * the client is allowed to depend on explicit.
 */
export function jsonResponse(status: number, body?: unknown): Response {
  return buildResponse(status, body, 'application/json');
}

/** A `application/problem+json` RFC 9457 error response. */
export function problemResponse(
  status: number,
  code: string,
  detail: string,
  extras: Record<string, unknown> = {},
): Response {
  return buildResponse(
    status,
    {
      type: `https://errors.acme-ecommerce.internal/${code.toLowerCase().replace(/_/g, '-')}`,
      title: code,
      status,
      detail,
      instance: '/api/test',
      code,
      correlationId: '11111111-2222-4333-8444-555555555555',
      timestamp: '2026-01-01T00:00:00Z',
      ...extras,
    },
    'application/problem+json',
  );
}

/** A 204 with no body, as `POST /api/auth/logout` returns. */
export function noContentResponse(): Response {
  return buildResponse(204, undefined, null);
}

function buildResponse(status: number, body: unknown, contentType: string | null): Response {
  const text = body === undefined ? '' : JSON.stringify(body);
  const response = {
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: {
      get(name: string): string | null {
        return name.toLowerCase() === 'content-type' ? contentType : null;
      },
    },
    text: (): Promise<string> => Promise.resolve(text),
  };
  // Safe: the http client's contract with a response is exactly the four members
  // above, and this object supplies all of them with the same semantics.
  return response as unknown as Response;
}

export interface RecordedRequest {
  readonly url: string;
  readonly method: string;
  readonly authorization: string | null;
  readonly body: unknown;
  readonly credentials: RequestCredentials | undefined;
}

export interface FetchRecorder {
  readonly calls: RecordedRequest[];
  callsTo(pathFragment: string): RecordedRequest[];
  countTo(pathFragment: string): number;
}

/**
 * Installs a `fetch` double that records every request and delegates the response
 * to `handler`.
 */
export function installFetch(
  handler: (request: RecordedRequest) => Response | Promise<Response>,
): FetchRecorder {
  const calls: RecordedRequest[] = [];

  const fetchDouble = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const headers = (init?.headers ?? {}) as Record<string, string>;
      const rawBody = init?.body;
      const recorded: RecordedRequest = {
        url: String(input),
        method: init?.method ?? 'GET',
        authorization: headers['Authorization'] ?? null,
        body: typeof rawBody === 'string' ? (JSON.parse(rawBody) as unknown) : undefined,
        credentials: init?.credentials,
      };
      calls.push(recorded);
      return handler(recorded);
    },
  );

  globalThis.fetch = fetchDouble as unknown as typeof fetch;

  return {
    calls,
    callsTo: (fragment: string) => calls.filter((call) => call.url.includes(fragment)),
    countTo: (fragment: string) => calls.filter((call) => call.url.includes(fragment)).length,
  };
}

/**
 * Module-level state (the refresh latch, the registered auth provider) outlives
 * `createPinia()`, so every test must clear it or the second test in a file
 * inherits the first one's in-flight refresh.
 */
export function resetModuleState(): void {
  resetRefreshLatch();
  clearAuthProvider();
}

/** A `UserResponse` fixture matching auth-service's contract. */
export const TEST_USER = Object.freeze({
  id: '9f0d5f4a-1111-4222-8333-444444444444',
  email: 'admin@acme-ecommerce.internal',
  emailVerified: true,
  displayName: 'Platform Admin',
  status: 'ACTIVE',
  roles: ['ADMIN'],
  createdAt: '2026-01-01T00:00:00Z',
});

/** A `TokenResponse` fixture. */
export function tokenResponse(accessToken: string): Record<string, unknown> {
  return { accessToken, tokenType: 'Bearer', expiresIn: 600, authMethod: 'PASSWORD' };
}

/** Lets pending microtasks (and the promise chains inside the store) settle. */
export async function flush(times = 3): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await Promise.resolve();
  }
}
