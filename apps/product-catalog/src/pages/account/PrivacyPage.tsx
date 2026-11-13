/** Privacy: data export (job + download), account deletion with a 7-day grace period, cookie choices. */

import { useEffect, useState } from 'react'
import { PageShell, fmtDate } from '../../components/account/PageShell'
import { ErrorAlert } from '../../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/Form'
import { useConsent } from '../../lib/consent'
import { errorMessage } from '../../lib/errors'
import { useT } from '../../lib/i18n'
import { privacyApi } from '../../services/trustApi'
import type { ExportJob } from '../../types'

const DELETE_KEY = 'deletion-scheduled-for'

function readScheduled(): string | null {
  try {
    return localStorage.getItem(DELETE_KEY)
  } catch {
    return null
  }
}

function ExportCard() {
  const [job, setJob] = useState<ExportJob | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  // Poll while the job is being prepared.
  useEffect(() => {
    if (!job || (job.status !== 'PENDING' && job.status !== 'RUNNING')) return
    const id = setInterval(() => {
      privacyApi.exportStatus(job.jobId).then(setJob).catch(() => undefined)
    }, 4000)
    return () => clearInterval(id)
  }, [job])

  const start = async () => {
    setBusy(true)
    setError(undefined)
    try {
      setJob(await privacyApi.requestExport())
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const download = async () => {
    if (!job) return
    try {
      const blob = await privacyApi.downloadExport(job)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'cartwise-data-export'
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  return (
    <section aria-labelledby="export-title" className={`${cardClass} space-y-3`}>
      <h3 id="export-title" className="font-display text-xl font-bold text-on-surface">Export my data</h3>
      <p className="text-sm text-on-surface">Get a copy of the personal data we hold about you. The file is kept for 24 hours and we email you when it is ready.</p>
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <div aria-live="polite" className="text-sm text-on-surface">
        {job?.status === 'PENDING' || job?.status === 'RUNNING' ? 'Preparing your export...' : null}
        {job?.status === 'FAILED' ? 'The export failed. Please try again.' : null}
        {job?.status === 'EXPIRED' ? 'The export expired. Request a new one.' : null}
      </div>
      {job?.status === 'READY' ? (
        <button type="button" className={primaryButtonClass} onClick={() => void download()}>
          Download export{job.expiresAt ? ` (until ${fmtDate(job.expiresAt)})` : ''}
        </button>
      ) : (
        <button type="button" className={secondaryButtonClass} disabled={busy || job?.status === 'PENDING' || job?.status === 'RUNNING'} onClick={() => void start()}>
          Request export
        </button>
      )}
    </section>
  )
}

function DeleteCard() {
  const [scheduled, setScheduled] = useState<string | null>(readScheduled)
  const [confirming, setConfirming] = useState(false)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const run = async (fn: () => Promise<void>) => {
    setBusy(true)
    setError(undefined)
    try {
      await fn()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="delete-title" className={`${cardClass} space-y-3`}>
      <h3 id="delete-title" className="font-display text-xl font-bold text-on-surface">Delete my account</h3>
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      {scheduled ? (
        <>
          <p className="text-sm text-on-surface">
            Your account is scheduled for deletion on <strong>{fmtDate(scheduled)}</strong>. Until then you can change your mind.
          </p>
          <button
            type="button"
            className={primaryButtonClass}
            disabled={busy}
            onClick={() =>
              void run(async () => {
                await privacyApi.cancelDeletion()
                try {
                  localStorage.removeItem(DELETE_KEY)
                } catch {
                  // Marker only; nothing to clean.
                }
                setScheduled(null)
              })
            }
          >
            Cancel deletion
          </button>
        </>
      ) : confirming ? (
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            void run(async () => {
              const res = await privacyApi.requestDeletion(password)
              const when = res.scheduledFor ?? new Date(Date.now() + 7 * 86400000).toISOString()
              try {
                localStorage.setItem(DELETE_KEY, when)
              } catch {
                // Marker only.
              }
              setScheduled(when)
              setConfirming(false)
              setPassword('')
            })
          }}
        >
          <p className="text-sm text-on-surface">
            Your account will be deleted after a 7-day grace period. Orders keep their financial records but your name and address are removed. You can&apos;t delete while orders are still open or refunds are pending.
          </p>
          <Field label="Confirm with your password">
            {(id) => <input id={id} type="password" className={inputClass} required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
          </Field>
          <div className="flex gap-2">
            <button type="submit" className={primaryButtonClass} disabled={busy || !password}>
              Schedule deletion
            </button>
            <button type="button" className={secondaryButtonClass} onClick={() => setConfirming(false)}>
              Keep my account
            </button>
          </div>
        </form>
      ) : (
        <button type="button" className={secondaryButtonClass} onClick={() => setConfirming(true)}>
          Delete my account
        </button>
      )}
    </section>
  )
}

function CookieCard() {
  const analytics = useConsent((s) => s.analytics)
  const set = useConsent((s) => s.set)
  return (
    <section aria-labelledby="cookie-title" className={`${cardClass} space-y-3`}>
      <h3 id="cookie-title" className="font-display text-xl font-bold text-on-surface">Cookie preferences</h3>
      <label className="flex items-center gap-2 text-sm text-on-surface">
        <input type="checkbox" checked={analytics} onChange={(e) => set(e.target.checked)} />
        Allow analytics (product view counts, synced recently viewed)
      </label>
    </section>
  )
}

export function PrivacyPage() {
  const t = useT()
  return (
    <PageShell id="privacy" title={t('Privacy')} intro="Control your data.">
      <ExportCard />
      <DeleteCard />
      <CookieCard />
    </PageShell>
  )
}
