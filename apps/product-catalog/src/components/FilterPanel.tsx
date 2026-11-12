/**
 * Catalog filters: category tree, price, customer rating, discount. Controlled by the URL query.
 */

import { useEffect, useState } from 'react'
import { StarIcon } from './Icons'
import type { CategoryNode } from '../hooks'
import type { ProductQuery } from '../types'

interface Props {
  query: ProductQuery
  categories: CategoryNode[]
  onChange: (patch: Partial<ProductQuery>) => void
}

export const PRICE_PRESETS: { label: string; min?: number; max?: number }[] = [
  { label: 'Under $25', max: 25 },
  { label: '$25 – $50', min: 25, max: 50 },
  { label: '$50 – $100', min: 50, max: 100 },
  { label: '$100 – $500', min: 100, max: 500 },
  { label: 'Over $500', min: 500 },
]
const RATINGS = [4, 3]
const DISCOUNTS = [10, 15, 20]

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="border-b border-outline py-4 first:pt-0 last:border-b-0">
      <h3 className="mb-3 text-sm uppercase tracking-[0.18em] text-accent-2">{title}</h3>
      {children}
    </section>
  )
}

function Option({
  selected,
  onClick,
  children,
  indent = false,
}: {
  selected: boolean
  onClick: () => void
  children: React.ReactNode
  indent?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={`press flex w-full items-center gap-2 rounded-full border-2 px-3 py-1.5 text-left text-sm transition-all duration-300 ${
        indent ? 'pl-6' : ''
      } ${
        selected
          ? 'bg-brand-gradient border-ink font-bold text-on-brand shadow-[2px_2px_0_hsl(var(--color-ink))]'
          : 'border-transparent text-on-surface hover:translate-x-1 hover:border-outline/30 hover:bg-vivid-amber/25'
      }`}
    >
      <span className="flex-1">{children}</span>
      {selected && (
        <svg viewBox="0 0 24 24" className="w-4 h-4 shrink-0 animate-pop" fill="none" stroke="currentColor" strokeWidth={3} aria-hidden="true">
          <path d="m5 12.5 4.5 4.5L19 7.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      )}
    </button>
  )
}

export function FilterPanel({ query, categories, onChange }: Props) {
  const [minText, setMinText] = useState(query.minPrice?.toString() ?? '')
  const [maxText, setMaxText] = useState(query.maxPrice?.toString() ?? '')

  useEffect(() => {
    setMinText(query.minPrice?.toString() ?? '')
    setMaxText(query.maxPrice?.toString() ?? '')
  }, [query.minPrice, query.maxPrice])

  const toNumber = (text: string) => {
    const value = Number(text)
    return text.trim() !== '' && Number.isFinite(value) && value >= 0 ? value : undefined
  }
  const min = toNumber(minText)
  const max = toNumber(maxText)
  const rangeInvalid = min != null && max != null && min > max

  return (
    <div>
      <Section title="Category">
        <div className="space-y-0.5">
          <Option selected={!query.categoryId} onClick={() => onChange({ categoryId: undefined })}>
            All categories
          </Option>
          {categories.map((root) => {
            const expanded = root.id === query.categoryId || root.children.some((c) => c.id === query.categoryId)
            return (
              <div key={root.id}>
                <Option selected={root.id === query.categoryId} onClick={() => onChange({ categoryId: root.id })}>
                  {root.name}
                </Option>
                {expanded &&
                  root.children.map((child) => (
                    <Option
                      key={child.id}
                      indent
                      selected={child.id === query.categoryId}
                      onClick={() => onChange({ categoryId: child.id })}
                    >
                      {child.name}
                    </Option>
                  ))}
              </div>
            )
          })}
        </div>
      </Section>

      <Section title="Price">
        <div className="space-y-0.5">
          {PRICE_PRESETS.map((preset) => (
            <Option
              key={preset.label}
              selected={query.minPrice === preset.min && query.maxPrice === preset.max}
              onClick={() => onChange({ minPrice: preset.min, maxPrice: preset.max })}
            >
              {preset.label}
            </Option>
          ))}
        </div>
        <form
          className="mt-3"
          onSubmit={(e) => {
            e.preventDefault()
            if (!rangeInvalid) onChange({ minPrice: min, maxPrice: max })
          }}
        >
          <div className="flex items-center gap-2">
            <label className="sr-only" htmlFor="price-min">
              Minimum price
            </label>
            <input
              id="price-min"
              inputMode="decimal"
              placeholder="Min"
              value={minText}
              onChange={(e) => setMinText(e.target.value)}
              className="w-full rounded-full border-2 border-outline bg-surface px-3 py-1.5 text-sm text-on-surface transition-shadow focus:border-primary focus:shadow-[0_0_0_3px_hsl(var(--color-primary)/0.2)]"
            />
            <span className="text-on-surface-muted" aria-hidden="true">
              –
            </span>
            <label className="sr-only" htmlFor="price-max">
              Maximum price
            </label>
            <input
              id="price-max"
              inputMode="decimal"
              placeholder="Max"
              value={maxText}
              onChange={(e) => setMaxText(e.target.value)}
              className="w-full rounded-full border-2 border-outline bg-surface px-3 py-1.5 text-sm text-on-surface transition-shadow focus:border-primary focus:shadow-[0_0_0_3px_hsl(var(--color-primary)/0.2)]"
            />
            <button
              type="submit"
              disabled={rangeInvalid}
              className="press bg-brand-gradient rounded-full border-2 border-ink px-3 py-1.5 text-sm font-bold text-on-brand shadow-[2px_2px_0_hsl(var(--color-ink))] disabled:opacity-50"
            >
              Go
            </button>
          </div>
          {rangeInvalid && (
            <p className="mt-1 text-xs text-error" role="alert">
              Minimum must not exceed maximum.
            </p>
          )}
        </form>
      </Section>

      <Section title="Customer rating">
        <div className="space-y-0.5">
          {RATINGS.map((rating) => (
            <Option
              key={rating}
              selected={query.minRating === rating}
              onClick={() => onChange({ minRating: query.minRating === rating ? undefined : rating })}
            >
              <span className="inline-flex items-center gap-1">
                {rating}
                <StarIcon className="w-3.5 h-3.5 text-vivid-amber drop-shadow-[1px_1px_0_hsl(var(--color-ink))]" /> &amp; above
              </span>
            </Option>
          ))}
        </div>
      </Section>

      <Section title="Discount">
        <div className="space-y-0.5">
          {DISCOUNTS.map((discount) => (
            <Option
              key={discount}
              selected={query.minDiscount === discount}
              onClick={() => onChange({ minDiscount: query.minDiscount === discount ? undefined : discount })}
            >
              {discount}% or more
            </Option>
          ))}
        </div>
      </Section>
    </div>
  )
}
