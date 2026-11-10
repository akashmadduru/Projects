/**
 * StarRating component - displays product rating with stars
 */

import React from 'react'

interface Props {
  rating?: number
  reviewsCount?: number
  size?: 'sm' | 'md' | 'lg'
  showCount?: boolean
}

function StarRatingComponent({ rating = 0, reviewsCount = 0, size = 'md', showCount = true }: Props) {
  const sizeClasses = {
    sm: 'w-3 h-3',
    md: 'w-4 h-4',
    lg: 'w-5 h-5',
  }

  const textSizeClasses = {
    sm: 'text-xs',
    md: 'text-sm',
    lg: 'text-base',
  }

  const fullStars = Math.floor(rating)
  const hasHalfStar = rating % 1 >= 0.5

  const StarIcon = ({ filled, half, index }: { filled: boolean; half: boolean; index: number }) => (
    <svg
      viewBox="0 0 20 20"
      className={`${sizeClasses[size]} ${filled || half ? 'text-accent' : 'text-on-surface-muted'} opacity-${filled || half ? '100' : '30'} animate-star-fill`}
      fill="currentColor"
      style={{
        animationDelay: `${index * 0.1}s`,
      }}
    >
      {half ? (
        <>
          <defs>
            <mask id="half">
              <rect width="10" height="20" fill="white" />
            </mask>
          </defs>
          <path d="M10 15l-5.878 3.09 1.123-6.545L.489 6.91l6.572-.955L10 0l2.939 5.955 6.572.955-4.756 4.635 1.123 6.545z" />
        </>
      ) : (
        <path d="M10 15l-5.878 3.09 1.123-6.545L.489 6.91l6.572-.955L10 0l2.939 5.955 6.572.955-4.756 4.635 1.123 6.545z" />
      )}
    </svg>
  )

  return (
    <div className="flex items-center gap-2">
      <div className="flex items-center gap-0.5">
        {[1, 2, 3, 4, 5].map((star) => (
          <StarIcon
            key={star}
            filled={star <= fullStars}
            half={star === fullStars + 1 && hasHalfStar}
            index={star - 1}
          />
        ))}
      </div>

      {showCount && (
        <span className={`${textSizeClasses[size]} text-on-surface-muted font-medium animate-counter`}>
          {rating.toFixed(1)} ({reviewsCount} reviews)
        </span>
      )}
    </div>
  )
}

export const StarRating = React.memo(StarRatingComponent)
