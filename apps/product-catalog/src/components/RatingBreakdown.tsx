/**
 * Ratings summary: average, counts, and a per-star distribution when the backend has one
 */

import { RatingChip } from './RatingChip'
import { formatCount } from '../lib/format'
import type { RatingCounts } from '../types'

interface Props {
  averageRating: number | null
  ratingCount: number
  reviewCount: number
  distribution: RatingCounts | null
}

export function RatingBreakdown({ averageRating, ratingCount, reviewCount, distribution }: Props) {
  if (averageRating == null || ratingCount === 0) {
    return <p className="text-sm text-on-surface-muted">No ratings yet. Be the first to rate this product.</p>
  }

  const total = distribution ? Object.values(distribution).reduce((sum, n) => sum + n, 0) : 0

  return (
    <div className="grid gap-6 sm:grid-cols-[auto_1fr] sm:items-center">
      <div className="text-center sm:pr-6">
        <div className="font-display text-5xl font-extrabold text-on-surface">{averageRating.toFixed(1)}</div>
        <div className="mt-2 flex justify-center">
          <RatingChip rating={averageRating} size="md" />
        </div>
        <p className="mt-2 text-sm text-on-surface-muted">
          {formatCount(ratingCount)} ratings · {formatCount(reviewCount)} reviews
        </p>
      </div>

      {distribution && total > 0 && (
        <ul className="space-y-1.5" aria-label="Rating distribution">
          {[5, 4, 3, 2, 1].map((star) => {
            const count = distribution[String(star)] ?? 0
            const percent = Math.round((count / total) * 100)
            return (
              <li key={star} className="grid grid-cols-[2.5rem_1fr_3rem] items-center gap-2 text-sm">
                <span className="text-on-surface-muted">{star} ★</span>
                <span className="h-2 overflow-hidden rounded-full bg-surface-variant">
                  <span
                    className={`block h-full rounded-full ${star >= 3 ? 'bg-primary' : star === 2 ? 'bg-accent' : 'bg-error'}`}
                    style={{ width: `${percent}%` }}
                  />
                </span>
                <span className="text-right text-on-surface-muted">{formatCount(count)}</span>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
