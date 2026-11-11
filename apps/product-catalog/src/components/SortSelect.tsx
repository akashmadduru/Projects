/**
 * Sort dropdown for the catalog
 */

import type { ProductSort } from '../types'

export const SORT_OPTIONS: { value: ProductSort; label: string }[] = [
  { value: 'relevance', label: 'Relevance' },
  { value: 'price-asc', label: 'Price: Low to High' },
  { value: 'price-desc', label: 'Price: High to Low' },
  { value: 'rating', label: 'Customer rating' },
  { value: 'discount', label: 'Biggest discount' },
  { value: 'newest', label: 'Newest first' },
]

interface Props {
  value: ProductSort | undefined
  onChange: (sort: ProductSort) => void
}

export function SortSelect({ value, onChange }: Props) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="whitespace-nowrap text-on-surface-muted">Sort by</span>
      <select
        value={value ?? 'relevance'}
        onChange={(e) => onChange(e.target.value as ProductSort)}
        className="cursor-pointer rounded-xl border border-outline bg-surface px-3 py-2 font-medium text-on-surface transition-all hover:border-primary/50 focus:border-primary focus:shadow-[0_0_0_3px_hsl(var(--color-primary)/0.2)]"
      >
        {SORT_OPTIONS.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  )
}
