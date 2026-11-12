/**
 * Read-only 1-5 star row for a single review
 */

import { StarIcon } from './Icons'

export function ReviewStars({ value }: { value: number }) {
  return (
    <span role="img" aria-label={`${value} out of 5 stars`} className="inline-flex gap-0.5">
      {[1, 2, 3, 4, 5].map((n) => (
        <StarIcon key={n} className={`h-4 w-4 ${n <= value ? 'text-vivid-amber' : 'text-surface-variant'}`} />
      ))}
    </span>
  )
}
