/**
 * Order detail; doubles as the confirmation screen right after checkout
 */

import { useCallback, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { formatAddressLines } from '../components/AddressForm'
import { ErrorAlert } from '../components/ErrorAlert'
import { LoadingBlock, cardClass, secondaryButtonClass } from '../components/Form'
import { ProductImage } from '../components/ProductImage'
import { useLoad } from '../hooks/useLoad'
import { errorMessage } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { orderApi } from '../services/orderApi'
import { useUiStore } from '../stores'

export function OrderPage() {
  const { id = '' } = useParams()
  const location = useLocation()
  const justPlaced = (location.state as { placed?: boolean } | null)?.placed === true
  const load = useCallback(() => orderApi.get(id), [id])
  const { data: order, error, loading, setData } = useLoad(load)
  const notify = useUiStore((s) => s.notify)
  const [cancelling, setCancelling] = useState(false)
  const [cancelError, setCancelError] = useState<string | undefined>()

  if (loading && !order) return <LoadingBlock label="Loading order" />
  if (error) return <ErrorAlert message={errorMessage(error)} />
  if (!order) return null

  const cancel = async () => {
    if (!window.confirm('Cancel this order?')) return
    setCancelling(true)
    setCancelError(undefined)
    try {
      setData(await orderApi.cancel(order.id))
      notify('Order cancelled')
    } catch (err) {
      setCancelError(errorMessage(err))
    } finally {
      setCancelling(false)
    }
  }

  return (
    <div className="mx-auto max-w-3xl space-y-5">
      {justPlaced && order.status === 'PLACED' && (
        <div className="rounded-xl border-2 border-ink bg-primary-container p-4 text-on-primary-container">
          <p className="font-display text-lg font-bold">Thank you! Your order has been placed.</p>
        </div>
      )}
      {cancelError && <ErrorAlert message={cancelError} onDismiss={() => setCancelError(undefined)} />}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-on-surface">Order #{order.id.slice(0, 8)}</h1>
          <p className="text-sm text-on-surface-muted">Placed {new Date(order.placedAt).toLocaleString()}</p>
        </div>
        <span className="rounded-full border-2 border-ink bg-surface-variant px-3 py-1 text-sm font-bold text-on-surface">
          {order.status}
        </span>
      </div>

      <ul className={`${cardClass} divide-y divide-outline`}>
        {order.items.map((item) => (
          <li key={item.productId} className="flex gap-3 py-3 first:pt-0 last:pb-0">
            <div className="h-16 w-16 shrink-0 overflow-hidden rounded-lg bg-surface-variant">
              <ProductImage src={item.imageUrl ?? undefined} alt={item.name} productId={item.productId} />
            </div>
            <div className="min-w-0 flex-1">
              <Link to={`/products/${item.productId}`} className="line-clamp-2 text-sm font-medium text-on-surface hover:text-primary">
                {item.name}
              </Link>
              <p className="text-xs text-on-surface-muted">
                {item.quantity} x {formatMoney(Number(item.unitPrice), order.currency)}
              </p>
              {order.status !== 'CANCELLED' && (
                <Link
                  to={`/products/${item.productId}#write-review`}
                  aria-label={`Rate and review ${item.name}`}
                  className="mt-1 inline-block text-xs font-semibold text-primary hover:underline"
                >
                  Rate &amp; review
                </Link>
              )}
            </div>
            <span className="font-bold text-on-surface">{formatMoney(Number(item.lineTotal), order.currency)}</span>
          </li>
        ))}
        <li className="flex items-baseline justify-between pt-3">
          <span className="text-on-surface-muted">Total</span>
          <span className="text-xl font-bold text-on-surface">{formatMoney(Number(order.totalAmount), order.currency)}</span>
        </li>
      </ul>

      <div className={cardClass}>
        <h2 className="mb-2 font-bold text-on-surface">Ship to</h2>
        <address className="text-sm not-italic text-on-surface">
          {formatAddressLines(order.shipTo).map((line, i) => (
            <div key={i}>{line}</div>
          ))}
          {order.shipTo.phone && <div className="text-on-surface-muted">{order.shipTo.phone}</div>}
        </address>
      </div>

      <div className="flex flex-wrap gap-3">
        <Link to="/account/orders" className={secondaryButtonClass}>
          All orders
        </Link>
        {order.status === 'PLACED' && (
          <button type="button" onClick={() => void cancel()} disabled={cancelling} className={`${secondaryButtonClass} text-error`}>
            {cancelling ? 'Cancelling...' : 'Cancel order'}
          </button>
        )}
      </div>
    </div>
  )
}
