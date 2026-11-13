/** Consent banner: necessary cookies always on, analytics opt-in. Stored in localStorage. */

import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useConsent } from '../lib/consent'
import { useT } from '../lib/i18n'
import { primaryButtonClass, secondaryButtonClass } from './Form'

export function CookieBanner() {
  const t = useT()
  const decided = useConsent((s) => s.decided)
  const set = useConsent((s) => s.set)
  const [analytics, setAnalytics] = useState(false)
  if (decided) return null

  return (
    <section
      aria-label={t('Cookie preferences')}
      className="fixed inset-x-3 bottom-3 z-[75] mx-auto max-w-2xl space-y-3 rounded-[1.5rem] border-2 border-ink bg-surface p-4 text-sm text-on-surface shadow-card"
    >
      <p className="font-bold">{t('We use cookies')}</p>
      <p className="text-on-surface-muted">
        Necessary cookies keep you signed in and your cart working. Analytics (product view counts and synced
        recently-viewed) only run if you allow them. See our <Link className="font-semibold text-primary underline" to="/help">help centre</Link>.
      </p>
      <div className="space-y-1">
        <label className="flex items-center gap-2">
          <input type="checkbox" checked disabled /> Necessary (always on)
        </label>
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={analytics} onChange={(e) => setAnalytics(e.target.checked)} /> Analytics
        </label>
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="button" className={primaryButtonClass} onClick={() => set(true)}>
          {t('Accept all')}
        </button>
        <button type="button" className={secondaryButtonClass} onClick={() => set(analytics)}>
          {analytics ? 'Save choices' : t('Necessary only')}
        </button>
      </div>
    </section>
  )
}
