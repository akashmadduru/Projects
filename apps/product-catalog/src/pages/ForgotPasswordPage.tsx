/**
 * Request a password reset link. Always shows the same confirmation (no account enumeration).
 */

import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ErrorAlert } from '../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass } from '../components/Form'
import { errorMessage } from '../lib/errors'
import { authApi } from '../services/authApi'

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await authApi.forgotPassword(email.trim())
      setSent(true)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto max-w-md space-y-5">
      <h1 className="font-display text-2xl font-bold text-on-surface">Forgot your password?</h1>
      {sent ? (
        <div className={`${cardClass} space-y-3`} role="status">
          <p className="text-on-surface">
            If an account exists for <span className="font-semibold">{email.trim()}</span>, we have emailed a link to reset the
            password. It is valid for 30 minutes.
          </p>
          <Link to="/login" className="font-semibold text-primary hover:underline">
            Back to sign in
          </Link>
        </div>
      ) : (
        <form onSubmit={(e) => void submit(e)} className={`${cardClass} space-y-4`}>
          {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
          <Field label="Email">
            {(id) => (
              <input
                id={id}
                type="email"
                autoComplete="email"
                required
                maxLength={320}
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className={inputClass}
              />
            )}
          </Field>
          <button type="submit" disabled={busy} className={`${primaryButtonClass} w-full`}>
            {busy ? 'Sending...' : 'Send reset link'}
          </button>
        </form>
      )}
    </div>
  )
}
