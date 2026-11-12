/**
 * Account: price-drop and back-in-stock alerts, with removal.
 */

import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ErrorAlert } from '../../components/ErrorAlert'
import { LoadingBlock, cardClass, secondaryButtonClass } from '../../components/Form'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { productPath } from '../../lib/productPath'
import { discoveryApi } from '../../services/discoveryApi'
import { productApi } from '../../services/productApi'
import { useUiStore } from '../../stores'
import type { Product } from '../../types'

export function AlertsPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => discoveryApi.listAlerts(), [])
  const { data: alerts, loading, error, reload } = useLoad(load)
  const [products, setProducts] = useState<Record<string, Product>>({})

  const ids = [...new Set((alerts ?? []).map((a) => a.productId))].join(',')
  useEffect(() => {
    if (!ids) return
    let live = true
    void Promise.all(ids.split(',').map((id) => productApi.detail(id).catch(() => undefined))).then((list) => {
      if (live) setProducts(Object.fromEntries(list.filter((p): p is Product => !!p).map((p) => [p.id, p])))
    })
    return () => {
      live = false
    }
  }, [ids])

  const remove = async (id: string) => {
    try {
      await discoveryApi.removeAlert(id)
      notify('Alert removed')
      reload()
    } catch (e) {
      notify(errorMessage(e))
    }
  }

  return (
    <section aria-labelledby="alerts-title" className={`${cardClass} space-y-4`}>
      <h2 id="alerts-title" className="font-display text-2xl font-extrabold text-on-surface">
        Alerts
      </h2>
      {loading && !alerts ? (
        <LoadingBlock label="Loading alerts" />
      ) : error ? (
        <ErrorAlert message={errorMessage(error)} />
      ) : !alerts || alerts.length === 0 ? (
        <p className="text-sm text-on-surface-muted">
          You have no alerts. Use &ldquo;Alert me on price drop&rdquo; or &ldquo;Notify me&rdquo; on a product page.
        </p>
      ) : (
        <ul className="divide-y divide-outline">
          {alerts.map((a) => {
            const product = products[a.productId]
            return (
              <li key={a.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <Link to={product ? productPath(product) : `/products/${a.productId}`} className="font-semibold text-on-surface hover:text-primary">
                    {product?.name ?? a.productName ?? 'Product'}
                  </Link>
                  <p className="text-sm text-on-surface-muted">
                    {a.type === 'PRICE_DROP'
                      ? a.targetPrice
                        ? `Price drop to ${a.targetPrice} or below`
                        : 'Any price drop'
                      : 'Back in stock'}
                    {!a.active && a.triggeredAt && ` · sent ${new Date(a.triggeredAt).toLocaleDateString()}`}
                    {a.active && ' · active'}
                  </p>
                </div>
                <button type="button" onClick={() => void remove(a.id)} className={`${secondaryButtonClass} text-error`} aria-label="Remove alert">
                  Remove
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
