/**
 * Exchange offer: pick a programme, describe the old device, get a quote (valid 48 h). On the product
 * page it saves the quote; at checkout the same card lets the shopper apply it to the order.
 * Renders nothing when the product has no exchange programme.
 */

import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ErrorAlert } from './ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import { formatMoney } from '../lib/format'
import { useT } from '../lib/i18n'
import { exchangeApi } from '../services/trustApi'
import { useAuthStore } from '../stores'
import type { DeviceCondition, ExchangeProgram, ExchangeQuote } from '../types'

const KEY = 'exchange-quote'

interface Stored {
  productId: string
  quote: ExchangeQuote
}

function readStored(productId: string): ExchangeQuote | null {
  try {
    const raw = JSON.parse(sessionStorage.getItem(KEY) ?? 'null') as Stored | null
    if (!raw || raw.productId !== productId) return null
    if (raw.quote.expiresAt && new Date(raw.quote.expiresAt).getTime() < Date.now()) return null
    return raw.quote
  } catch {
    return null
  }
}

function store(productId: string, quote: ExchangeQuote | null) {
  try {
    if (quote) sessionStorage.setItem(KEY, JSON.stringify({ productId, quote } satisfies Stored))
    else sessionStorage.removeItem(KEY)
  } catch {
    // Quote only lives for this page view.
  }
}

interface Props {
  productId: string
  /** Checkout mode: the applied quote (controlled by the page) and its setter. */
  applied?: ExchangeQuote | null
  onApply?: (quote: ExchangeQuote | null) => void
}

export function ExchangeOffer({ productId, applied, onApply }: Props) {
  const t = useT()
  const authenticated = useAuthStore((s) => s.status === 'authenticated')
  const [programs, setPrograms] = useState<ExchangeProgram[]>([])
  const [programId, setProgramId] = useState('')
  const [brand, setBrand] = useState('')
  const [model, setModel] = useState('')
  const [condition, setCondition] = useState<DeviceCondition>('WORKING')
  const [ageYears, setAgeYears] = useState(1)
  const [quote, setQuote] = useState<ExchangeQuote | null>(() => readStored(productId))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  useEffect(() => {
    let active = true
    exchangeApi
      .programs(productId)
      .then((list) => {
        if (!active) return
        setPrograms(list)
        setProgramId((cur) => cur || list[0]?.id || '')
      })
      .catch(() => active && setPrograms([]))
    return () => {
      active = false
    }
  }, [productId])

  if (programs.length === 0) return null
  const program = programs.find((p) => p.id === programId) ?? programs[0]!

  const getQuote = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      const q = await exchangeApi.quote({ programId: program.id, brand: brand.trim(), model: model.trim(), condition, ageYears })
      setQuote(q)
      store(productId, q)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="exchange-title" className={`${cardClass} space-y-3`}>
      <h2 id="exchange-title" className="font-bold text-on-surface">
        {t('Exchange your old device')}
      </h2>
      <p className="text-sm text-on-surface-muted">Up to {formatMoney(program.maxValue, program.currency)} off with {program.title}.</p>
      {quote ? (
        <div className="space-y-2" aria-live="polite">
          <p className="text-on-surface">
            Your quote: <strong>{formatMoney(quote.quotedValue, quote.currency ?? program.currency)}</strong>
            {quote.expiresAt && <span className="text-sm text-on-surface-muted"> (valid until {new Date(quote.expiresAt).toLocaleString()})</span>}
          </p>
          <p className="text-xs text-on-surface-muted">Final value is confirmed when the old device is picked up and inspected.</p>
          <div className="flex flex-wrap gap-2">
            {onApply &&
              (applied?.id === quote.id ? (
                <button type="button" className={secondaryButtonClass} onClick={() => onApply(null)}>
                  Remove exchange
                </button>
              ) : (
                <button type="button" className={primaryButtonClass} onClick={() => onApply(quote)}>
                  Apply to this order
                </button>
              ))}
            <button
              type="button"
              className={secondaryButtonClass}
              onClick={() => {
                setQuote(null)
                store(productId, null)
                onApply?.(null)
              }}
            >
              New quote
            </button>
          </div>
        </div>
      ) : !authenticated ? (
        <Link to={`/login?next=${encodeURIComponent(window.location.pathname)}`} className={secondaryButtonClass}>
          Sign in to get a quote
        </Link>
      ) : (
        <form className="space-y-3" onSubmit={(e) => void getQuote(e)}>
          {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
          {programs.length > 1 && (
            <Field label="Programme">
              {(id) => (
                <select id={id} className={inputClass} value={program.id} onChange={(e) => setProgramId(e.target.value)}>
                  {programs.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.title}
                    </option>
                  ))}
                </select>
              )}
            </Field>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Brand">{(id) => <input id={id} className={inputClass} required maxLength={60} value={brand} onChange={(e) => setBrand(e.target.value)} />}</Field>
            <Field label="Model">{(id) => <input id={id} className={inputClass} required maxLength={60} value={model} onChange={(e) => setModel(e.target.value)} />}</Field>
            <Field label="Condition">
              {(id) => (
                <select id={id} className={inputClass} value={condition} onChange={(e) => setCondition(e.target.value as DeviceCondition)}>
                  <option value="WORKING">Working</option>
                  <option value="SCREEN_DAMAGED">Screen damaged</option>
                  <option value="NOT_WORKING">Not working</option>
                </select>
              )}
            </Field>
            <Field label="Age (years)">
              {(id) => <input id={id} type="number" min={0} max={15} className={inputClass} value={ageYears} onChange={(e) => setAgeYears(Math.max(0, Number(e.target.value) || 0))} />}
            </Field>
          </div>
          <button type="submit" className={primaryButtonClass} disabled={busy || !brand.trim() || !model.trim()}>
            {busy ? 'Calculating...' : t('Get exchange value')}
          </button>
        </form>
      )}
    </section>
  )
}
