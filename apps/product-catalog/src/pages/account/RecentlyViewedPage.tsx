/**
 * Recently viewed products (this browser's history, newest first) inside the account area.
 * History lives in localStorage (lib/recentlyViewed); products are hydrated in one batch call.
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { CardSkeleton } from '../../components/account/SectionCard'
import { cardClass, secondaryButtonClass } from '../../components/Form'
import { ClockIcon, CloseIcon } from '../../components/Icons'
import { ProductCard } from '../../components/ProductCard'
import { clearRecentlyViewed, getRecentlyViewed, removeRecentlyViewed } from '../../lib/recentlyViewed'
import { discoveryApi } from '../../services/discoveryApi'
import type { Product } from '../../types'

export function RecentlyViewedPage() {
  const [products, setProducts] = useState<Product[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)

  useEffect(() => {
    let live = true
    const ids = getRecentlyViewed()
    if (ids.length === 0) {
      setProducts([])
      return
    }
    discoveryApi
      .batch(ids)
      .then((p) => live && setProducts(p))
      .catch(() => {
        if (!live) return
        setFailed(true)
        setProducts([])
      })
    return () => {
      live = false
    }
  }, [])

  const remove = (id: string) => {
    removeRecentlyViewed(id)
    setProducts((p) => p?.filter((x) => x.id !== id) ?? null)
  }

  const clearAll = () => {
    clearRecentlyViewed()
    setProducts([])
    setConfirmClear(false)
  }

  if (products === null) return <CardSkeleton rows={3} />

  if (products.length === 0) {
    return (
      <div className={`${cardClass} flex flex-col items-center gap-3 py-14 text-center`}>
        <ClockIcon className="h-12 w-12 text-on-surface-muted" />
        <p className="font-bold text-on-surface">{failed ? 'Couldn’t load your history' : 'Nothing here yet'}</p>
        <p className="max-w-sm text-sm text-on-surface-muted">
          {failed
            ? 'The catalog is unreachable right now. Your history is safe on this device — try again shortly.'
            : 'Products you look at are remembered on this device so you can find them again.'}
        </p>
        <Link
          to="/"
          className="press shine bg-cta-gradient mt-1 rounded-full border-2 border-ink px-5 py-2.5 text-sm font-bold text-on-accent shadow-cta"
        >
          Browse the catalog
        </Link>
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="font-display text-2xl text-on-surface">Recently viewed</h2>
          <p className="text-sm text-on-surface-muted">
            {products.length} {products.length === 1 ? 'product' : 'products'}, newest first. Saved on this device only.
          </p>
        </div>
        {confirmClear ? (
          <div className="flex items-center gap-2" role="group" aria-label="Confirm clearing history">
            <span className="text-sm font-bold text-on-surface">Clear all history?</span>
            <button type="button" className={`${secondaryButtonClass} !text-error`} onClick={clearAll}>
              Yes, clear
            </button>
            <button type="button" className={secondaryButtonClass} onClick={() => setConfirmClear(false)}>
              Keep
            </button>
          </div>
        ) : (
          <button type="button" className={secondaryButtonClass} onClick={() => setConfirmClear(true)}>
            Clear history
          </button>
        )}
      </div>

      <ul className="grid grid-cols-2 gap-4 md:grid-cols-3 xl:grid-cols-4">
        {products.map((p) => (
          <li key={p.id} className="group relative">
            <ProductCard product={p} />
            <button
              type="button"
              onClick={() => remove(p.id)}
              aria-label={`Remove ${p.name} from history`}
              className="press absolute -right-2 -top-2 z-10 flex h-8 w-8 items-center justify-center rounded-full border-2 border-ink bg-surface text-on-surface shadow-cta opacity-100 transition-opacity duration-500 hover:text-error sm:opacity-0 sm:group-focus-within:opacity-100 sm:group-hover:opacity-100"
            >
              <CloseIcon className="h-4 w-4" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
