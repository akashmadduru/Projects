/**
 * HTTP client: in-memory bearer token, single-flight refresh on 401 (replayed once),
 * in-flight GET deduplication, and RFC 9457 error handling.
 *
 * The access token lives in memory only. The refresh token is an HttpOnly cookie that
 * the browser sends via `credentials: 'include'`; JavaScript never sees it.
 */

import { getLang } from './i18n'
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
  /** Query parameters. An array value is sent as a repeated parameter (`brand=A&brand=B`). */
  params?: QueryParams
}

interface RefreshResponse {
  accessToken?: string
  mfaRequired?: boolean
  mfaToken?: string
}

/** Set when /refresh answers with an MFA challenge (e.g. right after Google sign-in) instead of tokens. */
let pendingMfaToken: string | null = null

/** Returns and clears an MFA challenge captured by the last refresh, if any. */
export function takeMfaChallenge(): string | null {
  const token = pendingMfaToken
  pendingMfaToken = null
  return token
}

export type QueryParamValue = string | number | boolean
export type QueryParams = Record<string, QueryParamValue | readonly QueryParamValue[]>

export function buildUrl(baseUrl: string, params?: QueryParams): string {
  const url = new URL(baseUrl, typeof window !== 'undefined' ? window.location.origin : 'http://localhost')
  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (Array.isArray(value)) {
        url.searchParams.delete(key)
        value.forEach((v) => url.searchParams.append(key, String(v)))
      } else {
        url.searchParams.set(key, String(value))
      }
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

function isSameOrigin(url: string): boolean {
  if (typeof window === 'undefined') return true
  try {
    return new URL(url, window.location.origin).origin === window.location.origin
  } catch {
    return false
  }
}

function send(url: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body !== undefined && init.body !== null && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  headers.set('Accept-Language', getLang())
  // The token only ever goes to our own origin: a future absolute or user-influenced URL must not
  // receive it (TD-SEC-11). All API calls are relative `/api/*`, proxied by the gateway.
  if (accessToken && isSameOrigin(url)) {
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
      if (body.mfaRequired && body.mfaToken) {
        accessToken = null
        pendingMfaToken = body.mfaToken
        return null
      }
      accessToken = body.accessToken ?? null
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
    init.body = config.body instanceof FormData ? config.body : JSON.stringify(config.body)
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

/** Authenticated binary download (e.g. invoice PDF) with the same single refresh-and-replay on 401. */
export async function apiDownload(url: string): Promise<Blob> {
  const fullUrl = buildUrl(url)
  const init: RequestInit = { method: 'GET' }
  let response = await send(fullUrl, init)
  if (response.status === 401) {
    const token = await refreshSession()
    if (token) response = await send(fullUrl, init)
    else sessionExpiredHandler?.()
  }
  if (response.ok) return response.blob()
  throw apiErrorFrom(response.status, await parseBody(response))
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
