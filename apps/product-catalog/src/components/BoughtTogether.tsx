/**
 * "Frequently bought together": the current product plus co-purchased products, with a combined price
 * and one-click add-all. Products that need a variant choice are linked instead of added.
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ProductImage } from './ProductImage'
import { primaryButtonClass } from './Form'
import { formatMoney } from '../lib/format'
import { effectivePrice } from '../lib/price'
import { productPath } from '../lib/productPath'
import { discoveryApi } from '../services/discoveryApi'
import { useCartStore, useUiStore } from '../stores'
import type { Product } from '../types'

interface Props {
  product: Product
  /** Resolved unit price / variant of the current product; null while a variant is still to be chosen. */
  current: { price: number; variant?: { id: string; label: string; image?: string | null } } | null
}

export function BoughtTogether({ product, current }: Props) {
  const [others, setOthers] = useState<Product[]>([])
  const [unchecked, setUnchecked] = useState<Set<string>>(new Set())
  const addItem = useCartStore((s) => s.addItem)
  const notify = useUiStore((s) => s.notify)

  useEffect(() => {
    let live = true
    setOthers([])
    setUnchecked(new Set())
    discoveryApi
      .boughtTogether(product.id, 4)
      .then((entries) => discoveryApi.batch(entries.map((e) => e.productId).filter((id) => id !== product.id)))
      .then((p) => {
        if (live) setOthers(p.filter((x) => x.active))
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [product.id])

  if (others.length === 0) return null

  const addable = others.filter((p) => !p.hasVariants && !unchecked.has(p.id) && p.lastKnownQuantity !== 0)
  const includeCurrent = current != null
  const total = (includeCurrent ? current.price : 0) + addable.reduce((sum, p) => sum + effectivePrice(p), 0)
  const count = addable.length + (includeCurrent ? 1 : 0)

  const toggle = (id: string) =>
    setUnchecked((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const addAll = () => {
    if (current) {
      addItem(
        product.id,
        1,
        { name: product.name, priceAmount: current.price, priceCurrency: product.priceCurrency, image: current.variant?.image ?? product.images[0] },
        current.variant ? { variantId: current.variant.id, variantLabel: current.variant.label } : undefined,
      )
    }
    for (const p of addable) {
      addItem(p.id, 1, { name: p.name, priceAmount: effectivePrice(p), priceCurrency: p.priceCurrency, image: p.images[0] })
    }
    notify(`Added ${count} item${count === 1 ? '' : 's'} to your cart`)
  }

  return (
    <section aria-labelledby="bought-together-heading" className="space-y-4">
      <h2 id="bought-together-heading" className="font-display text-2xl font-extrabold text-on-surface">
        Frequently bought together
      </h2>
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {others.map((p) => {
          const needsChoice = p.hasVariants
          return (
            <li key={p.id} className="flex gap-3 rounded-2xl border-2 border-outline bg-surface p-3">
              <div className="h-16 w-16 shrink-0 overflow-hidden rounded-lg bg-surface-variant">
                <ProductImage src={p.images[0]} alt="" productId={p.id} />
              </div>
              <div className="min-w-0 flex-1 space-y-1">
                <Link to={productPath(p)} className="line-clamp-2 text-sm font-semibold text-on-surface hover:text-primary">
                  {p.name}
                </Link>
                <p className="text-sm font-bold text-on-surface">{formatMoney(effectivePrice(p), p.priceCurrency)}</p>
                {needsChoice ? (
                  <Link to={productPath(p)} className="text-xs font-semibold text-primary hover:underline">
                    Choose options
                  </Link>
                ) : (
                  <label className="flex items-center gap-1.5 text-xs text-on-surface-muted">
                    <input
                      type="checkbox"
                      checked={!unchecked.has(p.id)}
                      onChange={() => toggle(p.id)}
                      className="accent-[hsl(var(--color-primary))]"
                    />
                    Include
                    <span className="sr-only">{` ${p.name}`}</span>
                  </label>
                )}
              </div>
            </li>
          )
        })}
      </ul>
      <div className="flex flex-wrap items-center gap-4">
        <p className="text-on-surface">
          Total for {count} item{count === 1 ? '' : 's'}:{' '}
          <span className="text-xl font-bold">{formatMoney(total, product.priceCurrency)}</span>
        </p>
        <button type="button" onClick={addAll} disabled={count === 0} className={primaryButtonClass}>
          Add {count === 1 ? 'item' : 'all'} to cart
        </button>
        {!includeCurrent && <p className="text-xs text-on-surface-muted">Select options for this product to include it.</p>}
      </div>
    </section>
  )
}
