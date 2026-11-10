/**
 * Rating stars display component with distribution
 */

import type { RatingDistribution } from '../types'

interface Props {
  averageRating: number
  ratingCount: number
  distribution?: RatingDistribution
  size?: 'sm' | 'md' | 'lg'
}

function RatingBar({ stars, count, total }: { stars: number; count: number; total: number }) {
  const percentage = total > 0 ? (count / total) * 100 : 0
  return (
    <div className="flex items-center gap-2 text-sm">
      <span className="w-8 text-right text-on-surface-muted">{stars}★</span>
      <div className="flex-1 h-1 bg-surface-variant rounded-full overflow-hidden">
        <div
          className="h-full bg-on-surface transition-all duration-300"
          style={{ width: `${percentage}%` }}
        />
      </div>
      <span className="w-10 text-right text-xs text-on-surface-muted">{count}</span>
    </div>
  )
}

export function RatingStars({ averageRating, ratingCount, distribution, size = 'md' }: Props) {
  const sizeClasses = {
    sm: 'w-3 h-3',
    md: 'w-4 h-4',
    lg: 'w-5 h-5',
  }

  const textClasses = {
    sm: 'text-xs',
    md: 'text-sm',
    lg: 'text-base',
  }

  const renderStars = () => {
    return Array.from({ length: 5 }).map((_, i) => {
      const isFilled = i < Math.floor(averageRating)
      const isHalf = i < averageRating && i === Math.floor(averageRating)

      return (
        <div key={i} className="relative">
          {/* Background star */}
          <svg className={`${sizeClasses[size]} text-surface-variant`} fill="currentColor" viewBox="0 0 20 20">
            <path d="M10 15l-5.878 3.09 1.123-6.545L.489 6.91l6.572-.955L10 0l2.939 5.955 6.572.955-4.756 4.635 1.123 6.545z" />
          </svg>

          {/* Filled star overlay */}
          {(isFilled || isHalf) && (
            <svg
              className={`${sizeClasses[size]} text-on-surface absolute top-0 left-0`}
              fill="currentColor"
              viewBox="0 0 20 20"
              style={{ width: isHalf ? '50%' : '100%', overflow: 'hidden' }}
            >
              <path d="M10 15l-5.878 3.09 1.123-6.545L.489 6.91l6.572-.955L10 0l2.939 5.955 6.572.955-4.756 4.635 1.123 6.545z" />
            </svg>
          )}
        </div>
      )
    })
  }

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <div className="flex gap-0.5">
          {renderStars()}
        </div>
        <div className={`${textClasses[size]} font-medium text-on-surface`}>
          {averageRating.toFixed(1)}
        </div>
        <div className={`${textClasses[size]} text-on-surface-muted`}>
          ({ratingCount} {ratingCount === 1 ? 'review' : 'reviews'})
        </div>
      </div>

      {distribution && (
        <div className="space-y-1">
          <RatingBar stars={5} count={distribution.fiveStars} total={ratingCount} />
          <RatingBar stars={4} count={distribution.fourStars} total={ratingCount} />
          <RatingBar stars={3} count={distribution.threeStars} total={ratingCount} />
          <RatingBar stars={2} count={distribution.twoStars} total={ratingCount} />
          <RatingBar stars={1} count={distribution.oneStar} total={ratingCount} />
        </div>
      )}
    </div>
  )
}
