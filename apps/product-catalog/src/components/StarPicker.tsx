/**
 * Accessible 1-5 star input: a native radio group, so arrow keys and screen readers work
 */

import { useState } from 'react'
import { StarIcon } from './Icons'

interface Props {
  name: string
  value: number
  onChange: (value: number) => void
  disabled?: boolean
}

const LABELS = ['Terrible', 'Poor', 'Average', 'Good', 'Excellent']

export function StarPicker({ name, value, onChange, disabled }: Props) {
  const [hover, setHover] = useState(0)
  const shown = hover || value

  return (
    <fieldset disabled={disabled} className="space-y-1">
      <legend className="text-sm font-semibold text-on-surface">Your rating</legend>
      <div className="flex items-center gap-1" onMouseLeave={() => setHover(0)}>
        {[1, 2, 3, 4, 5].map((n) => (
          <label
            key={n}
            className="cursor-pointer rounded-md p-0.5 focus-within:ring-2 focus-within:ring-primary"
            onMouseEnter={() => setHover(n)}
          >
            <input
              type="radio"
              name={name}
              value={n}
              checked={value === n}
              onChange={() => onChange(n)}
              className="sr-only"
              aria-label={`${n} ${n === 1 ? 'star' : 'stars'}, ${LABELS[n - 1]}`}
            />
            <StarIcon className={`h-8 w-8 ${n <= shown ? 'text-vivid-amber' : 'text-surface-variant'}`} />
          </label>
        ))}
        <span className="ml-2 text-sm text-on-surface-muted" aria-live="polite">
          {shown > 0 ? LABELS[shown - 1] : 'Select a rating'}
        </span>
      </div>
    </fieldset>
  )
}
