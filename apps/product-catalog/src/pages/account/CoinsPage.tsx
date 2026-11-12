/**
 * Account: coins balance, value and ledger.
 */

import { useCallback } from 'react'
import { cardClass } from '../../components/Form'
import { PageShell, fmtDate } from '../../components/account/PageShell'
import { useLoad } from '../../hooks/useLoad'
import { formatMoney } from '../../lib/format'
import { engageApi } from '../../services/engageApi'

export function CoinsPage() {
  const load = useCallback(() => engageApi.coins('USD'), [])
  const { data, loading, error } = useLoad(load)
  const currency = data?.currency ?? 'USD'
  return (
    <PageShell
      id="coins"
      title="Coins"
      intro="Earn 1 coin per 100 spent when an order is delivered (members earn double). Redeem up to 20% of an order at checkout."
      loading={loading && !data}
      error={error}
    >
      {data && (
        <>
          <div className={`${cardClass} grid gap-4 sm:grid-cols-2`}>
            <div>
              <p className="text-sm font-semibold text-on-surface-muted">Balance</p>
              <p className="font-display text-3xl text-on-surface">{data.balance.toLocaleString()} coins</p>
            </div>
            <div>
              <p className="text-sm font-semibold text-on-surface-muted">Worth</p>
              <p className="font-display text-3xl text-on-surface">{formatMoney(data.value, currency)}</p>
            </div>
            {typeof data.expiringSoon === 'number' && data.expiringSoon > 0 && (
              <p className="text-sm font-semibold text-error sm:col-span-2">{data.expiringSoon} coins expire soon. Use them before they go.</p>
            )}
          </div>
          <div className={`${cardClass} space-y-2`}>
            <h3 className="font-bold text-on-surface">History</h3>
            {data.history.length === 0 ? (
              <p className="text-sm text-on-surface-muted">No coin activity yet.</p>
            ) : (
              <ul className="divide-y divide-outline">
                {data.history.map((h, i) => (
                  <li key={h.id ?? `${h.createdAt}-${i}`} className="flex items-center justify-between gap-3 py-2 text-sm">
                    <span>
                      <span className="font-semibold capitalize text-on-surface">{h.reason.replace(/_/g, ' ').toLowerCase()}</span>
                      <span className="block text-xs text-on-surface-muted">
                        {fmtDate(h.createdAt)}
                        {h.expiresAt && h.delta > 0 ? ` · expires ${fmtDate(h.expiresAt)}` : ''}
                      </span>
                    </span>
                    <span className={`font-bold ${h.delta > 0 ? 'text-success' : 'text-on-surface'}`}>
                      {h.delta > 0 ? '+' : ''}
                      {h.delta}
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
