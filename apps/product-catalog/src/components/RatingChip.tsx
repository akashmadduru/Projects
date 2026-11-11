/**
 * Compact rating chip ("4.5 ★") with an optional count, in the storefront's listing style
 */

import { StarIcon } from './Icons'
import { formatCount } from '../lib/format'

interface Props {
  rating: number | null
  count?: number
  size?: 'sm' | 'md'
}

export function RatingChip({ rating, count, size = 'sm' }: Props) {
  if (rating == null) {
    return <span className="text-xs text-on-surface-muted">No ratings yet</span>
  }

  const chip = size === 'md' ? 'text-sm px-2 py-0.5' : 'text-xs px-1.5 py-0.5'
  // Score-coded like marketplace rating chips: green is good, amber is fair, coral is poor.
  const tone =
    rating >= 4
      ? 'bg-success text-on-primary'
      : rating >= 3
        ? 'bg-vivid-amber text-on-accent'
        : 'bg-vivid-coral text-on-accent'
  return (
    <span className="inline-flex items-center gap-2">
      <span
        className={`inline-flex items-center gap-0.5 rounded-md font-semibold shadow-sm ${tone} ${chip}`}
        aria-label={`Rated ${rating.toFixed(1)} out of 5`}
      >
        {rating.toFixed(1)}
        <StarIcon className={size === 'md' ? 'w-3.5 h-3.5' : 'w-3 h-3'} />
      </span>
      {count != null && count > 0 && (
        <span className="text-xs text-on-surface-muted">({formatCount(count)})</span>
      )}
    </span>
  )
}
