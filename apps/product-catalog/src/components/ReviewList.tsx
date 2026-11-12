/**
 * Paged list of a product's reviews with sort and helpful / not helpful voting
 */

import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ErrorAlert } from './ErrorAlert'
import { secondaryButtonClass } from './Form'
import { ThemedSelect } from './ThemedSelect'
import { CheckBadgeIcon } from './Icons'
import { ReviewStars } from './ReviewStars'
import { errorMessage } from '../lib/errors'
import { ratingApi } from '../services/ratingApi'
import type { Review, ReviewSort, ReviewVote } from '../types'

const SORTS: { value: ReviewSort; label: string }[] = [
  { value: 'createdAt,desc', label: 'Newest' },
  { value: 'helpfulCount,desc', label: 'Most helpful' },
  { value: 'ratingValue,desc', label: 'Highest rated' },
  { value: 'ratingValue,asc', label: 'Lowest rated' },
]

interface Props {
  productId: string
  /** Bump to reload the list after the user creates, edits or deletes a review. */
  refreshKey: number
  authenticated: boolean
  ownReviewId?: string
}

export function ReviewList({ productId, refreshKey, authenticated, ownReviewId }: Props) {
  const navigate = useNavigate()
  const [sort, setSort] = useState<ReviewSort>('createdAt,desc')
  const [reviews, setReviews] = useState<Review[]>([])
  const [pagesLoaded, setPagesLoaded] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const [votingId, setVotingId] = useState<string | undefined>()

  useEffect(() => {
    let active = true
    setLoading(true)
    setError(undefined)
    ratingApi
      .list(productId, 0, sort)
      .then((result) => {
        if (!active) return
        setReviews(result.reviews)
        setPagesLoaded(1)
        setTotalPages(result.totalPages)
        setTotalElements(result.totalElements)
      })
      .catch((e: unknown) => {
        if (active) setError(errorMessage(e))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [productId, sort, refreshKey])

  const loadMore = async () => {
    setLoadingMore(true)
    setError(undefined)
    try {
      const result = await ratingApi.list(productId, pagesLoaded, sort)
      setReviews((prev) => [...prev, ...result.reviews.filter((r) => !prev.some((p) => p.id === r.id))])
      setPagesLoaded((n) => n + 1)
      setTotalPages(result.totalPages)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLoadingMore(false)
    }
  }

  const vote = async (review: Review, value: ReviewVote) => {
    if (!authenticated) {
      navigate(`/login?next=${encodeURIComponent(`${window.location.pathname}#reviews`)}`)
      return
    }
    setVotingId(review.id)
    setError(undefined)
    try {
      await ratingApi.vote(review.id, value)
      // The vote response may be empty (204), so re-read everything loaded so far for fresh counts.
      const pages = await Promise.all(
        Array.from({ length: Math.max(1, pagesLoaded) }, (_, i) => ratingApi.list(productId, i, sort)),
      )
      setReviews(pages.flatMap((p) => p.reviews))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setVotingId(undefined)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-bold text-on-surface">
          Customer reviews
          {totalElements > 0 && <span className="font-normal text-on-surface-muted"> ({totalElements})</span>}
        </h3>
        <ThemedSelect id="review-sort" label="Sort by" value={sort} options={SORTS} onChange={setSort} />
      </div>

      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}

      {loading ? (
        <div role="status" aria-label="Loading reviews" className="space-y-3">
          <div className="h-24 w-full rounded-2xl skeleton-loading" />
          <div className="h-24 w-full rounded-2xl skeleton-loading" />
        </div>
      ) : reviews.length === 0 ? (
        !error && <p className="text-sm text-on-surface-muted">No reviews yet.</p>
      ) : (
        <ul className="divide-y divide-outline">
          {reviews.map((review) => {
            const own = review.id === ownReviewId
            return (
              <li key={review.id} className="space-y-2 py-4 first:pt-0">
                <div className="flex flex-wrap items-center gap-2">
                  <ReviewStars value={review.ratingValue} />
                  {review.reviewTitle && <span className="font-bold text-on-surface">{review.reviewTitle}</span>}
                </div>
                <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-on-surface-muted">
                  <span className="font-semibold text-on-surface">{review.userDisplayName ?? 'Verified buyer'}</span>
                  {own && <span className="rounded bg-surface-variant px-1.5 py-0.5 font-semibold">Your review</span>}
                  {review.isVerifiedPurchase && (
                    <span className="inline-flex items-center gap-0.5 rounded bg-primary-container px-1.5 py-0.5 font-semibold text-on-primary-container">
                      <CheckBadgeIcon className="h-3.5 w-3.5" /> Verified purchase
                    </span>
                  )}
                  <time dateTime={review.createdAt}>{new Date(review.createdAt).toLocaleDateString()}</time>
                </p>
                {review.reviewText && (
                  <p className="whitespace-pre-line break-words leading-relaxed text-on-surface">{review.reviewText}</p>
                )}
                {!own && (
                  <div className="flex flex-wrap items-center gap-2 pt-1">
                    <button
                      type="button"
                      onClick={() => void vote(review, 'HELPFUL')}
                      disabled={votingId === review.id}
                      aria-label={`Mark review as helpful (${review.helpfulCount} found it helpful)`}
                      className={`${secondaryButtonClass} !px-3 !py-1 !text-xs`}
                    >
                      Helpful ({review.helpfulCount})
                    </button>
                    <button
                      type="button"
                      onClick={() => void vote(review, 'UNHELPFUL')}
                      disabled={votingId === review.id}
                      aria-label={`Mark review as not helpful (${review.unhelpfulCount} found it not helpful)`}
                      className={`${secondaryButtonClass} !px-3 !py-1 !text-xs`}
                    >
                      Not helpful ({review.unhelpfulCount})
                    </button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {!loading && pagesLoaded < totalPages && (
        <button
          type="button"
          onClick={() => void loadMore()}
          disabled={loadingMore}
          className={secondaryButtonClass}
          aria-label="Load more reviews"
        >
          {loadingMore ? 'Loading...' : 'Load more reviews'}
        </button>
      )}
    </div>
  )
}
