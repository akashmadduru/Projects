/**
 * Marketplace types (seller-service via gateway). Shapes follow the Phase 9 contract.
 */

/** GET /api/sellers/{slug} (public). */
export interface SellerProfile {
  id: string
  slug: string
  displayName: string
  ratingAvg: number | null
  ratingCount: number
  joinedAt: string
  productCount: number
}

export interface SellerFeedbackRequest {
  shipmentId: string
  rating: number
  comment?: string
}
