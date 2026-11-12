/**
 * Side-by-side comparison (up to 4): price, rating, brand and the union of specification / attribute keys,
 * with rows whose values differ highlighted. Lazy route.
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ProductImage } from '../components/ProductImage'
import { RatingChip } from '../components/RatingChip'
import { CloseIcon } from '../components/Icons'
import { formatMoney } from '../lib/format'
import { effectivePrice } from '../lib/price'
import { productPath } from '../lib/productPath'
import { productApi } from '../services/productApi'
import { useCompareStore } from '../stores/compareStore'
import type { Product } from '../types'

function specValues(p: Product): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(p.specifications ?? {})) out[k] = typeof v === 'object' ? JSON.stringify(v) : String(v)
  for (const [k, v] of Object.entries(p.attributes ?? {})) out[k] = v
  if (p.color) out['color'] = p.color
  if (p.material) out['material'] = p.material
  if (p.weightGrams != null) out['weight'] = `${p.weightGrams} g`
  return out
}

export default function ComparePage() {
  const entries = useCompareStore((s) => s.items)
  const remove = useCompareStore((s) => s.remove)
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const ids = entries.map((e) => e.id).join(',')

  useEffect(() => {
    let live = true
    setLoading(true)
    void Promise.all(ids.split(',').filter(Boolean).map((id) => productApi.detail(id).catch(() => undefined))).then((list) => {
      if (!live) return
      setProducts(list.filter((p): p is Product => !!p))
      setLoading(false)
    })
    return () => {
      live = false
    }
  }, [ids])

  if (entries.length === 0) {
    return (
      <div className="mx-auto max-w-md rounded-xl border border-outline bg-surface px-6 py-16 text-center shadow-card">
        <h1 className="text-xl font-bold text-on-surface">Nothing to compare yet</h1>
        <p className="mt-2 text-sm text-on-surface-muted">Use the Compare toggle on product cards to pick up to 4 products.</p>
        <Link to="/" className="press shine bg-cta-gradient mt-6 inline-block rounded-xl px-5 py-2.5 font-semibold text-on-accent shadow-cta">
          Browse products
        </Link>
      </div>
    )
  }

  const specs = products.map(specValues)
  const keys = [...new Set(specs.flatMap((s) => Object.keys(s)))].sort()
  const differs = (values: (string | undefined)[]) => new Set(values).size > 1

  const rows: { label: string; cells: React.ReactNode[]; diff: boolean }[] = [
    {
      label: 'Price',
      cells: products.map((p) => <strong key={p.id}>{formatMoney(effectivePrice(p), p.priceCurrency)}</strong>),
      diff: differs(products.map((p) => String(effectivePrice(p)))),
    },
    {
      label: 'Rating',
      cells: products.map((p) => <RatingChip key={p.id} rating={p.averageRating} count={p.ratingCount} />),
      diff: differs(products.map((p) => String(p.averageRating))),
    },
    {
      label: 'Brand',
      cells: products.map((p) => p.brand ?? '—'),
      diff: differs(products.map((p) => p.brand ?? '')),
    },
    ...keys.map((key) => ({
      label: key,
      cells: specs.map((s) => s[key] ?? '—'),
      diff: differs(specs.map((s) => s[key])),
    })),
  ]

  return (
    <div className="space-y-4">
      <h1 className="font-display text-3xl font-extrabold text-on-surface">Compare products</h1>
      <p className="text-sm text-on-surface-muted">Rows with differences are highlighted.</p>
      {loading ? (
        <div role="status" aria-label="Loading comparison" className="h-64 w-full rounded-2xl skeleton-loading" />
      ) : (
        <div className="overflow-x-auto rounded-[1.75rem] border-2 border-outline bg-surface shadow-card">
          <table className="w-full min-w-[640px] border-collapse text-left text-sm">
            <caption className="sr-only">Product comparison</caption>
            <thead>
              <tr>
                <th scope="col" className="w-36 p-3" />
                {products.map((p) => (
                  <th key={p.id} scope="col" className="min-w-[10rem] p-3 align-top">
                    <div className="relative space-y-2">
                      <button
                        type="button"
                        onClick={() => remove(p.id)}
                        aria-label={`Remove ${p.name} from comparison`}
                        className="absolute right-0 top-0 rounded-full p-1 text-on-surface-muted hover:bg-error/15 hover:text-error"
                      >
                        <CloseIcon className="h-4 w-4" />
                      </button>
                      <Link to={productPath(p)} className="block h-28 w-28 overflow-hidden rounded-xl bg-surface-variant">
                        <ProductImage src={p.images[0]} alt={p.name} productId={p.id} />
                      </Link>
                      <Link to={productPath(p)} className="line-clamp-2 font-bold text-on-surface hover:text-primary">
                        {p.name}
                      </Link>
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.label} className={`border-t border-outline ${row.diff ? 'bg-vivid-amber/15' : ''}`}>
                  <th scope="row" className="p-3 font-semibold capitalize text-on-surface">
                    {row.label}
                    {row.diff && <span className="sr-only"> (differs)</span>}
                  </th>
                  {row.cells.map((cell, i) => (
                    <td key={products[i]?.id ?? i} className="p-3 text-on-surface">
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
