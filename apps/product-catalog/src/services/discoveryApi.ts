/**
 * Phase 7 discovery and conversion clients: suggest, facets, offers, deals, recommendations, alerts, views
 */

import { apiFetch } from '../lib/http'
import { toProduct, toQueryParams } from './productApi'
import type {
  AlertRequest,
  BackendProductResponse,
  BoughtTogetherEntry,
  Deal,
  FacetsResponse,
  OffersResponse,
  Product,
  ProductAlert,
  ProductQuery,
  SuggestResponse,
} from '../types'

const SESSION_KEY = 'cartwise-session-id'

function sessionId(): string {
  try {
    let id = localStorage.getItem(SESSION_KEY)
    if (!id) {
      id = crypto.randomUUID()
      localStorage.setItem(SESSION_KEY, id)
    }
    return id
  } catch {
    return 'anonymous'
  }
}

export const discoveryApi = {
  suggest(q: string, limit = 8): Promise<SuggestResponse> {
    return apiFetch<SuggestResponse>('/api/products/suggest', { params: { q, limit } })
  },

  facets(query: ProductQuery): Promise<FacetsResponse> {
    const params = toQueryParams(0, 1, { ...query, sort: undefined })
    delete params['page']
    delete params['size']
    return apiFetch<FacetsResponse>('/api/products/facets', { params })
  },

  offers(productId: string, price: number, currency: string): Promise<OffersResponse> {
    return apiFetch<OffersResponse>('/api/promotions/offers', { params: { productId, price, currency } })
  },

  activeDeals(): Promise<Deal[]> {
    return apiFetch<Deal[]>('/api/promotions/deals/active')
  },

  async batch(ids: string[]): Promise<Product[]> {
    if (ids.length === 0) return []
    const rows = await apiFetch<BackendProductResponse[]>('/api/products/batch', {
      params: { ids: ids.slice(0, 50).join(',') },
    })
    // Keep the caller's order; the backend may not.
    const byId = new Map(rows.map((r) => [r.id, toProduct(r)]))
    return ids.map((id) => byId.get(id)).filter((p): p is Product => p !== undefined)
  },

  alsoViewed(productId: string, limit = 8): Promise<Product[]> {
    return apiFetch<BackendProductResponse[]>(`/api/products/${productId}/also-viewed`, { params: { limit } }).then(
      (rows) => rows.map(toProduct),
    )
  },

  boughtTogether(productId: string, limit = 4): Promise<BoughtTogetherEntry[]> {
    return apiFetch<BoughtTogetherEntry[]>(`/api/recommendations/products/${productId}/bought-together`, {
      params: { limit },
    })
  },

  /** Fire-and-forget view beacon; never surfaces errors. */
  trackView(productId: string): void {
    apiFetch<void>(`/api/products/${productId}/views`, { method: 'POST', body: { sessionId: sessionId() } }).catch(
      () => undefined,
    )
  },

  syncRecentlyViewed(productId: string): void {
    apiFetch<void>('/api/users/me/recently-viewed', { method: 'POST', body: { productId } }).catch(() => undefined)
  },

  listAlerts(): Promise<ProductAlert[]> {
    return apiFetch<ProductAlert[]>('/api/alerts')
  },

  createAlert(request: AlertRequest): Promise<ProductAlert> {
    return apiFetch<ProductAlert>('/api/alerts', { method: 'POST', body: request })
  },

  removeAlert(id: string): Promise<void> {
    return apiFetch<void>(`/api/alerts/${id}`, { method: 'DELETE' })
  },
}
