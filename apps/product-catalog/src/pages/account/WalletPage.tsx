/**
 * Account: wallet balance, history and gift card redemption.
 */

import { useCallback, useState } from 'react'
import { ErrorAlert } from '../../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass } from '../../components/Form'
import { PageShell, fmtDate } from '../../components/account/PageShell'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { formatMoney } from '../../lib/format'
import { engageApi } from '../../services/engageApi'
import { useUiStore } from '../../stores'

export function WalletPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => engageApi.wallet(), [])
  const { data, loading, error, reload } = useLoad(load)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [redeemError, setRedeemError] = useState<string | undefined>()

  const redeem = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setRedeemError(undefined)
    try {
      await engageApi.redeemGiftCard(code.trim().toUpperCase())
      notify('Gift card added to your wallet')
      setCode('')
      reload()
    } catch (err) {
      setRedeemError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const currency = data?.currency ?? 'INR'
  return (
    <PageShell id="wallet" title="Wallet" intro="Refunds, gift cards and rewards land here. Use it at checkout." loading={loading && !data} error={error}>
      {data && (
        <>
          <div className={`${cardClass} flex flex-wrap items-baseline justify-between gap-3`}>
            <span className="text-sm font-semibold text-on-surface-muted">Balance</span>
            <span className="font-display text-3xl text-on-surface">{formatMoney(data.balance, currency)}</span>
          </div>
          <form onSubmit={(e) => void redeem(e)} className={`${cardClass} space-y-3`}>
            <h3 className="font-bold text-on-surface">Redeem a gift card</h3>
            {redeemError && <ErrorAlert message={redeemError} onDismiss={() => setRedeemError(undefined)} />}
            <Field label="Gift card code" hint="Format XXXX-XXXX-XXXX-XXXX">
              {(id) => (
                <input
                  id={id}
                  className={inputClass}
                  value={code}
                  maxLength={19}
                  autoCapitalize="characters"
                  required
                  onChange={(e) => setCode(e.target.value.toUpperCase())}
                />
              )}
            </Field>
            <button type="submit" disabled={busy || code.trim().length < 16} className={primaryButtonClass}>
              {busy ? 'Redeeming...' : 'Redeem'}
            </button>
          </form>
          <div className={`${cardClass} space-y-2`}>
            <h3 className="font-bold text-on-surface">History</h3>
            {data.transactions.length === 0 ? (
              <p className="text-sm text-on-surface-muted">No wallet activity yet.</p>
            ) : (
              <ul className="divide-y divide-outline">
                {data.transactions.map((t) => (
                  <li key={t.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                    <span>
                      <span className="font-semibold capitalize text-on-surface">{t.reason.replace(/_/g, ' ').toLowerCase()}</span>
                      <span className="block text-xs text-on-surface-muted">{fmtDate(t.createdAt)}</span>
                    </span>
                    <span className="text-right">
                      <span className={`font-bold ${t.type === 'CREDIT' ? 'text-success' : 'text-on-surface'}`}>
                        {t.type === 'CREDIT' ? '+' : '-'}
                        {formatMoney(t.amount, currency)}
                      </span>
                      <span className="block text-xs text-on-surface-muted">Balance {formatMoney(t.balanceAfter, currency)}</span>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </PageShell>
  )
}
