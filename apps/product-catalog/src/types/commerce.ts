/**
 * Phase 7 discovery and conversion payloads: suggest, facets, offers, deals, recommendations, Q&A, alerts
 */

export interface SuggestProduct {
  id: string
  slug: string | null
  name: string
  thumbnailUrl: string | null
  priceAmount: number
  salePrice: number | null
  priceCurrency: string
}

export interface SuggestResponse {
  query: string
  products: SuggestProduct[]
  categories: { id: string; name: string }[]
  didYouMean: string | null
}

export interface FacetValue {
  value: string
  count: number
}

export interface FacetsResponse {
  brands: FacetValue[]
  attributes: { key: string; values: FacetValue[] }[]
  price: { min: number | null; max: number | null }
}

export interface CouponOffer {
  code: string
  description: string
  discountAmount: number
}

export interface BankOffer {
  id: string
  bank: string
  title: string
  discountAmount: number
}

export interface EmiPlan {
  months: number
  monthlyAmount: number
  interestRate: number
  noCost: boolean
}

export interface OffersResponse {
  coupons: CouponOffer[]
  bankOffers: BankOffer[]
  emi: EmiPlan[]
}

export interface Deal {
  id: string
  productId: string
  variantId: string | null
  title: string
  dealPrice: number
  currency: string
  startsAt: string
  endsAt: string
  quantityLimit: number | null
  claimed: number
  remaining: number | null
}

export interface BoughtTogetherEntry {
  productId: string
  score: number
}

export interface Answer {
  id: string
  text: string
  authorName: string
  verifiedBuyer: boolean
  upvotes: number
  createdAt: string
}

export interface Question {
  id: string
  text: string
  authorName: string
  createdAt: string
  upvotes: number
  answerCount: number
  answers: Answer[]
}

export type QuestionSort = 'top' | 'newest'

export type AlertType = 'PRICE_DROP' | 'BACK_IN_STOCK'

export interface ProductAlert {
  id: string
  productId: string
  variantId: string | null
  type: AlertType
  targetPrice: number | null
  lastPrice: number | null
  active: boolean
  triggeredAt: string | null
  createdAt: string
  // Display fields the backend may add; the account page falls back to a product lookup when absent.
  productName?: string | null
}

export interface AlertRequest {
  productId: string
  variantId?: string
  type: AlertType
  targetPrice?: number
  currentPrice?: number
  currency?: string
  productName?: string
}
