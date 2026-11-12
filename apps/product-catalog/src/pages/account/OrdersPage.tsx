/**
 * Order history, newest first, paged. Each card shows a compact delivery tracker so the
 * customer can see "where is my order" without opening it.
 */

import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { CardSkeleton } from '../../components/account/SectionCard'
import { ErrorAlert } from '../../components/ErrorAlert'
import { cardClass, secondaryButtonClass } from '../../components/Form'
import { CheckIcon, ChevronRightIcon, PackageIcon } from '../../components/Icons'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { orderApi } from '../../services/orderApi'
import type { OrderStatus, PaymentStatus } from '../../types'

const PAGE_SIZE = 10

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

/** The tracker's four customer-facing milestones and the statuses that reach each. */
const STEPS: { label: string; reachedBy: OrderStatus[] }[] = [
  { label: 'Ordered', reachedBy: ['PLACED', 'CONFIRMED', 'PACKED', 'SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED'] },
  { label: 'Packed', reachedBy: ['PACKED', 'SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED'] },
  { label: 'Shipped', reachedBy: ['SHIPPED', 'OUT_FOR_DELIVERY', 'DELIVERED'] },
  { label: 'Delivered', reachedBy: ['DELIVERED'] },
]

type Tone = 'good' | 'warn' | 'bad' | 'done'

function statusTone(status: OrderStatus): Tone {
  if (status === 'CANCELLED') return 'bad'
  if (status === 'PENDING_PAYMENT') return 'warn'
  if (status === 'DELIVERED') return 'done'
  return 'good'
}

const TONE_CHIP: Record<Tone, string> = {
  good: 'bg-vivid-sky/25 text-on-surface',
  done: 'bg-success/20 text-success',
  warn: 'bg-vivid-amber/30 text-on-surface',
  bad: 'bg-error/15 text-error',
}
const TONE_DOT: Record<Tone, string> = {
  good: 'bg-vivid-sky animate-pulse',
  done: 'bg-success',
  warn: 'bg-vivid-amber animate-pulse',
  bad: 'bg-error',
}

function Tracker({ status }: { status: OrderStatus }) {
  if (status === 'CANCELLED' || status === 'PENDING_PAYMENT') {
    return (
      <p className="text-sm text-on-surface-muted">
        {status === 'CANCELLED' ? 'This order was cancelled.' : 'Complete payment to start processing this order.'}
      </p>
    )
  }
  const reached = STEPS.filter((s) => s.reachedBy.includes(status)).length
  return (
    <ol className="grid grid-cols-4" aria-label={`Progress: ${STATUS_LABEL[status]}`}>
      {STEPS.map((step, i) => {
        const done = i < reached
        const current = i === reached - 1 && status !== 'DELIVERED'
        return (
          <li key={step.label} className="relative flex flex-col items-center gap-1.5 text-center">
            {i > 0 && (
              <span
                aria-hidden="true"
                className={`absolute right-1/2 top-3 h-1 w-full -translate-y-1/2 border-y border-ink/60 ${
                  done ? 'bg-primary' : 'bg-surface-variant'
                }`}
              />
            )}
            <span
              className={`relative z-10 flex h-6 w-6 items-center justify-center rounded-full border-2 border-ink ${
                done ? 'bg-primary text-on-primary' : 'bg-surface text-on-surface-muted'
              } ${current ? 'ring-4 ring-vivid-amber/50' : ''}`}
            >
              {done && <CheckIcon className="h-3.5 w-3.5" />}
            </span>
            <span className={`text-[11px] font-bold sm:text-xs ${done ? 'text-on-surface' : 'text-on-surface-muted'}`}>
              {step.label}
              {done ? <span className="sr-only"> (done)</span> : null}
            </span>
          </li>
        )
      })}
    </ol>
  )
}

