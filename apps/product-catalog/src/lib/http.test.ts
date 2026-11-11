/**
 * Unit tests for HTTP client (apiFetch, dedup, JWT, etc.)
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { apiFetch, setAuthToken, getAuthToken, clearRequestCache } from './http'
import type { BackendPagedModel, BackendProductResponse } from '../types'

describe('apiFetch', () => {
  beforeEach(() => {
    clearRequestCache()
    setAuthToken(null)
    localStorage.clear()
  })

  it('should fetch and return JSON data on success', async () => {
    const data = await apiFetch<BackendPagedModel<BackendProductResponse>>('/api/products', {
      method: 'GET',
      params: { page: 0, size: 20 },
    })

    expect(data).toBeDefined()
    expect(data.content).toBeDefined()
    expect(Array.isArray(data.content)).toBe(true)
    expect(data.page.totalElements).toBe(100)
  })

  it('should handle GET request deduplication', async () => {
    const promise1 = apiFetch('/api/products', { method: 'GET' })
    const promise2 = apiFetch('/api/products', { method: 'GET' })

    const [result1, result2] = await Promise.all([promise1, promise2])

    expect(result1).toEqual(result2)
  })

  it('should throw ApiError on 404 response', async () => {
    try {
      await apiFetch('/api/products/nonexistent', { method: 'GET' })
      expect.fail('Should have thrown an error')
    } catch (error) {
      expect(error).toBeDefined()
      expect((error as any).status).toBe(404)
      expect((error as any).problem.title).toBe('Not Found')
    }
  })

  it('should include JWT token in Authorization header when set', async () => {
    const testToken = 'test-jwt-token-123'
    setAuthToken(testToken)

    expect(getAuthToken()).toBe(testToken)

    const data = await apiFetch<BackendPagedModel<BackendProductResponse>>('/api/products', {
      method: 'GET',
    })

    expect(data).toBeDefined()
  })

  it('should persist auth token to localStorage', () => {
    const testToken = 'test-token-456'
    setAuthToken(testToken)

    expect(localStorage.getItem('auth_token')).toBe(testToken)

    setAuthToken(null)
    expect(localStorage.getItem('auth_token')).toBeNull()
  })

  it('should retrieve auth token from localStorage on getAuthToken', () => {
    const testToken = 'test-token-789'
    localStorage.setItem('auth_token', testToken)

    expect(getAuthToken()).toBe(testToken)
  })

  it('should add query parameters to request URL', async () => {
    const data = await apiFetch<BackendPagedModel<BackendProductResponse>>('/api/products', {
      method: 'GET',
      params: {
        page: 1,
        size: 50,
      },
    })

    expect(data).toBeDefined()
  })
})

describe('ApiError', () => {
  it('should have correct error structure', async () => {
    try {
      await apiFetch('/api/products/invalid-id', { method: 'GET' })
      expect.fail('Should have thrown')
    } catch (error) {
      const apiError = error as any
      expect(apiError.status).toBe(404)
      expect(apiError.problem).toBeDefined()
      expect(apiError.problem.title).toBe('Not Found')
      expect(apiError.message).toBeDefined()
    }
  })
})
