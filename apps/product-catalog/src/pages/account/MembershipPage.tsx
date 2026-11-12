/**
 * Account: Plus membership status, benefits, and joining through the shared payment panel.
 */

import { useCallback, useState } from 'react'
import { ErrorAlert } from '../../components/ErrorAlert'
import { cardClass, primaryButtonClass, secondaryButtonClass } from '../../components/Form'
import { PageShell, fmtDate } from '../../components/account/PageShell'
import { PaymentPanel } from '../../components/PaymentPanel'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { engageApi } from '../../services/engageApi'
import { useUiStore } from '../../stores'
import type { MembershipPurchase } from '../../types'

export function MembershipPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => engageApi.membership(), [])
  const { data, loading, error, reload } = useLoad(load)
  const [currency, setCurrency] = useState('INR')
  const [busy, setBusy] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | undefined>()
  const [purchase, setPurchase] = useState<MembershipPurchase | null>(null)

  const join = async (plan: string) => {
    setBusy(plan)
    setActionError(undefined)
    try {
      setPurchase(await engageApi.joinMembership(plan, currency))
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const cancel = async () => {
    if (!window.confirm('Cancel membership? Benefits stay until the end date.')) return
    setBusy('cancel')
    try {
      await engageApi.cancelMembership()
      notify('Membership cancelled. Benefits continue until the end date.')
      reload()
    } catch (err) {
      setActionError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const plans = (data?.plans ?? []).filter((p) => p.currency === currency)
  const currencies = [...new Set((data?.plans ?? []).map((p) => p.currency))]

  return (
    <PageShell id="membership" title="Plus membership" loading={loading && !data} error={error}>
      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}
      {data && (
        <>
          <div className={`${cardClass} space-y-3`}>
            {data.active ? (
              <>
                <p className="font-display text-xl text-on-surface">You are a Plus member</p>
                <p className="text-sm text-on-surface-muted">
                  {data.plan} · active until {fmtDate(data.endsAt)}
                </p>
                <button type="button" className={secondaryButtonClass} disabled={busy === 'cancel'} onClick={() => void cancel()}>
                  Cancel membership
                </button>
              </>
            ) : (
              <p className="font-display text-xl text-on-surface">Join Plus for free shipping, double coins and early deals</p>
            )}
            {data.benefits.length > 0 && (
              <ul className="list-inside list-disc space-y-1 text-sm text-on-surface">
                {data.benefits.map((b) => (
                  <li key={b}>{b}</li>
                ))}
              </ul>
            )}
          </div>

          {purchase?.payment ? (
            <div className="space-y-3">
              <PaymentPanel
                orderId="membership"
                returnUrl={`${window.location.origin}/account/membership`}
                payment={purchase.payment}
                onSubmitted={() => {
                  notify('Payment submitted. Your membership activates once it clears.')
                  setPurchase(null)
                  reload()
                }}
              />
              <button type="button" className="text-sm font-semibold text-primary hover:underline" onClick={() => setPurchase(null)}>
                Cancel
              </button>
            </div>
          ) : (
            <div className={`${cardClass} space-y-3`}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="font-bold text-on-surface">{data.active ? 'Extend your membership' : 'Choose a plan'}</h3>
                {currencies.length > 1 && (
                  <select aria-label="Currency" className="rounded-lg border-2 border-ink bg-surface px-2 py-1 text-sm" value={currency} onChange={(e) => setCurrency(e.target.value)}>
                    {currencies.map((c) => (
                      <option key={c} value={c}>
                        {c}
                      </option>
                    ))}
                  </select>
                )}
              </div>
              <ul className="grid gap-3 sm:grid-cols-2">
                {plans.map((p) => (
                  <li key={p.code} className="space-y-2 rounded-2xl border-2 border-outline p-4">
                    <p className="font-bold text-on-surface">{p.name}</p>
                    <p className="font-display text-2xl text-on-surface">{formatMoney(p.price, p.currency)}</p>
                    <p className="text-xs text-on-surface-muted">{p.days} days</p>
                    <button type="button" disabled={busy !== null} onClick={() => void join(p.code)} className={`${primaryButtonClass} w-full`}>
                      {busy === p.code ? 'Starting...' : 'Join'}
                    </button>
                  </li>
                ))}
              </ul>
              {plans.length === 0 && <p className="text-sm text-on-surface-muted">No plans available in this currency.</p>}
            </div>
          )}
        </>
      )}
    </PageShell>
  )
}
