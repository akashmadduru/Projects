/**
 * "Login with OTP": phone number -> 6-digit code, with a 30 s resend timer.
 */

import { useState } from 'react'
import { ErrorAlert } from './ErrorAlert'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from './Form'
import { useCooldown } from '../hooks/useCooldown'
import { errorMessage } from '../lib/errors'
import { authApi } from '../services/authApi'
import { useAuthStore } from '../stores'

const RESEND_SECONDS = 30

export function OtpLoginForm({ onSuccess }: { onSuccess: () => void }) {
  const loginWithOtp = useAuthStore((s) => s.loginWithOtp)
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [codeSent, setCodeSent] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | undefined>()
  const { remaining, start } = useCooldown()

  const sendCode = async () => {
    setBusy(true)
    setError(undefined)
    try {
      await authApi.requestOtp(phone.trim())
      setCodeSent(true)
      start(RESEND_SECONDS)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const verify = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await loginWithOtp(phone.trim(), code.trim())
      onSuccess()
    } catch (err) {
      setError(errorMessage(err))
      setBusy(false)
    }
  }

  return (
    <form
      onSubmit={(e) => (codeSent ? void verify(e) : (e.preventDefault(), void sendCode()))}
      className="space-y-4"
    >
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      <Field label="Mobile number" hint="Include the country code, for example +919876543210. Only verified numbers can sign in.">
        {(id) => (
          <input
            id={id}
            type="tel"
            autoComplete="tel"
            required
            maxLength={16}
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            disabled={codeSent}
            className={inputClass}
          />
        )}
      </Field>
      {codeSent && (
        <>
          <Field label="6-digit code" hint="If a verified account has this number, a code is on its way.">
            {(id) => (
              <input
                id={id}
                inputMode="numeric"
                autoComplete="one-time-code"
                required
                pattern="[0-9]{6}"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                className={inputClass}
              />
            )}
          </Field>
          <div className="flex items-center justify-between gap-3">
            <button
              type="button"
              className={secondaryButtonClass}
              disabled={busy || remaining > 0}
              onClick={() => void sendCode()}
            >
              Resend code
            </button>
            <span aria-live="polite" className="text-sm text-on-surface-muted">
              {remaining > 0 ? `You can resend in ${remaining}s` : ''}
            </span>
          </div>
        </>
      )}
      <button type="submit" disabled={busy || !phone.trim() || (codeSent && code.length !== 6)} className={`${primaryButtonClass} w-full`}>
        {busy ? 'Please wait...' : codeSent ? 'Verify and sign in' : 'Send code'}
      </button>
    </form>
  )
}
