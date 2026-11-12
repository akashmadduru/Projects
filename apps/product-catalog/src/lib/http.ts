/**
 * HTTP client: in-memory bearer token, single-flight refresh on 401 (replayed once),
 * in-flight GET deduplication, and RFC 9457 error handling.
 *
 * The access token lives in memory only. The refresh token is an HttpOnly cookie that
 * the browser sends via `credentials: 'include'`; JavaScript never sees it.
 */

import { apiErrorFrom } from './problem'

type RequestKey = string

const inflightGets = new Map<RequestKey, Promise<unknown>>()
let accessToken: string | null = null
let refreshInFlight: Promise<string | null> | null = null
let sessionExpiredHandler: (() => void) | null = null

const AUTH_PATH_PREFIX = '/api/auth/'

interface ApiRequestConfig {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  headers?: Record<string, string>
  body?: unknown
  params?: Record<string, string | number | boolean>
}

interface RefreshResponse {
  accessToken: string
}

function buildUrl(baseUrl: string, params?: Record<string, string | number | boolean>): string {
  const url = new URL(baseUrl, typeof window !== 'undefined' ? window.location.origin : 'http://localhost')
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      url.searchParams.set(key, String(value))
    })
  }
  return url.toString()
}

function makeRequestKey(url: string, method: string): RequestKey {
  return `${method.toUpperCase()} ${url}`
}

function isAuthPath(url: string): boolean {
  return new URL(url).pathname.startsWith(AUTH_PATH_PREFIX)
}

function send(url: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && init.body !== null) {
    headers.set('Content-Type', 'application/json')
  }
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }
  return fetch(url, { ...init, headers, credentials: 'include' })
}

async function parseBody(response: Response): Promise<unknown> {
  const isJson = response.headers.get('content-type')?.includes('json')
  if (isJson) {
    try {
      return await response.json()
    } catch {
      return undefined
    }
  }
  return response.status === 204 ? undefined : await response.text()
}

/**
 * Exchanges the refresh cookie for a new access token. Concurrent callers share one request
 * (the refresh token rotates, so a second concurrent call would be treated as reuse).
 * Resolves to the new token, or null when there is no valid session. Never throws.
 */
export function refreshSession(): Promise<string | null> {
  if (refreshInFlight) return refreshInFlight
  refreshInFlight = (async () => {
    try {
      const response = await send(buildUrl('/api/auth/refresh'), { method: 'POST' })
      if (!response.ok) {
        accessToken = null
        return null
      }
      const body = (await response.json()) as RefreshResponse
      accessToken = body.accessToken
      return accessToken
    } catch {
      return null
    } finally {
      refreshInFlight = null
    }
  })()
  return refreshInFlight
}

async function execute<T>(url: string, init: RequestInit): Promise<T> {
  let response = await send(url, init)

  if (response.status === 401 && !isAuthPath(url)) {
    const token = await refreshSession()
    if (token) {
      // Same init (including any Idempotency-Key header) is replayed exactly once.
      response = await send(url, init)
    } else {
      sessionExpiredHandler?.()
    }
  }

  const body = await parseBody(response)
  if (response.ok) return body as T
  throw apiErrorFrom(response.status, body)
}

export async function apiFetch<T = unknown>(url: string, config: ApiRequestConfig = {}): Promise<T> {
  const method = config.method || 'GET'
  const fullUrl = buildUrl(url, config.params)

  const init: RequestInit = { method, headers: config.headers }
  if (config.body !== undefined) {
    init.body = JSON.stringify(config.body)
  }

  if (method !== 'GET') return execute<T>(fullUrl, init)

  // In-flight dedup only: the entry is removed as soon as the request settles.
  const key = makeRequestKey(fullUrl, method)
  const existing = inflightGets.get(key)
  if (existing) return existing as Promise<T>

  const promise = execute<T>(fullUrl, init).finally(() => {
    inflightGets.delete(key)
  })
  inflightGets.set(key, promise)
  return promise
}

export function setAccessToken(token: string | null): void {
  accessToken = token
}

export function getAccessToken(): string | null {
  return accessToken
}

/** Registered by the auth store; invoked when a 401 could not be recovered by refreshing. */
export function onSessionExpired(handler: (() => void) | null): void {
  sessionExpiredHandler = handler
}

export function clearRequestCache(): void {
  inflightGets.clear()
}
