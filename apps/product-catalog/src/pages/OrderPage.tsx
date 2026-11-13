/**
 * Order detail; doubles as the confirmation screen right after checkout and the return target of the
 * card payment (?payment=return polls until the order leaves PENDING_PAYMENT).
 */

import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom'
import { formatAddressLines } from '../components/AddressForm'
import { ErrorAlert } from '../components/ErrorAlert'
import { Field, LoadingBlock, cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from '../components/Form'
import { PaymentPanel } from '../components/PaymentPanel'
import { ProductImage } from '../components/ProductImage'
import { useLoad } from '../hooks/useLoad'
import { errorMessage } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { orderApi } from '../services/orderApi'
import { paymentApi } from '../services/paymentApi'
import { useUiStore } from '../stores'
import type { Order, OrderShipment, OrderStatus, PaymentInfo, PaymentStatus, ReturnReason, ReturnResponse } from '../types'

const SellerFeedbackForm = lazy(() => import('../components/SellerFeedbackForm'))

const POLL_MS = 2000
const POLL_MAX = 15

const STEPS: { status: OrderStatus; label: string }[] = [
  { status: 'PLACED', label: 'Order placed' },
  { status: 'CONFIRMED', label: 'Confirmed' },
  { status: 'PACKED', label: 'Packed' },
  { status: 'SHIPPED', label: 'Shipped' },
  { status: 'OUT_FOR_DELIVERY', label: 'Out for delivery' },
  { status: 'DELIVERED', label: 'Delivered' },
]

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  PLACED: 'Placed',
  CONFIRMED: 'Confirmed',
  PACKED: 'Packed',
  SHIPPED: 'Shipped',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
}

const PAYMENT_LABEL: Record<PaymentStatus, string> = {
  PENDING: 'Payment pending',
  PAID: 'Paid',
  FAILED: 'Payment failed',
  REFUNDED: 'Refunded',
  PARTIALLY_REFUNDED: 'Partially refunded',
  COD_PENDING: 'Pay on delivery',
  COD_COLLECTED: 'Cash collected',
}

const RETURN_REASONS: { value: ReturnReason; label: string }[] = [
  { value: 'DAMAGED', label: 'Item arrived damaged' },
  { value: 'WRONG_ITEM', label: 'Wrong item received' },
  { value: 'NOT_AS_DESCRIBED', label: 'Not as described' },
  { value: 'NO_LONGER_NEEDED', label: 'No longer needed' },
  { value: 'OTHER', label: 'Other' },
]

