/**
 * Account: buy a gift card (card payment) and list the ones you purchased.
 */

import { useCallback, useState } from 'react'
import { ErrorAlert } from '../../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass } from '../../components/Form'
import { PageShell, fmtDate } from '../../components/account/PageShell'
import { PaymentPanel } from '../../components/PaymentPanel'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { engageApi } from '../../services/engageApi'
import { useUiStore } from '../../stores'
import type { GiftCardPurchase } from '../../types'

const RANGE: Record<string, [number, number]> = { INR: [100, 10000] }
const OTHER_RANGE: [number, number] = [5, 500]

export function GiftCardsPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => engageApi.giftCards(), [])
  const { data, loading, error, reload } = useLoad(load)
  const [currency, setCurrency] = useState('INR')
  const [amount, setAmount] = useState('500')
  const [email, setEmail] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState<string | undefined>()
  const [purchase, setPurchase] = useState<GiftCardPurchase | null>(null)
  const [min, max] = RANGE[currency] ?? OTHER_RANGE

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    const value = Number(amount)
    if (!(value >= min && value <= max)) {
      setFormError(`Amount must be between ${formatMoney(min, currency)} and ${formatMoney(max, currency)}.`)
      return
    }
    setBusy(true)
    setFormError(undefined)
    try {
      setPurchase(
        await engageApi.buyGiftCard({
          amount: value,
          currency,
          recipientEmail: email.trim(),
          ...(message.trim() ? { message: message.trim() } : {}),
        }),
      )
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageShell id="gift-cards" title="Gift cards" intro="Send a gift card by email. The code is delivered once payment succeeds.">
      {purchase?.payment ? (
        <div className="space-y-3">
          <PaymentPanel
            orderId={purchase.giftCard.id}
            returnUrl={`${window.location.origin}/account/gift-cards`}
            payment={purchase.payment}
            onSubmitted={() => {
              notify('Payment submitted. The gift card is emailed once it clears.')
              setPurchase(null)
              reload()
            }}
          />
          <button type="button" className="text-sm font-semibold text-primary hover:underline" onClick={() => setPurchase(null)}>
            Cancel
          </button>
        </div>
      ) : (
        <form onSubmit={(e) => void submit(e)} className={`${cardClass} space-y-3`}>
          {formError && <ErrorAlert message={formError} onDismiss={() => setFormError(undefined)} />}
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Currency">
              {(id) => (
                <select id={id} className={inputClass} value={currency} onChange={(e) => setCurrency(e.target.value)}>
                  <option value="INR">INR</option>
                  <option value="USD">USD</option>
                  <option value="EUR">EUR</option>
                </select>
              )}
            </Field>
            <Field label="Amount" hint={`${formatMoney(min, currency)} to ${formatMoney(max, currency)}`}>
              {(id) => (
                <input id={id} type="number" min={min} max={max} step="1" required className={inputClass} value={amount} onChange={(e) => setAmount(e.target.value)} />
              )}
            </Field>
          </div>
          <Field label="Recipient email">
            {(id) => <input id={id} type="email" required maxLength={320} className={inputClass} value={email} onChange={(e) => setEmail(e.target.value)} />}
          </Field>
          <Field label="Message (optional)">
            {(id) => <textarea id={id} rows={2} maxLength={300} className={inputClass} value={message} onChange={(e) => setMessage(e.target.value)} />}
          </Field>
          <button type="submit" disabled={busy} className={primaryButtonClass}>
            {busy ? 'Creating...' : 'Continue to payment'}
          </button>
        </form>
      )}

      <div className={`${cardClass} space-y-2`}>
        <h3 className="font-bold text-on-surface">Gift cards you bought</h3>
        {loading && !data ? (
          <p className="text-sm text-on-surface-muted">Loading...</p>
        ) : error ? (
          <ErrorAlert message={errorMessage(error)} />
        ) : !data || data.length === 0 ? (
          <p className="text-sm text-on-surface-muted">Nothing here yet.</p>
        ) : (
          <ul className="divide-y divide-outline">
            {data.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                <span>
                  <span className="font-semibold text-on-surface">
                    {g.amount != null && g.currency ? formatMoney(g.amount, g.currency) : 'Gift card'}
                  </span>
                  {g.recipientEmail && <span className="block text-xs text-on-surface-muted">To {g.recipientEmail}</span>}
                </span>
                <span className="text-right text-xs text-on-surface-muted">
                  <span className="rounded-full border-2 border-ink bg-surface-variant px-2 py-0.5 font-bold text-on-surface">
                    {g.status.replace(/_/g, ' ')}
                  </span>
                  {g.createdAt && <span className="block pt-1">{fmtDate(g.createdAt)}</span>}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </PageShell>
  )
}
