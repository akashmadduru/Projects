/**
 * Removable chips for every applied filter, plus "Clear all"
 */

import { CloseIcon } from './Icons'
import { PRICE_PRESETS, SWITCHES } from './FilterPanel'
import { formatMoney } from '../lib/format'
import type { Category, ProductQuery } from '../types'

interface Props {
  query: ProductQuery
  categories: Category[]
  onChange: (patch: Partial<ProductQuery>) => void
  onClearAll: () => void
}

function priceLabel(min?: number, max?: number): string {
  const preset = PRICE_PRESETS.find((p) => p.min === min && p.max === max)
  if (preset) return preset.label
  if (min != null && max != null) return `${formatMoney(min, 'USD')} – ${formatMoney(max, 'USD')}`
  return min != null ? `Over ${formatMoney(min, 'USD')}` : `Under ${formatMoney(max ?? 0, 'USD')}`
}

export function ActiveFilters({ query, categories, onChange, onClearAll }: Props) {
  const chips: { key: string; label: string; clear: Partial<ProductQuery> }[] = []
  if (query.q) chips.push({ key: 'q', label: `“${query.q}”`, clear: { q: undefined } })
  if (query.categoryId) {
    const name = categories.find((c) => c.id === query.categoryId)?.name ?? 'Category'
    chips.push({ key: 'category', label: name, clear: { categoryId: undefined } })
  }
  if (query.minPrice != null || query.maxPrice != null) {
    chips.push({
      key: 'price',
      label: priceLabel(query.minPrice, query.maxPrice),
      clear: { minPrice: undefined, maxPrice: undefined },
    })
  }
  if (query.minRating != null) {
    chips.push({ key: 'rating', label: `${query.minRating}★ & above`, clear: { minRating: undefined } })
  }
  if (query.minDiscount != null) {
    chips.push({ key: 'discount', label: `${query.minDiscount}%+ off`, clear: { minDiscount: undefined } })
  }
  if (query.seller) chips.push({ key: 'seller', label: `Seller: ${query.seller}`, clear: { seller: undefined } })
  for (const brand of query.brand ?? []) {
    const rest = (query.brand ?? []).filter((b) => b !== brand)
    chips.push({ key: `brand-${brand}`, label: `Brand: ${brand}`, clear: { brand: rest.length ? rest : undefined } })
  }
  for (const [attr, values] of Object.entries(query.attrs ?? {})) {
    for (const value of values) {
      const attrs = { ...(query.attrs ?? {}) }
      const rest = values.filter((v) => v !== value)
      if (rest.length) attrs[attr] = rest
      else delete attrs[attr]
      chips.push({
        key: `attr-${attr}-${value}`,
        label: `${attr}: ${value}`,
        clear: { attrs: Object.keys(attrs).length ? attrs : undefined },
      })
    }
  }
  for (const item of SWITCHES) {
    if (query[item.key]) chips.push({ key: item.key, label: item.label, clear: { [item.key]: undefined } })
  }

  if (chips.length === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-2">
      {chips.map((chip) => (
        <button
          key={chip.key}
          type="button"
          onClick={() => onChange(chip.clear)}
          className="press group animate-pop inline-flex items-center gap-1.5 rounded-full border border-primary/30 bg-primary-container px-3 py-1 text-sm font-medium text-on-primary-container hover:border-error/50 hover:bg-error/10 hover:text-error"
          aria-label={`Remove filter ${chip.label}`}
        >
          {chip.label}
          <CloseIcon className="w-3.5 h-3.5 transition-transform duration-500 group-hover:rotate-90" />
        </button>
      ))}
      <button type="button" onClick={onClearAll} className="press text-sm font-semibold text-vivid-pink hover:underline">
        Clear all
      </button>
    </div>
  )
}
