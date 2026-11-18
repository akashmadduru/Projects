/**
 * Product API client
 */

import { apiFetch, type QueryParams } from '../lib/http'
import type {
  BackendPagedModel,
  BackendProductResponse,
  BackendSearchResponse,
  Category,
  InventoryStatus,
  PagedModel,
  Product,
  ProductCatalogAggregateResponse,
  ProductQuery,
  ProductRating,
  ProductResponse,
  ProductSort,
} from '../types'

/** UI sort keys -> the backend's `sort` param (limited server-side to a whitelist). */
const SORT_PARAM: Record<ProductSort, string | undefined> = {
  relevance: undefined,
  'price-asc': 'priceAmount,asc',
  'price-desc': 'priceAmount,desc',
  rating: 'averageRating,desc',
  discount: 'discountPercent,desc',
  newest: 'createdAt,desc',
}

export function toQueryParams(
  page: number,
  size: number,
  query: ProductQuery = {},
): QueryParams {
  const params: QueryParams = { page, size }
  const q = query.q?.trim()
  if (q) params['q'] = q
  if (query.categoryId) params['categoryId'] = query.categoryId
  if (query.minPrice != null) params['minPrice'] = query.minPrice
  if (query.maxPrice != null) params['maxPrice'] = query.maxPrice
  if (query.minRating != null) params['minRating'] = query.minRating
  if (query.minDiscount != null) params['minDiscount'] = query.minDiscount
  const seller = query.seller?.trim()
  if (seller) params['seller'] = seller
  if (query.sellerId) params['sellerId'] = query.sellerId
  if (query.inStock) params['inStock'] = true
  if (query.onSale) params['onSale'] = true
  if (query.featured) params['featured'] = true
  if (query.verifiedSeller) params['verifiedSeller'] = true
  // Repeated parameters, never comma-joined: a brand or attribute value may contain a comma.
  if (query.brand?.length) params['brand'] = [...query.brand]
  for (const [key, values] of Object.entries(query.attrs ?? {})) {
    if (values.length) params[`attr.${key}`] = [...values]
  }
  const sort = query.sort ? SORT_PARAM[query.sort] : undefined
  if (sort) params['sort'] = sort
  return params
}

/** UI sort keys GET /api/products/search understands; `rating`/`discount` are catalog-only. */
const SEARCH_SORT: Partial<Record<ProductSort, string>> = {
  relevance: 'relevance',
  'price-asc': 'price_asc',
  'price-desc': 'price_desc',
  newest: 'newest',
}

/**
 * Cartwise AI-1: a query goes to the hybrid (keyword + semantic) search endpoint when it has search
 * text and uses only the filters that endpoint supports. Anything else - attribute facets, seller,
 * stock/sale toggles, rating/discount sorts - stays on the plain catalog listing so no filter is
 * silently dropped.
 */
export function usesSmartSearch(query: ProductQuery = {}): boolean {
  const q = query.q?.trim() ?? ''
  if (q.length < 2) return false
  if (query.sort && !SEARCH_SORT[query.sort]) return false
  const hasAttrs = Object.values(query.attrs ?? {}).some((v) => v.length > 0)
  return !(
    hasAttrs ||
    query.minDiscount != null ||
    query.seller?.trim() ||
    query.sellerId ||
    query.inStock ||
    query.onSale ||
    query.featured ||
    query.verifiedSeller
  )
}

/** Params for /api/products/search and /search/facets. Only call when {@link usesSmartSearch}. */
export function toSearchParams(page: number, size: number, query: ProductQuery): QueryParams {
  const params: QueryParams = { q: query.q!.trim(), mode: 'hybrid', page, size }
  if (query.categoryId) params['categoryId'] = query.categoryId
  if (query.minPrice != null) params['minPrice'] = query.minPrice
  if (query.maxPrice != null) params['maxPrice'] = query.maxPrice
  if (query.minRating != null) params['minRating'] = query.minRating
  if (query.brand?.length) params['brand'] = [...query.brand]
  const sort = query.sort ? SEARCH_SORT[query.sort] : undefined
  if (sort) params['sort'] = sort
  return params
}

export function toProduct(backendProduct: BackendProductResponse): Product {
  const images = [...(backendProduct.images ?? [])]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((img) => img.url)
  if (images.length === 0 && backendProduct.thumbnailUrl) {
    images.push(backendProduct.thumbnailUrl)
  }
  return { ...backendProduct, images }
}

export class ProductApiClient {
  private baseUrl = '/api/products'

  async list(page: number = 0, size: number = 20, query?: ProductQuery): Promise<ProductResponse> {
    if (query && usesSmartSearch(query)) {
      try {
        return await this.search(page, size, query)
      } catch {
        // An older backend without /search, or a transient failure: the plain listing still answers.
      }
    }
    const response = await apiFetch<BackendPagedModel<BackendProductResponse>>(this.baseUrl, {
      method: 'GET',
      params: toQueryParams(page, size, query),
    })

    const { number, size: pageSize, totalElements, totalPages } = response.page
    const paged: PagedModel<Product> = {
      content: response.content.map(toProduct),
      pageable: { pageNumber: number, pageSize },
      totalElements,
      totalPages,
      isFirst: number === 0,
      isLast: number >= totalPages - 1,
    }
    return paged
  }

  /** Hybrid keyword + semantic search (Cartwise AI-1). Degrades server-side to keyword, never errors for that. */
  async search(page: number, size: number, query: ProductQuery): Promise<ProductResponse> {
    const response = await apiFetch<BackendSearchResponse>(`${this.baseUrl}/search`, {
      method: 'GET',
      params: toSearchParams(page, size, query),
    })
    const { number, size: pageSize, totalElements, totalPages } = response.page
    return {
      content: response.content.map(toProduct),
      pageable: { pageNumber: number, pageSize },
      totalElements,
      totalPages,
      isFirst: number === 0,
      isLast: number >= totalPages - 1,
      search: {
        effectiveMode: response.effectiveMode,
        degraded: response.degraded,
        interpretedFilters: response.interpretedFilters,
      },
    }
  }

  async detail(id: string): Promise<Product> {
    const backendProduct = await apiFetch<BackendProductResponse>(`${this.baseUrl}/${id}`, {
      method: 'GET',
    })
    return toProduct(backendProduct)
  }

  async bySlug(slug: string): Promise<Product> {
    const backendProduct = await apiFetch<BackendProductResponse>(`${this.baseUrl}/slug/${encodeURIComponent(slug)}`)
    return toProduct(backendProduct)
  }

  async aggregate(page: number = 0, size: number = 20): Promise<ProductCatalogAggregateResponse> {
    return apiFetch<ProductCatalogAggregateResponse>('/api/products/catalog/aggregate', {
      method: 'GET',
      params: {
        page,
        size,
      },
    })
  }

  // The catalog has a few dozen categories; one page of 100 is the whole tree.
  async getCategories(): Promise<Category[]> {
    const response = await apiFetch<BackendPagedModel<Category>>('/api/categories', {
      method: 'GET',
      params: { page: 0, size: 100 },
    })
    return response.content
  }

  async getRatings(productId: string): Promise<PagedModel<ProductRating>> {
    return apiFetch<PagedModel<ProductRating>>(`/api/ratings/products/${productId}`, {
      method: 'GET',
    })
  }

  async getInventory(productId: string): Promise<InventoryStatus> {
    return apiFetch<InventoryStatus>(`/api/inventory/by-product/${productId}`, {
      method: 'GET',
    })
  }
}

export const productApi = new ProductApiClient()
