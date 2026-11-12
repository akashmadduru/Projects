/**
 * Account: Subscribe & Save subscriptions - change quantity or frequency, skip, pause, resume, cancel.
 */

import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ErrorAlert } from '../../components/ErrorAlert'
import { cardClass, secondaryButtonClass } from '../../components/Form'
import { PageShell, fmtDate } from '../../components/account/PageShell'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { productPath } from '../../lib/productPath'
import { discoveryApi } from '../../services/discoveryApi'
import { engageApi } from '../../services/engageApi'
import { useUiStore } from '../../stores'
import type { Product, Subscription, SubscriptionUpdate } from '../../types'

export const FREQUENCIES = [7, 14, 30, 60] as const

export function SubscriptionsPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => engageApi.subscriptions(), [])
  const { data, loading, error, reload } = useLoad(load)
  const [products, setProducts] = useState<Record<string, Product>>({})
  const [actionError, setActionError] = useState<string | undefined>()
  const [busy, setBusy] = useState<string | null>(null)

  const ids = [...new Set((data ?? []).map((s) => s.productId))].join(',')
  useEffect(() => {
    if (!ids) return
    let live = true
    void discoveryApi.batch(ids.split(',')).then((list) => {
      if (live) setProducts(Object.fromEntries(list.map((p) => [p.id, p])))
    }).catch(() => undefined)
    return () => {
      live = false
    }
  }, [ids])

  const run = async (id: string, action: () => Promise<unknown>, done: string) => {
    setBusy(id)
    setActionError(undefined)
    try {
      await action()
      notify(done)
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }
  const update = (s: Subscription, body: SubscriptionUpdate, done: string) =>
    run(s.id, () => engageApi.updateSubscription(s.id, body), done)

  const visible = (data ?? []).filter((s) => s.status !== 'CANCELLED')

  return (
    <PageShell
      id="subscriptions"
      title="Subscriptions"
      intro="Subscribe & Save on the product page. Orders are placed automatically on the next delivery date."
      loading={loading && !data}
      error={error}
    >
      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}
      {data && visible.length === 0 && <p className="text-sm text-on-surface-muted">No active subscriptions.</p>}
      <ul className="space-y-3">
        {visible.map((s) => {
          const product = products[s.productId]
          const disabled = busy === s.id
          return (
            <li key={s.id} className={`${cardClass} space-y-3`}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <Link to={product ? productPath(product) : `/products/${s.productId}`} className="font-semibold text-on-surface hover:text-primary">
                    {product?.name ?? s.productName ?? 'Product'}
                  </Link>
                  <p className="text-sm text-on-surface-muted">
                    {s.status === 'PAUSED' ? 'Paused' : `Next order ${fmtDate(s.nextRunOn)}`} · {s.discountPercent}% off · pay by {s.paymentMethod}
                  </p>
                  {(s.failures ?? 0) > 0 && <p className="text-xs text-error">Last attempt failed ({s.failures}). We will retry.</p>}
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-3 text-sm">
                <label className="flex items-center gap-2 text-on-surface">
                  Qty
                  <input
                    type="number"
                    min={1}
                    max={20}
                    defaultValue={s.quantity}
                    disabled={disabled}
                    aria-label="Quantity"
                    className="w-16 rounded-lg border-2 border-ink bg-surface px-2 py-1"
                    onBlur={(e) => {
                      const q = Math.min(20, Math.max(1, Number(e.target.value) || s.quantity))
                      if (q !== s.quantity) void update(s, { quantity: q }, 'Quantity updated')
                    }}
                  />
                </label>
                <label className="flex items-center gap-2 text-on-surface">
                  Every
                  <select
                    value={s.frequencyDays}
                    disabled={disabled}
                    aria-label="Frequency"
                    className="rounded-lg border-2 border-ink bg-surface px-2 py-1"
                    onChange={(e) => void update(s, { frequencyDays: Number(e.target.value) }, 'Frequency updated')}
                  >
                    {FREQUENCIES.map((f) => (
                      <option key={f} value={f}>
                        {f} days
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              <div className="flex flex-wrap gap-2">
                <button type="button" className={secondaryButtonClass} disabled={disabled || s.status !== 'ACTIVE'} onClick={() => void run(s.id, () => engageApi.skipSubscription(s.id), 'Next delivery skipped')}>
                  Skip next
                </button>
                {s.status === 'ACTIVE' ? (
                  <button type="button" className={secondaryButtonClass} disabled={disabled} onClick={() => void update(s, { status: 'PAUSED' }, 'Subscription paused')}>
                    Pause
                  </button>
                ) : (
                  <button type="button" className={secondaryButtonClass} disabled={disabled} onClick={() => void update(s, { status: 'ACTIVE' }, 'Subscription resumed')}>
                    Resume
                  </button>
                )}
                <button
                  type="button"
                  className={`${secondaryButtonClass} !text-error`}
                  disabled={disabled}
                  onClick={() => {
                    if (window.confirm('Cancel this subscription?')) void update(s, { status: 'CANCELLED' }, 'Subscription cancelled')
                  }}
                >
                  Cancel
                </button>
              </div>
            </li>
          )
        })}
      </ul>
    </PageShell>
  )
}
