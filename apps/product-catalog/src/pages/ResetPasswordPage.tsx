/**
 * Choose a new password from the emailed link (?token=...)
 */

import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ErrorAlert } from '../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass } from '../components/Form'
import { errorMessage } from '../lib/errors'
import { authApi } from '../services/authApi'

const MIN_PASSWORD = 12

export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | undefined>()

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (password !== confirm) {
      setError('The two passwords do not match.')
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      await authApi.resetPassword(token, password)
      setDone(true)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (!token) {
    return (
      <div className={`${cardClass} mx-auto max-w-md space-y-3`}>
        <p className="text-on-surface">This reset link is missing its token.</p>
        <Link to="/forgot-password" className="font-semibold text-primary hover:underline">
          Request a new link
        </Link>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-md space-y-5">
      <h1 className="font-display text-2xl font-bold text-on-surface">Set a new password</h1>
      {done ? (
        <div className={`${cardClass} space-y-3`} role="status">
          <p className="text-on-surface">Your password was changed. You have been signed out everywhere.</p>
          <Link to="/login" className={`${primaryButtonClass} w-full`}>
            Sign in
          </Link>
        </div>
      ) : (
        <form onSubmit={(e) => void submit(e)} className={`${cardClass} space-y-4`}>
          {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
          <Field label="New password" hint={`At least ${MIN_PASSWORD} characters.`}>
            {(id) => (
              <input
                id={id}
                type="password"
                autoComplete="new-password"
                required
                minLength={MIN_PASSWORD}
                maxLength={200}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={inputClass}
              />
            )}
          </Field>
          <Field label="Confirm new password">
            {(id) => (
              <input
                id={id}
                type="password"
                autoComplete="new-password"
                required
                minLength={MIN_PASSWORD}
                maxLength={200}
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                className={inputClass}
              />
            )}
          </Field>
          <button type="submit" disabled={busy} className={`${primaryButtonClass} w-full`}>
            {busy ? 'Saving...' : 'Change password'}
          </button>
        </form>
      )}
    </div>
  )
}
