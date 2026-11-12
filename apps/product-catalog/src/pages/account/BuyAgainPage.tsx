/**
 * Account: products from delivered orders, one click to reorder.
 */

import { useCallback } from 'react'
import { PageShell } from '../../components/account/PageShell'
import { AddToCartButton } from '../../components/AddToCartButton'
import { ProductCard } from '../../components/ProductCard'
import { useLoad } from '../../hooks/useLoad'
import { discoveryApi } from '../../services/discoveryApi'
import { engageApi } from '../../services/engageApi'

export function BuyAgainPage() {
  const load = useCallback(async () => {
    const entries = await engageApi.buyAgain(20)
    const products = await discoveryApi.batch([...new Set(entries.map((e) => e.productId))])
    return products.map((product) => ({ product, entry: entries.find((e) => e.productId === product.id) }))
  }, [])
  const { data, loading, error } = useLoad(load)

  return (
    <PageShell id="buy-again" title="Buy again" intro="Things you have ordered before." loading={loading && !data} error={error}>
      {data && data.length === 0 && <p className="text-sm text-on-surface-muted">Delivered orders will show up here.</p>}
      {data && data.length > 0 && (
        <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {data.map(({ product, entry }) => (
            <li key={product.id} className="space-y-2">
              <ProductCard product={product} />
              {entry && (
                <p className="text-xs text-on-surface-muted">
                  Ordered {entry.timesOrdered} {entry.timesOrdered === 1 ? 'time' : 'times'}
                </p>
              )}
              <AddToCartButton product={product} className="w-full" />
            </li>
          ))}
        </ul>
      )}
    </PageShell>
  )
}