export function OrdersPage() {
  const [page, setPage] = useState(0)
  const load = useCallback(() => orderApi.list(page, PAGE_SIZE), [page])
  const { data, error, loading } = useLoad(load)

  if (loading && !data) return <CardSkeleton rows={4} />
  if (error) return <ErrorAlert message={errorMessage(error)} />
  if (!data) return null

  if (data.content.length === 0 && page === 0) {
    return (
      <div className={`${cardClass} flex flex-col items-center gap-3 py-14 text-center`}>
        <PackageIcon className="h-12 w-12 text-on-surface-muted" />
        <p className="font-bold text-on-surface">No orders yet</p>
        <p className="text-sm text-on-surface-muted">Once you place an order, you can track it here.</p>
        <Link
          to="/"
          className="press shine bg-cta-gradient mt-1 rounded-full border-2 border-ink px-5 py-2.5 text-sm font-bold text-on-accent shadow-cta"
        >
          Start shopping
        </Link>
      </div>
    )
  }

  const { number, totalPages, totalElements } = data.page

  return (
    <div className="space-y-5">
      <div>
        <h2 className="font-display text-2xl text-on-surface">Your orders</h2>
        <p className="text-sm text-on-surface-muted">
          {totalElements} {totalElements === 1 ? 'order' : 'orders'} in total, newest first.
        </p>
      </div>

      <ul className={`space-y-4 transition-opacity duration-500 ${loading ? 'opacity-60' : ''}`} aria-busy={loading}>
        {data.content.map((order) => {
          const tone = statusTone(order.status)
          const cancelled = tone === 'bad'
          return (
            <li key={order.id}>
              <Link
                to={`/orders/${order.id}`}
                className={`${cardClass} group block !p-0 transition-all duration-700 hover:-translate-x-0.5 hover:-translate-y-0.5 hover:shadow-card-hover`}
              >
                {/* Header strip: id + date on the left, total on the right. */}
                <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b-2 border-outline/20 px-5 py-4">
                  <span
                    className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full border-2 border-ink text-ink ${
                      cancelled ? 'bg-surface-variant' : 'bg-vivid-sky/30'
                    }`}
                    aria-hidden="true"
                  >
                    <PackageIcon className="h-5 w-5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="font-mono text-sm font-bold text-on-surface">Order #{order.id.slice(0, 8).toUpperCase()}</p>
                    <p className="text-sm text-on-surface-muted">
                      {new Date(order.placedAt).toLocaleDateString(undefined, { dateStyle: 'medium' })} · {order.itemCount}{' '}
                      {order.itemCount === 1 ? 'item' : 'items'}
                    </p>
                  </div>
                  <span
                    className={`font-display text-xl ${cancelled ? 'text-on-surface-muted line-through' : 'text-on-surface'}`}
                  >
                    {formatMoney(Number(order.totalAmount), order.currency)}
                  </span>
                </div>

                {/* Body: status chips, tracker, call to action. */}
                <div className="grid gap-4 px-5 py-4 md:grid-cols-[auto_minmax(0,1fr)_auto] md:items-center">
                  <div className="flex flex-wrap gap-2">
                    <span
                      className={`inline-flex items-center gap-1.5 rounded-full border-2 border-ink px-2.5 py-0.5 text-xs font-bold ${TONE_CHIP[tone]}`}
                    >
                      <span className={`h-2 w-2 rounded-full ${TONE_DOT[tone]}`} aria-hidden="true" />
                      {STATUS_LABEL[order.status]}
                    </span>
                    <span className="rounded-full border-2 border-outline px-2.5 py-0.5 text-xs font-semibold text-on-surface-muted">
                      {PAYMENT_LABEL[order.paymentStatus] ?? order.paymentStatus}
                    </span>
                  </div>
                  <div className="md:px-4">
                    <Tracker status={order.status} />
                  </div>
                  <span className="inline-flex items-center gap-1 justify-self-start text-sm font-bold text-primary md:justify-self-end">
                    {order.status === 'PENDING_PAYMENT' ? 'Complete payment' : 'View details'}
                    <ChevronRightIcon className="h-4 w-4 transition-transform duration-700 group-hover:translate-x-1" />
                  </span>
                </div>
              </Link>
            </li>
          )
        })}
      </ul>

      {totalPages > 1 && (
        <nav aria-label="Order pages" className="flex items-center justify-between">
          <button type="button" className={secondaryButtonClass} disabled={number <= 0 || loading} onClick={() => setPage(number - 1)}>
            Previous
          </button>
          <span className="text-sm text-on-surface-muted">
            Page {number + 1} of {totalPages}
          </span>
          <button
            type="button"
            className={secondaryButtonClass}
            disabled={number >= totalPages - 1 || loading}
            onClick={() => setPage(number + 1)}
          >
            Next
          </button>
        </nav>
      )}
    </div>
  )
}
