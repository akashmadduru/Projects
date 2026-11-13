/** Second sign-in step: 6-digit authenticator code or a one-time recovery code. */

import { useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { Field, inputClass, primaryButtonClass } from './Form'
import { errorMessage } from '../lib/errors'
import { useAuthStore } from '../stores'

export function MfaStep({ onSuccess }: { onSuccess: () => void }) {
  const verifyMfa = useAuthStore((s) => s.verifyMfa)
  const cancelMfa = useAuthStore((s) => s.cancelMfa)
  const [recovery, setRecovery] = useState(false)
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await verifyMfa(recovery ? { recoveryCode: value.trim() } : { code: value.trim() })
      onSuccess()
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <form onSubmit={(e) => void submit(e)} className="space-y-4" aria-labelledby="mfa-title">
      <h2 id="mfa-title" className="font-display text-xl font-bold text-on-surface">
        Two-step verification
      </h2>
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <Field
        label={recovery ? 'Recovery code' : 'Authenticator code'}
        hint={recovery ? 'Each recovery code works once.' : 'Enter the 6-digit code from your authenticator app.'}
      >
        {(id) => (
          <input
            id={id}
            inputMode={recovery ? 'text' : 'numeric'}
            autoComplete="one-time-code"
            required
            maxLength={recovery ? 32 : 6}
            value={value}
            onChange={(e) => setValue(recovery ? e.target.value : e.target.value.replace(/\D/g, ''))}
            className={inputClass}
          />
        )}
      </Field>
      <button type="submit" disabled={busy || !value.trim()} className={`${primaryButtonClass} w-full`}>
        {busy ? 'Verifying...' : 'Verify and sign in'}
      </button>
      <div className="flex justify-between text-sm">
        <button
          type="button"
          className="font-semibold text-primary hover:underline"
          onClick={() => {
            setRecovery(!recovery)
            setValue('')
          }}
        >
          {recovery ? 'Use authenticator code' : 'Use a recovery code'}
        </button>
        <button type="button" className="font-semibold text-on-surface-muted hover:underline" onClick={cancelMfa}>
          Cancel
        </button>
      </div>
    </form>
  )
}