const RETURN_STATUS_LABEL: Record<ReturnResponse['status'], string> = {
  REQUESTED: 'Requested',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  PICKED_UP: 'Picked up',
  REFUNDED: 'Refunded',
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

function Timeline({
  status,
  timeline,
  label = 'Order tracking',
}: {
  status: OrderStatus
  timeline: Order['timeline']
  label?: string
}) {
  const at = new Map(timeline.map((t) => [t.status, t]))
  const cancelled = status === 'CANCELLED'
  const steps = cancelled
    ? timeline.map((t) => ({ status: t.status, label: STATUS_LABEL[t.status] }))
    : STEPS
  return (
    <ol className="space-y-0" aria-label={label}>
      {steps.map((step, index) => {
        const entry = at.get(step.status)
        const reached = !!entry
        const current = status === step.status
        const bad = step.status === 'CANCELLED'
        return (
          <li key={`${step.status}-${index}`} className="relative flex gap-3 pb-5 last:pb-0" aria-current={current ? 'step' : undefined}>
            {index < steps.length - 1 && (
              <span
                aria-hidden="true"
                className={`absolute left-[0.6875rem] top-6 h-[calc(100%-1.5rem)] w-0.5 ${reached ? 'bg-ink' : 'bg-outline/40'}`}
              />
            )}
            <span
              aria-hidden="true"
              className={`relative mt-0.5 h-6 w-6 shrink-0 rounded-full border-2 border-ink transition-colors duration-500 ${
                bad ? 'bg-error' : reached ? 'bg-success' : 'bg-surface'
              } ${current ? 'ring-4 ring-primary/30' : ''}`}
            />
            <div className="min-w-0">
              <p className={`font-semibold ${reached ? 'text-on-surface' : 'text-on-surface-muted'}`}>
                {step.label}
                <span className="sr-only">{reached ? (current ? ' (current)' : ' (done)') : ' (upcoming)'}</span>
              </p>
              {entry && <p className="text-xs text-on-surface-muted">{formatWhen(entry.at)}</p>}
              {entry?.note && <p className="text-sm text-on-surface">{entry.note}</p>}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

function ShipmentCard({
  order,
  shipment,
  index,
  total,
  onInvoice,
  invoiceBusy,
  invoiceReady,
}: {
  order: Order
  shipment: OrderShipment
  index: number
  total: number
  onInvoice: (shipment: OrderShipment) => void
  invoiceBusy: boolean
  invoiceReady: boolean
}) {
  const [rating, setRating] = useState(false)
  const [rated, setRated] = useState(false)
  const items = order.items.filter(
    (i) => (i.shipmentId ? i.shipmentId === shipment.id : shipment.items.some((x) => x.productId === i.productId)),
  )
  const active = shipment.status !== 'DELIVERED' && shipment.status !== 'CANCELLED'

  return (
    <section className={`${cardClass} space-y-3`} aria-label={`Shipment ${index + 1} of ${total}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-bold text-on-surface">
          Shipment {index + 1} of {total}
          <span className="font-normal text-on-surface-muted"> · Sold by {shipment.sellerName}</span>
        </h2>
        <span className="rounded-full border-2 border-ink bg-surface-variant px-3 py-1 text-sm font-bold text-on-surface">
          {STATUS_LABEL[shipment.status]}
        </span>
      </div>

      <ul className="divide-y divide-outline/40">
        {items.map((item) => (
          <li key={`${item.productId}:${item.variantId ?? ''}`} className="flex gap-3 py-2 first:pt-0 last:pb-0">
            <div className="h-14 w-14 shrink-0 overflow-hidden rounded-lg bg-surface-variant">
              <ProductImage src={item.imageUrl ?? undefined} alt={item.name} productId={item.productId} />
            </div>
            <div className="min-w-0 flex-1">
              <Link to={`/products/${item.productId}`} className="line-clamp-2 text-sm font-medium text-on-surface hover:text-primary">
                {item.name}
              </Link>
              {item.variantLabel && <p className="text-xs text-on-surface-muted">{item.variantLabel}</p>}
              <p className="text-xs text-on-surface-muted">
                {item.quantity} x {formatMoney(Number(item.unitPrice), order.currency)}
              </p>
              {shipment.status === 'DELIVERED' && (
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
      </ul>

      <Timeline status={shipment.status} timeline={shipment.timeline} label={`Shipment ${index + 1} tracking`} />

      <dl className="grid gap-2 border-t-2 border-outline/30 pt-3 text-sm sm:grid-cols-3">
        {shipment.estimatedDeliveryDate && active && (
          <div>
            <dt className="text-on-surface-muted">Estimated delivery</dt>
            <dd className="font-semibold text-on-surface">
              {new Date(`${shipment.estimatedDeliveryDate}T00:00:00`).toLocaleDateString(undefined, { dateStyle: 'medium' })}
            </dd>
          </div>
        )}
        {shipment.carrier && (
          <div>
            <dt className="text-on-surface-muted">Carrier</dt>
            <dd className="font-semibold text-on-surface">{shipment.carrier}</dd>
          </div>
        )}
        {shipment.trackingNumber && (
          <div>
            <dt className="text-on-surface-muted">Tracking number</dt>
            <dd className="font-mono font-semibold text-on-surface">{shipment.trackingNumber}</dd>
          </div>
        )}
      </dl>

      <div className="flex flex-wrap gap-2">
        {invoiceReady && shipment.status !== 'CANCELLED' && (
          <button type="button" onClick={() => onInvoice(shipment)} disabled={invoiceBusy} className={secondaryButtonClass}>
            {invoiceBusy ? 'Preparing...' : 'Download invoice'}
          </button>
        )}
        {shipment.status === 'DELIVERED' && !rated && !rating && (
          <button type="button" onClick={() => setRating(true)} className={secondaryButtonClass}>
            Rate seller
          </button>
        )}
      </div>
      {rated && <p className="text-sm font-semibold text-success">Thanks for rating {shipment.sellerName}.</p>}
      {rating && !rated && (
        <Suspense fallback={<LoadingBlock label="Loading form" />}>
          <SellerFeedbackForm
            sellerId={shipment.sellerId}
            sellerName={shipment.sellerName}
            shipmentId={shipment.id}
            onDone={() => {
              setRating(false)
              setRated(true)
            }}
          />
        </Suspense>
      )}
    </section>
  )
}

function ReturnForm({ order, onCreated }: { order: Order; onCreated: (created: ReturnResponse) => void }) {
  const [quantities, setQuantities] = useState<Record<string, number>>({})
  const [reason, setReason] = useState<ReturnReason>('NO_LONGER_NEEDED')
  const [comment, setComment] = useState('')
  const [refundTo, setRefundTo] = useState<'SOURCE' | 'WALLET'>(order.paymentMethod === 'COD' ? 'WALLET' : 'SOURCE')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const lineKey = (i: Order['items'][number]) => `${i.productId}:${i.variantId ?? ''}`
  const chosen = order.items.filter((i) => (quantities[lineKey(i)] ?? 0) > 0)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const created = await orderApi.requestReturn(order.id, {
        items: chosen.map((i) => ({
          productId: i.productId,
          ...(i.variantId ? { variantId: i.variantId } : {}),
          quantity: quantities[lineKey(i)] ?? 0,
        })),
        reason,
        refundTo: order.paymentMethod === 'COD' ? 'WALLET' : refundTo,
        ...(comment.trim() ? { comment: comment.trim() } : {}),
      })
      onCreated(created)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="space-y-4">
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <fieldset className="space-y-2">
        <legend className="mb-1 text-sm font-semibold text-on-surface">Items to return</legend>
        {order.items.map((item) => (
          <div key={lineKey(item)} className="flex items-center gap-3">
            <label htmlFor={`ret-${lineKey(item)}`} className="min-w-0 flex-1 truncate text-sm text-on-surface">
              {item.name}
              {item.variantLabel ? ` (${item.variantLabel})` : ''} <span className="text-on-surface-muted">(bought {item.quantity})</span>
            </label>
            <input
              id={`ret-${lineKey(item)}`}
              type="number"
              min={0}
              max={item.quantity}
              value={quantities[lineKey(item)] ?? 0}
              onChange={(e) =>
                setQuantities((q) => ({
                  ...q,
                  [lineKey(item)]: Math.min(Math.max(Number.parseInt(e.target.value, 10) || 0, 0), item.quantity),
                }))
              }
              className={`${inputClass} !w-20`}
            />
          </div>
        ))}
      </fieldset>
      <fieldset>
        <legend className="mb-1 text-sm font-semibold text-on-surface">Reason</legend>
        <div role="radiogroup" aria-label="Return reason" className="space-y-1">
          {RETURN_REASONS.map((r) => (
            <label key={r.value} className="flex items-center gap-2 text-sm text-on-surface">
              <input
                type="radio"
                name="returnReason"
                checked={reason === r.value}
                onChange={() => setReason(r.value)}
              />
              {r.label}
            </label>
          ))}
        </div>
      </fieldset>
      <Field label="Comment (optional)">
        {(id) => (
          <textarea
            id={id}
            rows={3}
            maxLength={1000}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            className={inputClass}
          />
        )}
      </Field>
      {order.paymentMethod === 'COD' ? (
        <p className="text-sm text-on-surface-muted">Refunds for Cash on Delivery orders go to your wallet.</p>
      ) : (
        <fieldset>
          <legend className="mb-1 text-sm font-semibold text-on-surface">Refund to</legend>
          <div role="radiogroup" aria-label="Refund destination" className="space-y-1">
            {(['SOURCE', 'WALLET'] as const).map((v) => (
              <label key={v} className="flex items-center gap-2 text-sm text-on-surface">
                <input type="radio" name="refundTo" checked={refundTo === v} onChange={() => setRefundTo(v)} />
                {v === 'SOURCE' ? 'Original payment method' : 'Wallet (instant)'}
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <button type="submit" disabled={busy || chosen.length === 0} className={primaryButtonClass}>
        {busy ? 'Submitting...' : 'Request return'}
      </button>
    </form>
  )
}

export function OrderPage() {
  const { id = '' } = useParams()
  const location = useLocation()
  const [search, setSearch] = useSearchParams()
  const justPlaced = (location.state as { placed?: boolean } | null)?.placed === true
  const paymentReturn = search.get('payment') === 'return'
  const load = useCallback(() => orderApi.get(id), [id])
  const { data: order, error, loading, setData } = useLoad(load)
  const loadReturns = useCallback(() => orderApi.listReturns(id), [id])
  const { data: returns, reload: reloadReturns } = useLoad(loadReturns)
  const notify = useUiStore((s) => s.notify)
  const [cancelling, setCancelling] = useState(false)
  const [actionError, setActionError] = useState<string | undefined>()
  const [downloading, setDownloading] = useState(false)
  const [downloadingShipment, setDownloadingShipment] = useState<string | undefined>()
  const [payment, setPayment] = useState<PaymentInfo | null>(null)
  const [resuming, setResuming] = useState(false)
  const [showReturn, setShowReturn] = useState(false)
  const [pollState, setPollState] = useState<'idle' | 'polling' | 'timeout'>('idle')

  const pending = order?.status === 'PENDING_PAYMENT'

  // After the card flow: poll every 2 s (max 30 s) until the webhook/simulation moves the order on.
  useEffect(() => {
    if (!paymentReturn || !pending) {
      setPollState('idle')
      return
    }
    setPollState('polling')
    let tries = 0
    const timer = setInterval(() => {
      tries += 1
      orderApi
        .get(id)
        .then((fresh) => {
          setData(fresh)
          if (fresh.status !== 'PENDING_PAYMENT') clearInterval(timer)
        })
        .catch(() => undefined)
      if (tries >= POLL_MAX) {
        clearInterval(timer)
        setPollState('timeout')
      }
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [paymentReturn, pending, id, setData])

  if (loading && !order) return <LoadingBlock label="Loading order" />
  if (error) return <ErrorAlert message={errorMessage(error)} />
  if (!order) return null

  const cancel = async () => {
    if (!window.confirm('Cancel this order?')) return
    setCancelling(true)
    setActionError(undefined)
    try {
      setData(await orderApi.cancel(order.id))
      notify('Order cancelled')
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setCancelling(false)
    }
  }

  const downloadInvoice = async () => {
    setDownloading(true)
    setActionError(undefined)
    try {
      const blob = await orderApi.invoice(order.id)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `invoice-${order.id.slice(0, 8)}.pdf`
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setDownloading(false)
    }
  }

  const downloadShipmentInvoice = async (shipment: OrderShipment) => {
    setDownloadingShipment(shipment.id)
    setActionError(undefined)
    try {
      const blob = await orderApi.shipmentInvoice(order.id, shipment.id)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `invoice-${order.id.slice(0, 8)}-${shipment.id.slice(0, 8)}.pdf`
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setDownloadingShipment(undefined)
    }
  }

  const resumePayment = async () => {
    setResuming(true)
    setActionError(undefined)
    try {
      setPayment(await paymentApi.forOrder(order.id))
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setResuming(false)
    }
  }

  const invoiceReady = order.status !== 'PENDING_PAYMENT' && order.status !== 'CANCELLED'
  const shipments = order.shipments ?? []
  const money = (value: number | null | undefined) => formatMoney(Number(value ?? 0), order.currency)

  return (
    <div className="mx-auto max-w-3xl space-y-5">
      {justPlaced && order.status === 'PLACED' && (
        <div className="rounded-xl border-2 border-ink bg-primary-container p-4 text-on-primary-container">
          <p className="font-display text-lg font-bold">Thank you! Your order has been placed.</p>
        </div>
      )}
      {order.riskHold && order.status === 'PLACED' && (
        <p role="status" className="rounded-xl border-2 border-ink bg-surface-variant p-3 text-sm text-on-surface">
          Your order is being reviewed by our team before it is confirmed. This usually takes a short while; we will email you.
        </p>
      )}
      <div aria-live="polite" role="status">
        {pollState === 'polling' && (
          <p className="rounded-xl border-2 border-ink bg-surface-variant p-3 text-sm text-on-surface">
            Confirming your payment...
          </p>
        )}
        {pollState === 'timeout' && (
          <p className="rounded-xl border-2 border-ink bg-surface-variant p-3 text-sm text-on-surface">
            We are still waiting for confirmation from the payment provider. Refresh this page in a moment; you will not be
            charged twice.
          </p>
        )}
        {paymentReturn && order.status === 'PLACED' && (
          <p className="rounded-xl border-2 border-ink bg-primary-container p-3 text-sm text-on-primary-container">
            Payment received. Thank you!
          </p>
        )}
        {paymentReturn && order.status === 'CANCELLED' && (
          <p className="rounded-xl border-2 border-ink bg-surface-variant p-3 text-sm text-error">
            Your payment did not go through and the order was cancelled.
          </p>
        )}
      </div>
      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-bold text-on-surface">Order #{order.id.slice(0, 8)}</h1>
          <p className="text-sm text-on-surface-muted">Placed {new Date(order.placedAt).toLocaleString()}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <span className="rounded-full border-2 border-ink bg-surface-variant px-3 py-1 text-sm font-bold text-on-surface">
            {STATUS_LABEL[order.status]}
          </span>
          <span className="rounded-full border-2 border-ink bg-surface px-3 py-1 text-sm font-bold text-on-surface">
            {PAYMENT_LABEL[order.paymentStatus]}
          </span>
        </div>
      </div>

      {pending && (
        <div className={`${cardClass} space-y-3`}>
          <p className="text-sm text-on-surface">This order is waiting for payment. Unpaid orders are cancelled after 30 minutes.</p>
          {payment ? (
            <PaymentPanel
              orderId={order.id}
              payment={payment}
              onSubmitted={() => {
                setPayment(null)
                setSearch({ payment: 'return' }, { replace: true })
              }}
            />
          ) : (
            <button type="button" onClick={() => void resumePayment()} disabled={resuming} className={primaryButtonClass}>
              {resuming ? 'Loading...' : 'Complete payment'}
            </button>
          )}
        </div>
      )}

      {shipments.map((shipment, index) => (
        <ShipmentCard
          key={shipment.id}
          order={order}
          shipment={shipment}
          index={index}
          total={shipments.length}
          onInvoice={(s) => void downloadShipmentInvoice(s)}
          invoiceBusy={downloadingShipment === shipment.id}
          invoiceReady={invoiceReady}
        />
      ))}

      {shipments.length === 0 && (
      <div className={cardClass}>
        <h2 className="mb-3 font-bold text-on-surface">Tracking</h2>
        <Timeline status={order.status} timeline={order.timeline} />
        <dl className="mt-4 grid gap-2 border-t-2 border-outline/30 pt-3 text-sm sm:grid-cols-2">
          {order.estimatedDeliveryDate && order.status !== 'DELIVERED' && order.status !== 'CANCELLED' && (
            <div>
              <dt className="text-on-surface-muted">Estimated delivery</dt>
              <dd className="font-semibold text-on-surface">
                {new Date(`${order.estimatedDeliveryDate}T00:00:00`).toLocaleDateString(undefined, { dateStyle: 'full' })}
              </dd>
            </div>
          )}
          {order.carrier && (
            <div>
              <dt className="text-on-surface-muted">Carrier</dt>
              <dd className="font-semibold text-on-surface">{order.carrier}</dd>
            </div>
          )}
          {order.trackingNumber && (
            <div>
              <dt className="text-on-surface-muted">Tracking number</dt>
              <dd className="font-mono font-semibold text-on-surface">{order.trackingNumber}</dd>
            </div>
          )}
        </dl>
      </div>
      )}

      <ul className={`${cardClass} divide-y divide-outline`}>
        {(shipments.length > 0 ? [] : order.items).map((item) => (
          <li key={item.productId} className="flex gap-3 py-3 first:pt-0">
            <div className="h-16 w-16 shrink-0 overflow-hidden rounded-lg bg-surface-variant">
              <ProductImage src={item.imageUrl ?? undefined} alt={item.name} productId={item.productId} />
            </div>
            <div className="min-w-0 flex-1">
              <Link to={`/products/${item.productId}`} className="line-clamp-2 text-sm font-medium text-on-surface hover:text-primary">
                {item.name}
              </Link>
              {item.variantLabel && <p className="text-xs text-on-surface-muted">{item.variantLabel}</p>}
              <p className="text-xs text-on-surface-muted">
                {item.quantity} x {formatMoney(Number(item.unitPrice), order.currency)}
              </p>
              {order.status !== 'CANCELLED' && order.status !== 'PENDING_PAYMENT' && (
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
        <li className="space-y-1 pt-3 text-sm">
          <div className="flex justify-between">
            <span className="text-on-surface-muted">Subtotal</span>
            <span className="text-on-surface">{money(order.subtotalAmount ?? order.totalAmount)}</span>
          </div>
          {Number(order.discountAmount) > 0 && (
            <div className="flex justify-between">
              <span className="text-on-surface-muted">Discount{order.couponCode ? ` (${order.couponCode})` : ''}</span>
              <span className="text-success">-{money(order.discountAmount)}</span>
            </div>
          )}
          {Number(order.coinsValue) > 0 && (
            <div className="flex justify-between">
              <span className="text-on-surface-muted">Coins ({order.coinsRedeemed})</span>
              <span className="text-success">-{money(order.coinsValue)}</span>
            </div>
          )}
          <div className="flex justify-between">
            <span className="text-on-surface-muted">Shipping{order.membershipFreeShipping ? ' (Plus)' : ''}</span>
            <span className="text-on-surface">{Number(order.shippingAmount) > 0 ? money(order.shippingAmount) : 'Free'}</span>
          </div>
          {order.taxLabel && (
            <div className="flex justify-between">
              <span className="text-on-surface-muted">{order.taxLabel}</span>
              <span className="text-on-surface">{Number(order.taxAmount) > 0 ? money(order.taxAmount) : '—'}</span>
            </div>
          )}
          {order.taxBreakdown?.cgst != null && (
            <div className="flex justify-between text-xs text-on-surface-muted">
              <span>CGST</span>
              <span>{money(order.taxBreakdown.cgst)}</span>
            </div>
          )}
          {order.taxBreakdown?.sgst != null && (
            <div className="flex justify-between text-xs text-on-surface-muted">
              <span>SGST</span>
              <span>{money(order.taxBreakdown.sgst)}</span>
            </div>
          )}
          {order.taxBreakdown?.igst != null && (
            <div className="flex justify-between text-xs text-on-surface-muted">
              <span>IGST</span>
              <span>{money(order.taxBreakdown.igst)}</span>
            </div>
          )}
          <div className="flex items-baseline justify-between border-t-2 border-outline/30 pt-2">
            <span className="font-bold text-on-surface">Total</span>
            <span className="text-xl font-bold text-on-surface">{money(order.totalAmount)}</span>
          </div>
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

      {((returns && returns.length > 0) || order.returnable) && (
        <div className={`${cardClass} space-y-4`}>
          <h2 className="font-bold text-on-surface">Returns</h2>
          {returns && returns.length > 0 && (
            <ul className="space-y-3">
              {returns.map((r) => (
                <li key={r.id} className="rounded-xl border-2 border-outline p-3 text-sm">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="font-semibold text-on-surface">
                      {r.items.map((i) => `${i.quantity} x ${i.name}`).join(', ')}
                    </span>
                    <span className="rounded-full border-2 border-ink bg-surface-variant px-2.5 py-0.5 text-xs font-bold">
                      {RETURN_STATUS_LABEL[r.status]}
                    </span>
                  </div>
                  <p className="text-xs text-on-surface-muted">Requested {formatWhen(r.createdAt)}</p>
                  {r.refundAmount != null && r.status === 'REFUNDED' && (
                    <p className="text-on-surface">Refunded {money(r.refundAmount)}</p>
                  )}
                  {r.adminNote && <p className="text-on-surface">Note from the store: {r.adminNote}</p>}
                </li>
              ))}
            </ul>
          )}
          {order.returnable &&
            (showReturn ? (
              <ReturnForm
                order={order}
                onCreated={() => {
                  setShowReturn(false)
                  reloadReturns()
                  notify('Return requested')
                }}
              />
            ) : (
              <div className="space-y-1">
                <button type="button" className={secondaryButtonClass} onClick={() => setShowReturn(true)}>
                  Return items
                </button>
                {order.returnWindowEndsAt && (
                  <p className="text-xs text-on-surface-muted">
                    Return window open until {new Date(order.returnWindowEndsAt).toLocaleDateString(undefined, { dateStyle: 'medium' })}.
                  </p>
                )}
              </div>
            ))}
        </div>
      )}

      <div className="flex flex-wrap gap-3">
        <Link to="/account/orders" className={secondaryButtonClass}>
          All orders
        </Link>
        {invoiceReady && shipments.length === 0 && (
          <button type="button" onClick={() => void downloadInvoice()} disabled={downloading} className={secondaryButtonClass}>
            {downloading ? 'Preparing...' : 'Download invoice'}
          </button>
        )}
        {order.cancellable && (
          <button type="button" onClick={() => void cancel()} disabled={cancelling} className={`${secondaryButtonClass} text-error`}>
            {cancelling ? 'Cancelling...' : 'Cancel order'}
          </button>
        )}
      </div>
    </div>
  )
}
