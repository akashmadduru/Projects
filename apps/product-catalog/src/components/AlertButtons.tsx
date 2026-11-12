/**
 * "Notify me when back in stock" and "Alert me on price drop" for the selected product / variant.
 */

import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Link } from 'react-router-dom'
import { secondaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import { discoveryApi } from '../services/discoveryApi'
import { useAuthStore, useUiStore } from '../stores'
import type { AlertType } from '../types'

interface Props {
  productId: string
  variantId?: string
  outOfStock: boolean
  currentPrice: number
  currency: string
  productName: string
}

export function AlertButtons({ productId, variantId, outOfStock, currentPrice, currency, productName }: Props) {
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const notify = useUiStore((s) => s.notify)
  const navigate = useNavigate()
  const location = useLocation()
  const [busy, setBusy] = useState<AlertType | undefined>()
  const [target, setTarget] = useState('')
  const [showTarget, setShowTarget] = useState(false)

  const create = async (type: AlertType, targetPrice?: number) => {
    if (!authenticated) {
      navigate(`/login?next=${encodeURIComponent(location.pathname)}`)
      return
    }
    setBusy(type)
    try {
      await discoveryApi.createAlert({
        productId,
        ...(variantId ? { variantId } : {}),
        type,
        ...(targetPrice ? { targetPrice } : {}),
        currentPrice,
        currency,
        productName,
      })
      notify(type === 'PRICE_DROP' ? 'We will email you when the price drops' : 'We will email you when it is back in stock')
      setShowTarget(false)
    } catch (e) {
      notify(errorMessage(e))
    } finally {
      setBusy(undefined)
    }
  }

  const targetValue = Number(target)
  const targetValid = target.trim() === '' || (Number.isFinite(targetValue) && targetValue > 0 && targetValue < currentPrice)

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {outOfStock && (
          <button type="button" disabled={busy === 'BACK_IN_STOCK'} onClick={() => void create('BACK_IN_STOCK')} className={secondaryButtonClass}>
            Notify me when available
          </button>
        )}
        <button type="button" aria-expanded={showTarget} onClick={() => setShowTarget((s) => !s)} className={secondaryButtonClass}>
          Alert me on price drop
        </button>
      </div>
      {showTarget && (
        <form
          className="flex flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (targetValid) void create('PRICE_DROP', target.trim() ? targetValue : undefined)
          }}
        >
          <label htmlFor="alert-target" className="sr-only">
            Target price (optional)
          </label>
          <input
            id="alert-target"
            inputMode="decimal"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            placeholder="Target price (optional)"
            className="w-48 rounded-full border-2 border-outline bg-surface px-3 py-1.5 text-sm text-on-surface focus:border-primary"
          />
          <button type="submit" disabled={!targetValid || busy === 'PRICE_DROP'} className={secondaryButtonClass}>
            Set alert
          </button>
          {!targetValid && (
            <span role="alert" className="text-xs text-error">
              Target must be below the current price.
            </span>
          )}
          {authenticated && (
            <Link to="/account/alerts" className="text-xs font-semibold text-primary hover:underline">
              Manage alerts
            </Link>
          )}
        </form>
      )}
    </div>
  )
}
