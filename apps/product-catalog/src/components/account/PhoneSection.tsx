/**
 * Add and verify a mobile number (E.164). Verified numbers can be used for OTP sign-in.
 */

import { useState } from 'react'
import { ErrorAlert } from '../ErrorAlert'
import { Field, inputClass, primaryButtonClass, secondaryButtonClass } from '../Form'
import { useCooldown } from '../../hooks/useCooldown'
import { errorMessage } from '../../lib/errors'
import { authApi } from '../../services/authApi'
import { useAuthStore, useUiStore } from '../../stores'

const RESEND_SECONDS = 30

export function PhoneSection() {
  const user = useAuthStore((s) => s.user)
  const refreshUser = useAuthStore((s) => s.refreshUser)
  const notify = useUiStore((s) => s.notify)
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
      await authApi.addPhone(phone.trim())
      setCodeSent(true)
      start(RESEND_SECONDS)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const verify = async () => {
    setBusy(true)
    setError(undefined)
    try {
      await authApi.verifyPhone(code.trim())
      notify('Phone number verified')
      setCodeSent(false)
      setCode('')
      setPhone('')
      await refreshUser()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  const verifiedPhone = user?.phone && user.phoneVerified ? user.phone : null

  return (
    <div className="space-y-4">
      {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
      {verifiedPhone && (
        <p className="text-sm text-on-surface">
          Verified number: <span className="font-bold">{verifiedPhone}</span>. You can use it to sign in with a one-time code.
        </p>
      )}
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          void (codeSent ? verify() : sendCode())
        }}
      >
        <Field
          label={verifiedPhone ? 'Change mobile number' : 'Mobile number'}
          hint="Include the country code, for example +919876543210."
        >
          {(id) => (
            <input
              id={id}
              type="tel"
              autoComplete="tel"
              maxLength={16}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              disabled={codeSent}
              className={inputClass}
            />
          )}
        </Field>
        {codeSent && (
          <Field label="6-digit code">
            {(id) => (
              <input
                id={id}
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                className={inputClass}
              />
            )}
          </Field>
        )}
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="submit"
            disabled={busy || !phone.trim() || (codeSent && code.length !== 6)}
            className={primaryButtonClass}
          >
            {busy ? 'Please wait...' : codeSent ? 'Verify number' : 'Send code'}
          </button>
          {codeSent && (
            <button
              type="button"
              className={secondaryButtonClass}
              disabled={busy || remaining > 0}
              onClick={() => void sendCode()}
            >
              Resend code
            </button>
          )}
          <span aria-live="polite" className="text-sm text-on-surface-muted">
            {codeSent && remaining > 0 ? `You can resend in ${remaining}s` : ''}
          </span>
        </div>
      </form>
    </div>
  )
}
