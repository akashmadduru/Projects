/**
 * API response types mirrored from backend
 */

export interface ProductImage {
  id: string
  url: string
  displayOrder: number
}

/** Star value ("1".."5") -> count, as serialized from the backend's Map<Integer, Integer>. */
export type RatingCounts = Record<string, number>

/** GET /api/products/{id} and each GET /api/products row, exactly as the backend sends it. */
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
  specifications: Record<string, unknown> | null
  // Empty on the paged list (the backend skips the per-row image lookup); use thumbnailUrl there.
  images: ProductImage[] | null
  lastKnownQuantity: number | null
  description: string | null
  listPrice: number | null
  salePrice: number | null
  discountPercent: number | null
  thumbnailUrl: string | null
  videoUrls: string[] | null
  view360Url: string | null
  weightGrams: number | null
  dimensionsLength: number | null
  dimensionsWidth: number | null
  dimensionsHeight: number | null
  color: string | null
  material: string | null
  averageRating: number | null
  ratingCount: number
  reviewCount: number
  ratingDistribution: RatingCounts | null
  sellerRating: number | null
  featured: boolean
  sellerVerified: boolean
}

/** The backend product with images normalized to ordered URLs (thumbnailUrl as fallback). */
export interface Product extends Omit<BackendProductResponse, 'images'> {
  images: string[]
}

/** Spring Data's PagedModel envelope, as GET /api/products returns it. */
export interface BackendPagedModel<T> {
  content: T[]
  page: {
    size: number
    number: number
    totalElements: number
    totalPages: number
  }
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

/** GET /api/categories row. `active` is Lombok's name for the entity's `isActive`. */
export interface Category {
  id: string
  name: string
  description: string | null
  parentCategoryId: string | null
  depthLevel: number
  active: boolean
  version: number
}

export type ProductSort = 'relevance' | 'price-asc' | 'price-desc' | 'rating' | 'discount' | 'newest'

/** Catalog list narrowing; every field optional. Mirrors GET /api/products query params. */
export interface ProductQuery {
  q?: string
  categoryId?: string
  minPrice?: number
  maxPrice?: number
  minRating?: number
  minDiscount?: number
  sort?: ProductSort
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

/** Public review as rating-service returns it (no user id). */
export interface Review {
  id: string
  productId: string
  userDisplayName: string | null
  ratingValue: number
  reviewTitle: string | null
  reviewText: string | null
  isVerifiedPurchase: boolean
  helpfulCount: number
  unhelpfulCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface ReviewSummary {
  productId: string
  averageRating: number | null
  ratingCount: number
  reviewCount: number
  ratingDistribution: RatingCounts | null
}

/** GET /api/ratings/products/{id}/me */
export interface MyReviewStatus {
  canReview: boolean
  purchased: boolean
  review: Review | null
}

export type ReviewSort = 'createdAt,desc' | 'helpfulCount,desc' | 'ratingValue,desc' | 'ratingValue,asc'

export type ReviewVote = 'HELPFUL' | 'UNHELPFUL'

export interface ReviewInput {
  ratingValue: number
  reviewTitle?: string
  reviewText?: string
}

export interface ReviewPage {
  reviews: Review[]
  page: number
  totalElements: number
  totalPages: number
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
