/**
 * Rating API client (rating-service via gateway): public reads, buyer-only writes
 */

import { apiFetch } from '../lib/http'
import type {
  BackendPagedModel,
  MyReviewStatus,
  Answer,
  Question,
  QuestionSort,
  Review,
  ReviewFilters,
  ReviewMedia,
  ReviewInput,
  ReviewPage,
  ReviewSort,
  ReviewSummary,
  ReviewVote,
} from '../types'

export const REVIEW_PAGE_SIZE = 10

export interface QuestionPage {
  questions: Question[]
  totalPages: number
  totalElements: number
}

export class RatingApiClient {
  private baseUrl = '/api/ratings'

  async list(
    productId: string,
    page: number = 0,
    sort: ReviewSort = 'createdAt,desc',
    size: number = REVIEW_PAGE_SIZE,
    filters: ReviewFilters = {},
  ): Promise<ReviewPage> {
    const response = await apiFetch<BackendPagedModel<Review>>(`${this.baseUrl}/products/${productId}`, {
      params: {
        page,
        size,
        sort,
        ...(filters.stars ? { stars: filters.stars } : {}),
        ...(filters.verified ? { verified: true } : {}),
        ...(filters.withMedia ? { withMedia: true } : {}),
      },
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

  uploadMedia(ratingId: string, file: File): Promise<ReviewMedia> {
    const body = new FormData()
    body.append('file', file)
    return apiFetch<ReviewMedia>(`${this.baseUrl}/${ratingId}/media`, { method: 'POST', body })
  }

  removeMedia(ratingId: string, mediaId: string): Promise<void> {
    return apiFetch<void>(`${this.baseUrl}/${ratingId}/media/${mediaId}`, { method: 'DELETE' })
  }

  async questions(productId: string, page: number, sort: QuestionSort): Promise<QuestionPage> {
    const response = await apiFetch<BackendPagedModel<Question>>(`${this.baseUrl}/products/${productId}/questions`, {
      params: { page, size: 5, sort },
    })
    return { questions: response.content, totalPages: response.page.totalPages, totalElements: response.page.totalElements }
  }

  async answers(questionId: string, page: number): Promise<Answer[]> {
    const response = await apiFetch<BackendPagedModel<Answer>>(`${this.baseUrl}/questions/${questionId}/answers`, {
      params: { page, size: 10 },
    })
    return response.content
  }

  ask(productId: string, text: string): Promise<Question> {
    return apiFetch<Question>(`${this.baseUrl}/products/${productId}/questions`, { method: 'POST', body: { text } })
  }

  answer(questionId: string, text: string): Promise<Answer> {
    return apiFetch<Answer>(`${this.baseUrl}/questions/${questionId}/answers`, { method: 'POST', body: { text } })
  }

  upvoteQuestion(id: string): Promise<void> {
    return apiFetch<void>(`${this.baseUrl}/questions/${id}/upvote`, { method: 'POST' })
  }

  upvoteAnswer(id: string): Promise<void> {
    return apiFetch<void>(`${this.baseUrl}/answers/${id}/upvote`, { method: 'POST' })
  }

  vote(id: string, vote: ReviewVote): Promise<void> {
    return apiFetch<void>(`${this.baseUrl}/${id}/votes/${vote}`, { method: 'POST' })
  }
}

export const ratingApi = new RatingApiClient()
