/**
 * Account: email / push / SMS preferences and the browser push opt-in.
 */

import { useCallback, useEffect, useState } from 'react'
import { ErrorAlert } from '../../components/ErrorAlert'
import { cardClass } from '../../components/Form'
import { PageShell } from '../../components/account/PageShell'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { currentPushEndpoint, disablePush, enablePush, pushSupported } from '../../lib/push'
import { engageApi } from '../../services/engageApi'
import { useUiStore } from '../../stores'
import type { Preferences } from '../../types'

const ROWS: { key: keyof Preferences; label: string; hint: string }[] = [
  { key: 'emailOrders', label: 'Order emails', hint: 'Confirmations, shipping and delivery updates.' },
  { key: 'emailPromotions', label: 'Promotional emails', hint: 'Deals, offers and cart reminders.' },
  { key: 'pushOrders', label: 'Order push notifications', hint: 'Status changes on this device.' },
  { key: 'pushPromotions', label: 'Promotional push notifications', hint: 'Deals and offers on this device.' },
  { key: 'sms', label: 'SMS updates', hint: 'Delivery text messages.' },
]

const switchClass =
  'h-6 w-11 shrink-0 cursor-pointer appearance-none rounded-full border-2 border-ink bg-surface-variant transition-colors duration-500 before:block before:h-4 before:w-4 before:translate-x-0.5 before:rounded-full before:bg-ink before:transition-transform before:duration-500 checked:bg-vivid-amber checked:before:translate-x-5 disabled:opacity-60'

export function NotificationSettingsPage() {
  const notify = useUiStore((s) => s.notify)
  const load = useCallback(() => engageApi.preferences(), [])
  const { data, loading, error, setData } = useLoad(load)
  const [actionError, setActionError] = useState<string | undefined>()
  const [pushOn, setPushOn] = useState(false)
  const [pushBusy, setPushBusy] = useState(false)
  const supported = pushSupported()

  useEffect(() => {
    void currentPushEndpoint().then((e) => setPushOn(e !== null)).catch(() => undefined)
  }, [])

  const toggle = async (key: keyof Preferences, value: boolean) => {
    if (!data) return
    const next = { ...data, [key]: value }
    setData(next)
    setActionError(undefined)
    try {
      setData(await engageApi.savePreferences(next))
    } catch (err) {
      setData(data)
      setActionError(errorMessage(err))
    }
  }

  const togglePush = async (on: boolean) => {
    setPushBusy(true)
    setActionError(undefined)
    try {
      if (on) await enablePush()
      else await disablePush()
      setPushOn(on)
      notify(on ? 'Push notifications enabled on this device' : 'Push notifications turned off')
    } catch (err) {
      setActionError(err instanceof Error ? err.message : errorMessage(err))
    } finally {
      setPushBusy(false)
    }
  }

  return (
    <PageShell id="notification-settings" title="Notification preferences" loading={loading && !data} error={error}>
      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}
      {data && (
        <div className={`${cardClass} divide-y divide-outline !py-2`}>
          {ROWS.map((r) => (
            <label key={r.key} className="flex cursor-pointer items-center justify-between gap-4 py-3">
              <span>
                <span className="block font-bold text-on-surface">{r.label}</span>
                <span className="block text-sm text-on-surface-muted">{r.hint}</span>
              </span>
              <input type="checkbox" role="switch" className={switchClass} checked={data[r.key]} onChange={(e) => void toggle(r.key, e.target.checked)} />
            </label>
          ))}
        </div>
      )}
      <div className={`${cardClass}`}>
        <label className="flex cursor-pointer items-center justify-between gap-4">
          <span>
            <span className="block font-bold text-on-surface">Push on this browser</span>
            <span className="block text-sm text-on-surface-muted">
              {supported ? 'Get alerts even when the site is closed. Your browser will ask for permission.' : 'This browser does not support push notifications.'}
            </span>
          </span>
          <input
            type="checkbox"
            role="switch"
            className={switchClass}
            checked={pushOn}
            disabled={!supported || pushBusy}
            onChange={(e) => void togglePush(e.target.checked)}
          />
        </label>
      </div>
    </PageShell>
  )
}
