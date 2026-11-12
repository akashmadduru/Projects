/**
 * Deals of the Day: a lit theatre-marquee panel on the catalog home. Live deals with % off,
 * per-deal countdown and claimed progress; the header counts down to the soonest-ending deal.
 * Renders nothing once loaded if there are no live deals (a dead backend never leaves a hole).
 */

import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Starburst } from './Atomic'
import { Countdown } from './Countdown'
import { Carousel } from './Carousel'
import { ArrowRightIcon } from './Icons'
import { ProductImage } from './ProductImage'
import { toSearchString } from '../hooks'
import { useDealsState } from '../hooks/useDeals'
import { formatMoney } from '../lib/format'
import { effectivePrice } from '../lib/price'
import { productPath } from '../lib/productPath'
import { discoveryApi } from '../services/discoveryApi'
import type { Deal, Product } from '../types'

const MAX_DEALS = 12

function percentOff(deal: Deal, product: Product): number | null {
  const normal = effectivePrice(product)
  if (deal.currency !== product.priceCurrency || !(normal > deal.dealPrice)) return null
  return Math.round((1 - deal.dealPrice / normal) * 100)
}

function DealCard({ deal, product }: { deal: Deal; product: Product }) {
  const normal = effectivePrice(product)
  const off = percentOff(deal, product)
  const pct = deal.quantityLimit ? Math.min(100, Math.round((deal.claimed / deal.quantityLimit) * 100)) : null
  const almostGone = deal.remaining != null && deal.remaining <= 5

  return (
    <Link
      to={productPath(product)}
      className="press group flex h-full w-full flex-col overflow-hidden rounded-[1.75rem] border-2 border-ink bg-surface shadow-card transition-transform duration-700 hover:-translate-y-1 hover:shadow-card-hover focus-visible:-translate-y-1"
    >
      <div className="relative aspect-[4/3] border-b-2 border-ink bg-surface-variant">
        <ProductImage src={product.images[0]} alt={product.name} productId={product.id} />
        {off != null && off > 0 && (
          <span className="absolute right-2 top-2 flex h-14 w-14 items-center justify-center" aria-label={`${off}% off`}>
            <Starburst className="absolute inset-0 h-full w-full text-deal drop-shadow-[2px_2px_0_hsl(var(--color-ink))] transition-transform duration-1000 group-hover:rotate-45" />
            <span className="font-display relative -rotate-12 text-center text-xs leading-none text-white">
              <span className="block text-base">{off}%</span>OFF
            </span>
          </span>
        )}
        <span className="absolute bottom-2 left-2 max-w-[75%] truncate rounded-full border-2 border-ink bg-vivid-amber px-2.5 py-0.5 text-[11px] font-bold uppercase tracking-wide text-ink">
          {deal.title || 'Deal'}
        </span>
      </div>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <p className="line-clamp-2 min-h-[2.5rem] text-sm font-bold text-on-surface">{product.name}</p>
        <p className="flex flex-wrap items-baseline gap-x-2">
          <span className="font-display text-xl text-deal">{formatMoney(deal.dealPrice, deal.currency)}</span>
          {off != null && (
            <span className="text-sm text-on-surface-muted line-through">{formatMoney(normal, product.priceCurrency)}</span>
          )}
        </p>
        {pct != null && (
          <div className="mt-auto">
            <div
              className="h-2.5 overflow-hidden rounded-full border-2 border-ink bg-surface-variant"
              role="progressbar"
              aria-valuenow={pct}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label="Claimed"
            >
              <div className="bg-deal-gradient h-full transition-[width] duration-1000" style={{ width: `${Math.max(pct, 4)}%` }} />
            </div>
            <p className="mt-1 flex justify-between text-xs">
              <span className={almostGone ? 'font-bold text-deal' : 'text-on-surface-muted'}>
                {almostGone ? `Only ${deal.remaining} left!` : `${pct}% claimed`}
              </span>
            </p>
          </div>
        )}
        <Countdown endsAt={deal.endsAt} className={`text-xs font-semibold text-error ${pct == null ? 'mt-auto' : ''}`} />
      </div>
    </Link>
  )
}

