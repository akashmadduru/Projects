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
  // Phase 7 additions (optional so older payloads and demo data still type-check).
  slug?: string | null
  brand?: string | null
  attributes?: Record<string, string> | null
  hasVariants?: boolean
  variants?: ProductVariant[] | null
  variantOptions?: Record<string, string[]> | null
  // Phase 9 additions (marketplace). Optional: older payloads omit them.
  offers?: ProductOffer[] | null
  buyBox?: ProductOffer | null
  /** Set on sponsored placements injected into list responses (assumed shape; see techdebt). */
  sponsored?: boolean
  adId?: string | null
}

export interface ProductOffer {
  offerId: string
  sellerId: string
  sellerName: string
  sellerSlug: string | null
  sellerRating: number | null
  price: number
  salePrice: number | null
  currency: string
  dispatchDays: number
  availableQuantity: number | null
}

export interface ProductVariant {
  id: string
  sku: string
  attributes: Record<string, string>
  priceAmount: number | null
  salePrice: number | null
  imageUrl: string | null
  active: boolean
  /** Null when inventory could not be read. */
  availableQuantity: number | null
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
  /** Case-insensitive substring of the seller name. */
  seller?: string
  /** Exact seller id (seller storefront page). */
  sellerId?: string
  /** The switches below only ever narrow: leave them undefined (never false) when off. */
  inStock?: true
  onSale?: true
  featured?: true
  verifiedSeller?: true
  /** Brand names (OR). */
  brand?: string[]
  /** Attribute key -> values (OR within a key, AND across keys). */
  attrs?: Record<string, string[]>
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
  media?: ReviewMedia[]
}

export interface ReviewMedia {
  id: string
  url: string
}

export interface ReviewFilters {
  stars?: number
  verified?: true
  withMedia?: true
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
