/** Account security: two-factor authentication (QR setup, recovery codes) and active sessions. */

import { useCallback, useState } from 'react'
import { PageShell, fmtDate } from '../../components/account/PageShell'
import { ErrorAlert } from '../../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass, secondaryButtonClass } from '../../components/Form'
import { QrCode } from '../../components/QrCode'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage } from '../../lib/errors'
import { useT } from '../../lib/i18n'
import { authApi } from '../../services/authApi'
import { useAuthStore } from '../../stores'
import type { TwoFactorSetup } from '../../types'

function downloadCodes(codes: string[]) {
  const blob = new Blob([`Cartwise recovery codes\n\n${codes.join('\n')}\n`], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'cartwise-recovery-codes.txt'
  a.click()
  URL.revokeObjectURL(url)
}

function TwoFactorCard() {
  const user = useAuthStore((s) => s.user)
  const refreshUser = useAuthStore((s) => s.refreshUser)
  const [setup, setSetup] = useState<TwoFactorSetup | null>(null)
  const [code, setCode] = useState('')
  const [codes, setCodes] = useState<string[] | null>(null)
  const [disabling, setDisabling] = useState(false)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const enabled = !!user?.twoFactorEnabled

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
    <section aria-labelledby="tfa-title" className={`${cardClass} space-y-4`}>
      <h3 id="tfa-title" className="font-display text-xl font-bold text-on-surface">
        Two-factor authentication
      </h3>
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}

      {codes ? (
        <div className="space-y-3">
          <p className="font-semibold text-success">Two-factor authentication is on.</p>
          <p className="text-sm text-on-surface">Save these recovery codes now. Each works once and they will not be shown again.</p>
          <ul className="grid grid-cols-2 gap-1 font-mono text-sm text-on-surface">
            {codes.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
          <div className="flex gap-2">
            <button type="button" className={secondaryButtonClass} onClick={() => downloadCodes(codes)}>
              Download codes
            </button>
            <button type="button" className={primaryButtonClass} onClick={() => setCodes(null)}>
              I have saved them
            </button>
          </div>
        </div>
      ) : enabled ? (
        <div className="space-y-3">
          <p className="text-sm text-on-surface">Two-factor authentication is on for your account.</p>
          {disabling ? (
            <form
              className="space-y-3"
              onSubmit={(e) => {
                e.preventDefault()
                void run(async () => {
                  await authApi.twoFactorDisable(
                    code.length > 6 || !/^\d+$/.test(code) ? { recoveryCode: code.trim(), password } : { code: code.trim(), password },
                  )
                  setDisabling(false)
                  setCode('')
                  setPassword('')
                  await refreshUser()
                })
              }}
            >
              <Field label="Authenticator or recovery code">
                {(id) => <input id={id} className={inputClass} required autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />}
              </Field>
              <Field label="Password">
                {(id) => <input id={id} type="password" className={inputClass} required autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />}
              </Field>
              <div className="flex gap-2">
                <button type="submit" className={primaryButtonClass} disabled={busy}>
                  Turn off 2FA
                </button>
                <button type="button" className={secondaryButtonClass} onClick={() => setDisabling(false)}>
                  Cancel
                </button>
              </div>
            </form>
          ) : (
            <button type="button" className={secondaryButtonClass} onClick={() => setDisabling(true)}>
              Turn off 2FA
            </button>
          )}
        </div>
      ) : setup ? (
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            void run(async () => {
              const res = await authApi.twoFactorEnable(code.trim())
              setCodes(res.recoveryCodes)
              setSetup(null)
              setCode('')
              await refreshUser()
            })
          }}
        >
          <p className="text-sm text-on-surface">Scan this QR code with an authenticator app, then enter the 6-digit code it shows.</p>
          <QrCode value={setup.otpauthUrl} label="Authenticator setup QR code" />
          <p className="text-sm text-on-surface-muted">
            Can&apos;t scan? Enter this key manually: <code className="font-mono text-on-surface">{setup.secretBase32}</code>
          </p>
          <Field label="6-digit code">
            {(id) => <input id={id} className={inputClass} inputMode="numeric" autoComplete="one-time-code" required maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />}
          </Field>
          <div className="flex gap-2">
            <button type="submit" className={primaryButtonClass} disabled={busy || code.length !== 6}>
              Turn on 2FA
            </button>
            <button type="button" className={secondaryButtonClass} onClick={() => setSetup(null)}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-on-surface">Add a second step to sign-in using an authenticator app.</p>
          <button type="button" className={primaryButtonClass} disabled={busy} onClick={() => void run(async () => setSetup(await authApi.twoFactorSetup()))}>
            Set up 2FA
          </button>
        </div>
      )}
    </section>
  )
}

function SessionsCard() {
  const t = useT()
  const load = useCallback(() => authApi.sessions(), [])
  const { data, loading, error, reload } = useLoad(load)
  const [actionError, setActionError] = useState<string | undefined>()

  const act = (fn: () => Promise<void>) => {
    setActionError(undefined)
    fn().then(reload).catch((err: unknown) => setActionError(errorMessage(err)))
  }

  return (
    <section aria-labelledby="sessions-title" className={`${cardClass} space-y-3`}>
      <h3 id="sessions-title" className="font-display text-xl font-bold text-on-surface">
        {t('Active sessions')}
      </h3>
      {actionError && <ErrorAlert message={actionError} onDismiss={() => setActionError(undefined)} />}
      {loading && !data ? (
        <p role="status" className="text-on-surface-muted">Loading sessions...</p>
      ) : error ? (
        <ErrorAlert message={errorMessage(error)} />
      ) : (
        <>
          <ul className="divide-y divide-outline">
            {data?.map((s) => (
              <li key={s.familyId} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                <span className="text-on-surface">
                  <span className="font-bold">{s.device}</span>
                  {s.current && <span className="ml-2 rounded-full border-2 border-ink bg-primary-container px-2 text-xs font-bold text-on-primary-container">This device</span>}
                  <span className="block text-on-surface-muted">Last active {fmtDate(s.lastUsedAt)}</span>
                </span>
                {!s.current && (
                  <button type="button" className={secondaryButtonClass} onClick={() => act(() => authApi.revokeSession(s.familyId))}>
                    Sign out
                  </button>
                )}
              </li>
            ))}
          </ul>
          {data && data.some((s) => !s.current) && (
            <button type="button" className={secondaryButtonClass} onClick={() => act(() => authApi.revokeOtherSessions())}>
              Sign out all other devices
            </button>
          )}
        </>
      )}
    </section>
  )
}

export function SecurityPage() {
  const t = useT()
  return (
    <PageShell id="security" title={t('Security')} intro="Protect your account with two-step verification and review where you are signed in.">
      <TwoFactorCard />
      <SessionsCard />
    </PageShell>
  )
}
