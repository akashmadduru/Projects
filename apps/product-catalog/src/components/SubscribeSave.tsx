/**
 * "Subscribe & save 5%" on the product page (signed-in users): frequency, address and payment, then create.
 * Lazy-loaded from the product page.
 */

import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ErrorAlert } from './ErrorAlert'
import { primaryButtonClass } from './Form'
import { useLoad } from '../hooks/useLoad'
import { errorMessage } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { engageApi } from '../services/engageApi'
import { userApi } from '../services/userApi'
import { useAuthStore, useUiStore } from '../stores'

const FREQUENCIES = [7, 14, 30, 60]
const DISCOUNT = 5
const loadAddresses = () => userApi.listAddresses()

interface Props {
  productId: string
  variantId?: string
  quantity: number
  unitPrice: number
  currency: string
  disabled?: boolean
}

export default function SubscribeSave({ productId, variantId, quantity, unitPrice, currency, disabled }: Props) {
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const notify = useUiStore((s) => s.notify)
  const { pathname } = useLocation()
  const [open, setOpen] = useState(false)
  const [days, setDays] = useState(30)
  const [method, setMethod] = useState<'COD' | 'WALLET'>('COD')
  const [addressId, setAddressId] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const addresses = useLoad(loadAddresses)

  const saved = (unitPrice * quantity * DISCOUNT) / 100
  const chosen = addressId ?? addresses.data?.[0]?.id

  if (!authenticated) {
    return (
      <p className="text-sm text-on-surface-muted">
        <Link to={`/login?next=${encodeURIComponent(pathname)}`} className="font-semibold text-primary hover:underline">
          Sign in
        </Link>{' '}
        to subscribe and save {DISCOUNT}%.
      </p>
    )
  }

  const subscribe = async () => {
    if (!chosen) return
    setBusy(true)
    setError(undefined)
    try {
      await engageApi.createSubscription({
        productId,
        ...(variantId ? { variantId } : {}),
        quantity,
        frequencyDays: days,
        addressId: chosen,
        paymentMethod: method,
      })
      notify(`Subscribed. Delivered every ${days} days.`)
      setOpen(false)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rounded-2xl border-2 border-outline p-4">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between gap-2 text-left font-bold text-on-surface"
      >
        <span>Subscribe &amp; save {DISCOUNT}%</span>
        <span className="text-sm font-semibold text-success">Save {formatMoney(saved, currency)} per delivery</span>
      </button>
      {open && (
        <div className="mt-3 space-y-3 text-sm">
          {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
          <div className="flex flex-wrap gap-3">
            <label className="flex items-center gap-2 text-on-surface">
              Deliver every
              <select className="rounded-lg border-2 border-ink bg-surface px-2 py-1" value={days} onChange={(e) => setDays(Number(e.target.value))}>
                {FREQUENCIES.map((f) => (
                  <option key={f} value={f}>
                    {f} days
                  </option>
                ))}
              </select>
            </label>
            <label className="flex items-center gap-2 text-on-surface">
              Pay by
              <select className="rounded-lg border-2 border-ink bg-surface px-2 py-1" value={method} onChange={(e) => setMethod(e.target.value as 'COD' | 'WALLET')}>
                <option value="COD">Cash on delivery</option>
                <option value="WALLET">Wallet</option>
              </select>
            </label>
          </div>
          {addresses.data && addresses.data.length > 0 ? (
            <label className="flex items-center gap-2 text-on-surface">
              Ship to
              <select className="min-w-0 rounded-lg border-2 border-ink bg-surface px-2 py-1" value={chosen} onChange={(e) => setAddressId(e.target.value)}>
                {addresses.data.map((a) => (
                  <option key={a.id} value={a.id}>
                    {a.label || a.line1}, {a.city}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            !addresses.loading && (
              <p className="text-on-surface-muted">
                <Link to="/account/addresses" className="font-semibold text-primary hover:underline">
                  Add an address
                </Link>{' '}
                first.
              </p>
            )
          )}
          <button type="button" className={primaryButtonClass} disabled={busy || disabled || !chosen} onClick={() => void subscribe()}>
            {busy ? 'Subscribing...' : 'Start subscription'}
          </button>
          <p className="text-xs text-on-surface-muted">Skip, pause or cancel any time in your account.</p>
        </div>
      )}
    </div>
  )
}
