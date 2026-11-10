/**
 * API response types mirrored from backend
 */

export interface ProductImage {
  id: string
  url: string
  displayOrder: number
}

export interface Product {
  id: string
  sku: string
  name: string
  priceAmount: number
  priceCurrency: string
  active: boolean
  version: number
  categoryId: string | null
  sellerName: string | null
  specifications: unknown | null
  images: string[]
  lastKnownQuantity: number | null
}

export interface BackendProductResponse {
  id: string
  sku: string
  name: string
  priceAmount: number
  priceCurrency: string
  active: boolean
  version: number
  categoryId: string | null
  sellerName: string | null
  specifications: unknown | null
  images: ProductImage[]
  lastKnownQuantity: number | null
}

export interface PagedModel<T> {
  content: T[]
  pageable: {
    pageNumber: number
    pageSize: number
  }
  totalElements: number
  totalPages: number
  isFirst: boolean
  isLast: boolean
}

export type ProductResponse = PagedModel<Product>

export interface ProductCatalogAggregateResponse {
  status: 'success' | 'partial' | 'error'
  data: {
    products: ProductResponse
    categories?: string[]
    stats?: {
      totalProducts: number
      availableProducts: number
    }
  }
  error?: {
    code: string
    message: string
  }
}

export interface Category {
  id: string
  name: string
  description?: string
  imageUrl?: string
}

export interface RatingDistribution {
  fiveStars: number
  fourStars: number
  threeStars: number
  twoStars: number
  oneStar: number
}

export interface ProductRating {
  id: string
  productId: string
  rating: number
  title: string
  comment: string
  authorName: string
  createdAt: string
}

export interface InventoryStatus {
  productId: string
  quantity: number
  availableQuantity: number
  reservedQuantity: number
  status: 'in-stock' | 'low-stock' | 'out-of-stock'
  lastUpdated: string
}

export interface ApiProblem {
  type: string
  title: string
  status: number
  detail?: string
  instance?: string
  correlationId?: string
  [key: string]: unknown
}
