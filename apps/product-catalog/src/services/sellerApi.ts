/**
 * Seller API client: public seller profile, seller feedback and sponsored-ad beacons.
 */

import { apiFetch } from '../lib/http'
import type { SellerFeedbackRequest, SellerProfile } from '../types'

export const sellerApi = {
  profile(slug: string): Promise<SellerProfile> {
    return apiFetch<SellerProfile>(`/api/sellers/${encodeURIComponent(slug)}`)
  },

  submitFeedback(sellerId: string, request: SellerFeedbackRequest): Promise<void> {
    return apiFetch<void>(`/api/sellers/${encodeURIComponent(sellerId)}/feedback`, { method: 'POST', body: request })
  },

  /** Fire-and-forget; never surfaces errors. */
  trackImpressions(adIds: string[]): void {
    if (adIds.length === 0) return
    apiFetch<void>('/api/ads/impressions', { method: 'POST', body: { adIds } }).catch(() => undefined)
  },

  /** Fire-and-forget click beacon, sent right before navigating to the product. */
  trackClick(adId: string): void {
    apiFetch<void>(`/api/ads/${encodeURIComponent(adId)}/click`, { method: 'POST' }).catch(() => undefined)
  },
}
