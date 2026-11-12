/**
 * Delivery check on the product page: pincode in, delivery date / shipping fee / COD availability out.
 * The last pincode is remembered in localStorage (best effort).
 */

import { useEffect, useState } from 'react'
import { inputClass, secondaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { orderApi } from '../services/orderApi'
import type { DeliveryEstimate } from '../types'

const STORAGE_KEY = 'last-pincode'

function readStored(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

function writeStored(value: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, value)
  } catch {
    // Storage may be unavailable (private mode); remembering the pincode is optional.
  }
}

interface Props {
  price: number
  currency: string
}

export function PincodeCheck({ price, currency }: Props) {
  const [pincode, setPincode] = useState(readStored)
  const [estimate, setEstimate] = useState<DeliveryEstimate | null>(null)
  const [error, setError] = useState<string | undefined>()
  const [busy, setBusy] = useState(false)

  const check = async (value: string) => {
    const trimmed = value.trim()
    if (!trimmed) return
    setBusy(true)
    setError(undefined)
    try {
      setEstimate(await orderApi.deliveryEstimate(trimmed, price, currency))
      writeStored(trimmed)
    } catch (err) {
      setEstimate(null)
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  // Re-check a remembered pincode when the product (price) changes.
  useEffect(() => {
    const stored = readStored()
    if (stored) void check(stored)
  }, [price, currency])

  return (
    <div className="space-y-2">
      <form
        className="flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          void check(pincode)
        }}
      >
        <div className="flex-1 space-y-1">
          <label htmlFor="pincode-check" className="block text-sm font-semibold text-on-surface">
            Check delivery
          </label>
          <input
            id="pincode-check"
            inputMode="numeric"
            autoComplete="postal-code"
            maxLength={10}
            placeholder="Enter pincode"
            value={pincode}
            onChange={(e) => setPincode(e.target.value)}
            className={inputClass}
          />
        </div>
        <button type="submit" disabled={busy || !pincode.trim()} className={secondaryButtonClass}>
          {busy ? 'Checking...' : 'Check'}
        </button>
      </form>
      <div aria-live="polite" className="text-sm">
        {estimate && (
          <p className="font-semibold text-success">
            Delivery by{' '}
            {new Date(`${estimate.estimatedDeliveryDate}T00:00:00`).toLocaleDateString(undefined, {
              weekday: 'short',
              day: 'numeric',
              month: 'short',
            })}{' '}
            &middot; {estimate.shippingFee > 0 ? formatMoney(estimate.shippingFee, estimate.currency) : 'Free delivery'}
            {estimate.codAvailable ? ' · COD available' : ''}
          </p>
        )}
        {error && <p className="text-error">{error}</p>}
      </div>
    </div>
  )
}
