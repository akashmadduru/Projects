/**
 * Unit tests for the HTTP client: bearer token, single-flight refresh on 401 with one replay,
 * Idempotency-Key preserved on replay, auth paths never refresh, in-flight GET dedupe, and
 * RFC 9457 errors. `fetch` is stubbed per test, so nothing depends on MSW handlers.
 *
 * Rewritten 2026-11-17 for the in-memory token client (TD-TEST-03); the old file tested a
 * localStorage token API that no longer exists.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, clearRequestCache, getAccessToken, onSessionExpired, setAccessToken } from './http'

type Call = { url: string; method: string; headers: Headers; body: unknown }

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

let calls: Call[]

function stubFetch(handler: (call: Call) => Response | Promise<Response>) {
  calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const call: Call = {
        url: String(input),
        method: init?.method ?? 'GET',
        headers: new Headers(init?.headers),
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body,
      }
      calls.push(call)
      return handler(call)
    }),
  )
}

const path = (c: Call) => new URL(c.url).pathname

beforeEach(() => {
  clearRequestCache()
  setAccessToken(null)
  onSessionExpired(null)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('apiFetch', () => {
  it('returns parsed JSON and sends the bearer token when one is set', async () => {
    setAccessToken('token-1')
    stubFetch(() => json(200, { ok: true }))

    await expect(apiFetch('/api/products')).resolves.toEqual({ ok: true })
    expect(calls[0]?.headers.get('Authorization')).toBe('Bearer token-1')
  })

  it('adds query parameters to the URL', async () => {
    stubFetch(() => json(200, {}))

    await apiFetch('/api/products', { params: { page: 1, size: 50, q: 'shoes' } })

    const url = new URL(calls[0]?.url ?? 'http://x')
    expect(url.searchParams.get('page')).toBe('1')
    expect(url.searchParams.get('size')).toBe('50')
    expect(url.searchParams.get('q')).toBe('shoes')
  })

  it('throws an ApiError carrying the problem document', async () => {
    stubFetch(() => json(404, { title: 'Not Found', status: 404, code: 'PRODUCT_NOT_FOUND' }))

    await expect(apiFetch('/api/products/nope')).rejects.toMatchObject({ status: 404 })
  })

  it('shares one in-flight GET between callers and forgets it once settled', async () => {
    let release: (r: Response) => void = () => undefined
    stubFetch(() => new Promise<Response>((resolve) => (release = resolve)))

    const a = apiFetch('/api/products')
    const b = apiFetch('/api/products')
    release(json(200, { n: 1 }))
    await expect(Promise.all([a, b])).resolves.toEqual([{ n: 1 }, { n: 1 }])
    expect(calls).toHaveLength(1)

    stubFetch(() => json(200, { n: 2 }))
    await expect(apiFetch('/api/products')).resolves.toEqual({ n: 2 })
    expect(calls).toHaveLength(1)
  })
})

describe('401 handling', () => {
  it('refreshes once, then replays the request with the new token', async () => {
    setAccessToken('expired')
    stubFetch((c) => {
      if (path(c) === '/api/auth/refresh') return json(200, { accessToken: 'fresh' })
      return c.headers.get('Authorization') === 'Bearer fresh' ? json(200, { ok: 1 }) : json(401, {})
    })

    await expect(apiFetch('/api/orders')).resolves.toEqual({ ok: 1 })
    expect(calls.map(path)).toEqual(['/api/orders', '/api/auth/refresh', '/api/orders'])
    expect(getAccessToken()).toBe('fresh')
  })

  it('keeps the Idempotency-Key and body on the replayed request', async () => {
    setAccessToken('expired')
    stubFetch((c) => {
      if (path(c) === '/api/auth/refresh') return json(200, { accessToken: 'fresh' })
      return c.headers.get('Authorization') === 'Bearer fresh' ? json(201, { id: 'o1' }) : json(401, {})
    })

    await apiFetch('/api/orders', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'key-123' },
      body: { cart: 'c1' },
    })

    const orders = calls.filter((c) => path(c) === '/api/orders')
    expect(orders).toHaveLength(2)
    expect(orders.map((c) => c.headers.get('Idempotency-Key'))).toEqual(['key-123', 'key-123'])
    expect(orders[1]?.body).toEqual({ cart: 'c1' })
  })

  it('runs a single refresh for concurrent 401s', async () => {
    setAccessToken('expired')
    stubFetch((c) => {
      if (path(c) === '/api/auth/refresh') return json(200, { accessToken: 'fresh' })
      return c.headers.get('Authorization') === 'Bearer fresh' ? json(200, {}) : json(401, {})
    })

    await Promise.all([apiFetch('/api/a'), apiFetch('/api/b'), apiFetch('/api/c', { method: 'POST' })])

    expect(calls.filter((c) => path(c) === '/api/auth/refresh')).toHaveLength(1)
  })

  it('reports an expired session when the refresh fails, without replaying', async () => {
    const expired = vi.fn()
    onSessionExpired(expired)
    stubFetch(() => json(401, { title: 'Unauthorized', status: 401 }))

    await expect(apiFetch('/api/orders')).rejects.toMatchObject({ status: 401 })
    expect(expired).toHaveBeenCalledTimes(1)
    expect(calls.map(path)).toEqual(['/api/orders', '/api/auth/refresh'])
  })

  it('never refreshes for the auth endpoints themselves', async () => {
    stubFetch(() => json(401, { title: 'Unauthorized', status: 401 }))

    await expect(apiFetch('/api/auth/login', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 401 })
    expect(calls.map(path)).toEqual(['/api/auth/login'])
  })
})
