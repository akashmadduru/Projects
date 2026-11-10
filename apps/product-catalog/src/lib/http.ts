/**
 * HTTP client with single-flight deduplication, JWT injection, and RFC 9457 error handling
 */

import { apiErrorFrom } from './problem'

type RequestKey = string
type RequestPromiseCache = Map<RequestKey, Promise<unknown>>

const requestCache: RequestPromiseCache = new Map()
let authToken: string | null = null

interface ApiRequestConfig {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  headers?: Record<string, string>
  body?: unknown
  params?: Record<string, string | number | boolean>
}

interface ApiResponse<T> {
  status: number
  headers: Headers
  data: T
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

async function fetchWithAuth(url: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers || {})
  headers.set('Content-Type', 'application/json')

  if (authToken) {
    headers.set('Authorization', `Bearer ${authToken}`)
  }

  return fetch(url, {
    ...init,
    headers,
  })
}

async function handleResponse<T>(response: Response): Promise<ApiResponse<T>> {
  const contentType = response.headers.get('content-type')
  const isJson = contentType?.includes('application/json')

  let data: T

  if (response.ok) {
    if (isJson) {
      data = await response.json()
    } else {
      data = (await response.text()) as T
    }
    return {
      status: response.status,
      headers: response.headers,
      data,
    }
  }

  // Error response
  let errorBody: unknown
  if (isJson) {
    try {
      errorBody = await response.json()
    } catch {
      errorBody = await response.text()
    }
  } else {
    errorBody = await response.text()
  }

  const error = apiErrorFrom(response.status, errorBody)

  if (response.status === 401) {
    await refreshToken()
  }

  throw error
}

async function refreshToken(): Promise<void> {
  // Single-flight dedup on refresh
  const refreshKey = makeRequestKey('/api/auth/refresh', 'POST')

  if (requestCache.has(refreshKey)) {
    await requestCache.get(refreshKey)
    return
  }

  const refreshPromise = fetchWithAuth('/api/auth/refresh', {
    method: 'POST',
  })
    .then(async (response) => {
      if (response.ok) {
        const data = await response.json() as { token?: string }
        if (data.token) {
          authToken = data.token
          localStorage.setItem('auth_token', data.token)
        }
      }
      return response
    })
    .finally(() => {
      requestCache.delete(refreshKey)
    })

  requestCache.set(refreshKey, refreshPromise)

  try {
    await refreshPromise
  } catch {
    authToken = null
    localStorage.removeItem('auth_token')
  }
}

export async function apiFetch<T = unknown>(
  url: string,
  config: ApiRequestConfig = {},
): Promise<T> {
  const method = config.method || 'GET'
  const fullUrl = buildUrl(url, config.params)
  const requestKey = makeRequestKey(fullUrl, method)

  // Single-flight dedup: only GET requests
  if (method === 'GET' && requestCache.has(requestKey)) {
    return requestCache.get(requestKey)! as Promise<T>
  }

  const init: RequestInit = {
    method,
    headers: config.headers,
  }

  if (config.body) {
    init.body = JSON.stringify(config.body)
  }

  const dataPromise = fetchWithAuth(fullUrl, init)
    .then((response) => handleResponse<T>(response).then((result) => result.data))
    .catch((error) => {
      requestCache.delete(requestKey)
      throw error
    })

  if (method === 'GET') {
    requestCache.set(requestKey, dataPromise)
  }

  try {
    return await dataPromise
  } catch (error) {
    requestCache.delete(requestKey)
    throw error
  }
}

export function setAuthToken(token: string | null): void {
  authToken = token
  if (token) {
    localStorage.setItem('auth_token', token)
  } else {
    localStorage.removeItem('auth_token')
  }
}

export function getAuthToken(): string | null {
  if (authToken) return authToken
  if (typeof window !== 'undefined') {
    authToken = localStorage.getItem('auth_token')
  }
  return authToken
}

export function clearRequestCache(): void {
  requestCache.clear()
}
