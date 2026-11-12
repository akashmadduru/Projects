/**
 * Ratings & reviews for a product: live summary, buyer-only "write a review" area, and all reviews
 */

import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ErrorAlert } from './ErrorAlert'
import { primaryButtonClass, secondaryButtonClass } from './Form'
import { RatingBreakdown } from './RatingBreakdown'
import { ReviewForm } from './ReviewForm'
import { ReviewList } from './ReviewList'
import { ReviewStars } from './ReviewStars'
import { useLoad } from '../hooks/useLoad'
import { errorMessage } from '../lib/errors'
import { ratingApi } from '../services/ratingApi'
import { useAuthStore, useUiStore } from '../stores'
import type { Product, Review } from '../types'

interface Props {
  product: Product
}

export function ReviewsSection({ product }: Props) {
  const productId = product.id
  const location = useLocation()
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const notify = useUiStore((s) => s.notify)
  const [refreshKey, setRefreshKey] = useState(0)
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [actionError, setActionError] = useState<string | undefined>()

  const loadSummary = useCallback(() => ratingApi.summary(productId), [productId])
  const { data: summary, reload: reloadSummary } = useLoad(loadSummary)
  const loadMine = useCallback(
    () => (authenticated ? ratingApi.mine(productId) : Promise.resolve(undefined)),
    [authenticated, productId],
  )
  const { data: mine, reload: reloadMine, loading: mineLoading, error: mineError } = useLoad(loadMine)

  // Arriving from an order ("Rate & review") opens the form as soon as eligibility is known.
  useEffect(() => {
    if (location.hash === '#write-review' && mine?.canReview) setEditing(true)
  }, [location.hash, mine?.canReview])

  useEffect(() => {
    setEditing(false)
  }, [productId])

  // The product page renders after the browser's own anchor scroll, so scroll to deep links here.
  useEffect(() => {
    if (location.hash === '#write-review' || location.hash === '#reviews') {
      document.getElementById(location.hash.slice(1))?.scrollIntoView({ block: 'start' })
    }
  }, [location.hash, productId])

  const refresh = () => {
    setRefreshKey((n) => n + 1)
    reloadSummary()
    reloadMine()
  }

  const onSaved = () => {
    setEditing(false)
    notify(mine?.review ? 'Review updated' : 'Review posted')
    refresh()
  }

  const onDelete = async (review: Review) => {
    if (!window.confirm('Delete your review?')) return
    setDeleting(true)
    setActionError(undefined)
    try {
      await ratingApi.remove(review.id)
      notify('Review deleted')
      refresh()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  const next = encodeURIComponent(`${location.pathname}#write-review`)
  const myReview = mine?.review ?? undefined

  return (
    <div className="space-y-6">
      <RatingBreakdown
        averageRating={summary ? summary.averageRating : product.averageRating}
        ratingCount={summary ? summary.ratingCount : product.ratingCount}
        reviewCount={summary ? summary.reviewCount : product.reviewCount}
        distribution={summary ? summary.ratingDistribution : product.ratingDistribution}
      />

      <div id="write-review" className="scroll-mt-24 space-y-3 border-t border-outline pt-5">
        <h3 className="font-bold text-on-surface">Write a review</h3>
        {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}
        {!authenticated ? (
          <div className="space-y-3">
            <p className="text-sm text-on-surface-muted">Sign in to review products you have bought.</p>
            <Link to={`/login?next=${next}`} className={primaryButtonClass}>
              Sign in to review
            </Link>
          </div>
        ) : mineLoading && !mine ? (
          <div role="status" aria-label="Checking review eligibility" className="h-12 w-full rounded-xl skeleton-loading" />
        ) : mineError ? (
          <ErrorAlert message={errorMessage(mineError)} />
        ) : myReview ? (
          editing ? (
            <ReviewForm productId={productId} existing={myReview} onSaved={onSaved} onCancel={() => setEditing(false)} />
          ) : (
            <div className="space-y-2 rounded-2xl bg-surface-variant p-4">
              <p className="text-sm font-semibold text-on-surface">Your review</p>
              <div className="flex flex-wrap items-center gap-2">
                <ReviewStars value={myReview.ratingValue} />
                {myReview.reviewTitle && <span className="font-bold text-on-surface">{myReview.reviewTitle}</span>}
              </div>
              {myReview.reviewText && (
                <p className="whitespace-pre-line break-words text-on-surface">{myReview.reviewText}</p>
              )}
              <div className="flex gap-2 pt-1">
                <button
                  type="button"
                  onClick={() => setEditing(true)}
                  aria-label="Edit your review"
                  className={secondaryButtonClass}
                >
                  Edit
                </button>
                <button
                  type="button"
                  onClick={() => void onDelete(myReview)}
                  disabled={deleting}
                  aria-label="Delete your review"
                  className={`${secondaryButtonClass} text-error`}
                >
                  {deleting ? 'Deleting...' : 'Delete'}
                </button>
              </div>
            </div>
          )
        ) : mine?.canReview ? (
          editing ? (
            <ReviewForm productId={productId} onSaved={onSaved} onCancel={() => setEditing(false)} />
          ) : (
            <button type="button" onClick={() => setEditing(true)} className={primaryButtonClass}>
              Write a review
            </button>
          )
        ) : (
          <p className="text-sm text-on-surface-muted">Only customers who bought this item can review it.</p>
        )}
      </div>

      <div className="border-t border-outline pt-5">
        <ReviewList
          productId={productId}
          refreshKey={refreshKey}
          authenticated={authenticated}
          ownReviewId={myReview?.id}
        />
      </div>
    </div>
  )
}
