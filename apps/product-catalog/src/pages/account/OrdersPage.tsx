/**
 * Order history, newest first, paged
 */

import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { CardSkeleton } from '../../components/account/SectionCard'
import { ErrorAlert } from '../../components/ErrorAlert'
import { cardClass, secondaryButtonClass } from '../../components/Form'
import { ChevronRightIcon, PackageIcon } from '../../components/Icons'
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

type Tone = 'good' | 'warn' | 'bad'

function statusTone(status: OrderStatus): Tone {
  if (status === 'CANCELLED') return 'bad'
  if (status === 'PENDING_PAYMENT') return 'warn'
  return 'good'
}

const TONE_CHIP: Record<Tone, string> = {
  good: 'bg-success/20 text-success',
  warn: 'bg-vivid-amber/30 text-on-surface',
  bad: 'bg-error/15 text-error',
}
const TONE_DOT: Record<Tone, string> = { good: 'bg-success', warn: 'bg-vivid-amber', bad: 'bg-error' }

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

      <ul className="space-y-3">
        {data.content.map((order) => {
          const tone = statusTone(order.status)
          const placed = tone !== 'bad'
          return (
            <li key={order.id}>
              <Link
                to={`/orders/${order.id}`}
                className={`${cardClass} group flex flex-wrap items-center gap-4 transition-all duration-700 hover:-translate-x-0.5 hover:-translate-y-0.5 hover:shadow-card-hover`}
              >
                <span
                  className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-full border-2 border-ink text-ink ${
                    placed ? 'bg-vivid-sky/30' : 'bg-surface-variant'
                  }`}
                  aria-hidden="true"
                >
                  <PackageIcon className="h-6 w-6" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="font-mono text-sm font-bold text-on-surface">#{order.id.slice(0, 8)}</p>
                  <p className="text-sm text-on-surface-muted">
                    {new Date(order.placedAt).toLocaleDateString(undefined, { dateStyle: 'medium' })} · {order.itemCount}{' '}
                    {order.itemCount === 1 ? 'item' : 'items'}
                  </p>
                </div>
                <span
                  className={`inline-flex items-center gap-1.5 rounded-full border-2 border-ink px-2.5 py-0.5 text-xs font-bold ${
                    TONE_CHIP[tone]
                  }`}
                >
                  <span className={`h-2 w-2 rounded-full ${TONE_DOT[tone]}`} aria-hidden="true" />
                  {STATUS_LABEL[order.status]}
                </span>
                <span className="rounded-full border-2 border-outline px-2.5 py-0.5 text-xs font-semibold text-on-surface-muted">
                  {PAYMENT_LABEL[order.paymentStatus] ?? order.paymentStatus}
                </span>
                <span className={`font-display text-lg ${placed ? 'text-on-surface' : 'text-on-surface-muted line-through'}`}>
                  {formatMoney(Number(order.totalAmount), order.currency)}
                </span>
                <ChevronRightIcon className="h-5 w-5 text-on-surface-muted transition-transform duration-700 group-hover:translate-x-1" />
              </Link>
            </li>
          )
        })}
      </ul>

      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <button type="button" className={secondaryButtonClass} disabled={number <= 0} onClick={() => setPage(number - 1)}>
            Previous
          </button>
          <span className="text-sm text-on-surface-muted">
            Page {number + 1} of {totalPages}
          </span>
          <button
            type="button"
            className={secondaryButtonClass}
            disabled={number >= totalPages - 1}
            onClick={() => setPage(number + 1)}
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
