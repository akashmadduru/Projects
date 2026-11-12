/**
 * Rating API client (rating-service via gateway): public reads, buyer-only writes
 */

import { apiFetch } from '../lib/http'
import type {
  BackendPagedModel,
  MyReviewStatus,
  Review,
  ReviewInput,
  ReviewPage,
  ReviewSort,
  ReviewSummary,
  ReviewVote,
} from '../types'

export const REVIEW_PAGE_SIZE = 10

export class RatingApiClient {
  private baseUrl = '/api/ratings'

  async list(
    productId: string,
    page: number = 0,
    sort: ReviewSort = 'createdAt,desc',
    size: number = REVIEW_PAGE_SIZE,
  ): Promise<ReviewPage> {
    const response = await apiFetch<BackendPagedModel<Review>>(`${this.baseUrl}/products/${productId}`, {
      params: { page, size, sort },
    })
    return {
      reviews: response.content,
      page: response.page.number,
      totalElements: response.page.totalElements,
      totalPages: response.page.totalPages,
    }
  }

  summary(productId: string): Promise<ReviewSummary> {
    return apiFetch<ReviewSummary>(`${this.baseUrl}/products/${productId}/summary`)
  }

  /** Authenticated: whether the caller may review, and their existing review. */
  mine(productId: string): Promise<MyReviewStatus> {
    return apiFetch<MyReviewStatus>(`${this.baseUrl}/products/${productId}/me`)
  }

  create(productId: string, input: ReviewInput): Promise<Review> {
    return apiFetch<Review>(`${this.baseUrl}/products/${productId}`, { method: 'POST', body: input })
  }

  update(id: string, input: ReviewInput, version: number): Promise<Review> {
    return apiFetch<Review>(`${this.baseUrl}/${id}`, { method: 'PUT', body: { ...input, version } })
  }

  remove(id: string): Promise<void> {
    return apiFetch<void>(`${this.baseUrl}/${id}`, { method: 'DELETE' })
  }

  vote(id: string, vote: ReviewVote): Promise<void> {
    return apiFetch<void>(`${this.baseUrl}/${id}/votes/${vote}`, { method: 'POST' })
  }
}

export const ratingApi = new RatingApiClient()
