/**
 * Sort dropdown for the catalog
 */

import type { ProductSort } from '../types'
import { ThemedSelect } from './ThemedSelect'

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
  return <ThemedSelect label="Sort by" value={value ?? 'relevance'} options={SORT_OPTIONS} onChange={onChange} />
}
