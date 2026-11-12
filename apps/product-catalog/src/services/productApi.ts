/**
 * Product API client
 */

import { apiFetch } from '../lib/http'
import type {
  BackendPagedModel,
  BackendProductResponse,
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
): Record<string, string | number | boolean> {
  const params: Record<string, string | number | boolean> = { page, size }
  const q = query.q?.trim()
  if (q) params['q'] = q
  if (query.categoryId) params['categoryId'] = query.categoryId
  if (query.minPrice != null) params['minPrice'] = query.minPrice
  if (query.maxPrice != null) params['maxPrice'] = query.maxPrice
  if (query.minRating != null) params['minRating'] = query.minRating
  if (query.minDiscount != null) params['minDiscount'] = query.minDiscount
  const seller = query.seller?.trim()
  if (seller) params['seller'] = seller
  if (query.inStock) params['inStock'] = true
  if (query.onSale) params['onSale'] = true
  if (query.featured) params['featured'] = true
  if (query.verifiedSeller) params['verifiedSeller'] = true
  const sort = query.sort ? SORT_PARAM[query.sort] : undefined
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

  async detail(id: string): Promise<Product> {
    const backendProduct = await apiFetch<BackendProductResponse>(`${this.baseUrl}/${id}`, {
      method: 'GET',
    })
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
