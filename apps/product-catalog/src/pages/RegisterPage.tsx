/**
 * Sign-up form. On success the account is created and the user is signed in.
 */

import { useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { ErrorAlert } from '../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass } from '../components/Form'
import { GoogleSignInButton, OrDivider } from '../components/GoogleSignInButton'
import { errorMessage } from '../lib/errors'
import { safeNext } from '../lib/redirect'
import { useAuthStore } from '../stores'

const MIN_PASSWORD = 12

export function RegisterPage() {
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const navigate = useNavigate()
  const status = useAuthStore((s) => s.status)
  const register = useAuthStore((s) => s.register)
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | undefined>()

  if (status === 'authenticated' && !submitting) return <Navigate to={next} replace />

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (password.length < MIN_PASSWORD) {
      setError(`Password must be at least ${MIN_PASSWORD} characters.`)
      return
    }
    setSubmitting(true)
    setError(undefined)
    try {
      await register(email.trim(), password, displayName.trim())
      navigate(next, { replace: true })
    } catch (err) {
      setError(errorMessage(err))
      setSubmitting(false)
    }
  }

  return (
    <div className="mx-auto max-w-md space-y-5">
      <h1 className="font-display text-2xl font-bold text-on-surface">Create your account</h1>
      <form onSubmit={(e) => void onSubmit(e)} className={`${cardClass} space-y-4`}>
        {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
        <GoogleSignInButton next={next} label="Sign up with Google" />
        <OrDivider />
        <Field label="Name">
          {(id) => (
            <input
              id={id}
              type="text"
              autoComplete="name"
              required
              maxLength={200}
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              className={inputClass}
            />
          )}
        </Field>
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
        <Field label="Password" hint={`At least ${MIN_PASSWORD} characters.`}>
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
        <button type="submit" disabled={submitting} className={`${primaryButtonClass} w-full`}>
          {submitting ? 'Creating account...' : 'Create account'}
        </button>
      </form>
      <p className="text-center text-sm text-on-surface-muted">
        Already have an account?{' '}
        <Link to={`/login?next=${encodeURIComponent(next)}`} className="font-semibold text-primary hover:underline">
          Sign in
        </Link>
      </p>
    </div>
  )
}