function DealSkeleton() {
  return (
    <div className="w-[15.5rem] shrink-0 overflow-hidden rounded-[1.75rem] border-2 border-ink/30 bg-surface" aria-hidden="true">
      <div className="skeleton-loading aspect-[4/3]" />
      <div className="space-y-2 p-4">
        <div className="skeleton-loading h-4 w-4/5 rounded" />
        <div className="skeleton-loading h-5 w-1/2 rounded" />
        <div className="skeleton-loading h-2.5 w-full rounded-full" />
      </div>
    </div>
  )
}

export function DealsStrip() {
  const { deals, loaded } = useDealsState()
  const [products, setProducts] = useState<Product[] | null>(null)
  const ids = [...new Set(deals.map((d) => d.productId))].slice(0, MAX_DEALS).join(',')

  useEffect(() => {
    if (!loaded) return
    if (!ids) {
      setProducts([])
      return
    }
    let live = true
    discoveryApi
      .batch(ids.split(','))
      .then((p) => live && setProducts(p))
      .catch(() => live && setProducts([]))
    return () => {
      live = false
    }
  }, [ids, loaded])

  const rows = useMemo(
    () =>
      (products ?? []).length === 0
        ? []
        : deals
            .flatMap((deal) => {
              const product = products!.find((p) => p.id === deal.productId)
              return product ? [{ deal, product }] : []
            })
            .sort((a, b) => Date.parse(a.deal.endsAt) - Date.parse(b.deal.endsAt))
            .slice(0, MAX_DEALS),
    [deals, products],
  )
  const soonest = rows[0]?.deal.endsAt

  const loading = !loaded || products === null
  if (!loading && rows.length === 0) return null

  return (
    <section
      aria-labelledby="deals-heading"
      className="bg-brand-gradient relative isolate overflow-hidden rounded-[2rem] border-2 border-ink px-4 pb-6 pt-7 shadow-card-hover sm:px-6"
    >
      {/* Marquee sign: bulbs top and bottom, a sweeping searchlight. Decorative only. */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
        <div className="bg-atomic-pattern absolute inset-0 !opacity-15" />
        <div className="searchlight" />
      </div>
      <div aria-hidden="true" className="bulb-string bulb-string--warm absolute inset-x-6 top-2" />
      <div aria-hidden="true" className="bulb-string bulb-string--warm absolute inset-x-6 bottom-1.5" />

      <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
        <div className="space-y-1">
          <p className="flex items-center gap-2 text-xs font-bold uppercase tracking-[0.25em] text-vivid-amber">
            <span className="lamp" aria-hidden="true" /> Live now · today only
          </p>
          <h2 id="deals-heading" className="neon-sign font-display text-3xl sm:text-4xl">
            Deals of the Day
          </h2>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          {soonest && (
            <span className="rounded-full border-2 border-ink bg-ink/40 px-3 py-1.5 font-mono text-sm text-on-brand">
              Next ends in <Countdown endsAt={soonest} prefix="" className="whitespace-nowrap font-bold text-vivid-amber" />
            </span>
          )}
          <Link
            to={{ pathname: '/', search: toSearchString({ onSale: true, sort: 'discount' }) }}
            className="press shine bg-cta-gradient inline-flex items-center gap-1.5 rounded-full border-2 border-ink px-4 py-2 text-sm font-bold text-on-accent shadow-cta"
          >
            All offers <ArrowRightIcon className="h-4 w-4" />
          </Link>
        </div>
      </div>

      {loading ? (
        <div className="flex gap-4 overflow-hidden" aria-hidden="true">
          {Array.from({ length: 4 }, (_, i) => (
            <DealSkeleton key={i} />
          ))}
        </div>
      ) : (
        <Carousel label="Deals of the day" itemMinWidth={248} className="px-1">
          {rows.map(({ deal, product }) => (
            <DealCard key={deal.id} deal={deal} product={product} />
          ))}
        </Carousel>
      )}
    </section>
  )
}
