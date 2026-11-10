/**
 * Product API client
 */

import { apiFetch } from '../lib/http'
import type { Product, ProductResponse, ProductCatalogAggregateResponse, Category, ProductRating, InventoryStatus, PagedModel, BackendProductResponse } from '../types'

export class ProductApiClient {
  private baseUrl = '/api/products'

  private mapBackendProduct(backendProduct: BackendProductResponse): Product {
    return {
      ...backendProduct,
      images: backendProduct.images.map(img => img.url),
    }
  }

  async list(page: number = 0, size: number = 20, filters?: { categoryId?: string; minRating?: number; maxPrice?: number; minPrice?: number; sortBy?: string }): Promise<ProductResponse> {
    const params: Record<string, string | number> = { page, size }
    if (filters?.categoryId) params['categoryId'] = filters.categoryId
    if (filters?.minRating) params['minRating'] = filters.minRating
    if (filters?.maxPrice) params['maxPrice'] = filters.maxPrice
    if (filters?.minPrice) params['minPrice'] = filters.minPrice
    if (filters?.sortBy) params['sortBy'] = filters.sortBy

    const response = await apiFetch<any>(this.baseUrl, {
      method: 'GET',
      params,
    })

    return {
      ...response,
      content: response.content.map((product: BackendProductResponse) => this.mapBackendProduct(product)),
    } as ProductResponse
  }

  async detail(id: string): Promise<Product> {
    const backendProduct = await apiFetch<BackendProductResponse>(`${this.baseUrl}/${id}`, {
      method: 'GET',
    })
    return this.mapBackendProduct(backendProduct)
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

  async getCategories(): Promise<Category[]> {
    return apiFetch<Category[]>('/api/categories', {
      method: 'GET',
    })
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
