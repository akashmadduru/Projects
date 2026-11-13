/**
 * Sign-in form. Honors ?next= (same-origin paths only) after success.
 */

import { useState } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom'
import { ErrorAlert } from '../components/ErrorAlert'
import { Field, cardClass, inputClass, primaryButtonClass } from '../components/Form'
import { GoogleSignInButton, OrDivider } from '../components/GoogleSignInButton'
import { MfaStep } from '../components/MfaStep'
import { OtpLoginForm } from '../components/OtpLoginForm'
import { useT } from '../lib/i18n'
import { codeMessage, errorMessage } from '../lib/errors'
import { safeNext } from '../lib/redirect'
import { useAuthStore } from '../stores'

export function LoginPage() {
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))
  const navigate = useNavigate()
  const status = useAuthStore((s) => s.status)
  const login = useAuthStore((s) => s.login)
  const mfaToken = useAuthStore((s) => s.mfaToken)
  const t = useT()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [tab, setTab] = useState<'password' | 'otp'>('password')
  const redirectError = params.get('error')
  const [error, setError] = useState<string | undefined>(redirectError ? codeMessage(redirectError) : undefined)

  if (status === 'authenticated' && !submitting) return <Navigate to={next} replace />

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(undefined)
    try {
      const result = await login(email.trim(), password)
      if (result === 'ok') navigate(next, { replace: true })
      else setSubmitting(false)
    } catch (err) {
      setError(errorMessage(err))
      setSubmitting(false)
    }
  }

  if (mfaToken) {
    return (
      <div className="mx-auto max-w-md space-y-5">
        <div className={cardClass}>
          <MfaStep onSuccess={() => navigate(next, { replace: true })} />
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-md space-y-5">
      <h1 className="font-display text-2xl font-bold text-on-surface">{t('Sign in')}</h1>
      <div role="tablist" aria-label="Sign-in method" className="flex gap-2">
        {([
          ['password', t('Password')],
          ['otp', t('Login with OTP')],
        ] as const).map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            onClick={() => setTab(key)}
            className={`press rounded-full border-2 border-ink px-4 py-1.5 text-sm font-bold ${
              tab === key ? 'bg-primary-container text-on-primary-container shadow-cta' : 'bg-surface text-on-surface'
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === 'otp' ? (
        <div className={`${cardClass} space-y-4`} role="tabpanel">
          <OtpLoginForm onSuccess={() => navigate(next, { replace: true })} />
        </div>
      ) : (
      <form onSubmit={(e) => void onSubmit(e)} className={`${cardClass} space-y-4`} role="tabpanel">
        {error && <ErrorAlert message={error} onDismiss={() => setError(undefined)} />}
        <GoogleSignInButton next={next} />
        <OrDivider />
        <Field label={t('Email')}>
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
        <Field label={t('Password')}>
          {(id) => (
            <input
              id={id}
              type="password"
              autoComplete="current-password"
              required
              maxLength={200}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={inputClass}
            />
          )}
        </Field>
        <div className="text-right">
          <Link to="/forgot-password" className="text-sm font-semibold text-primary hover:underline">
            {t('Forgot password?')}
          </Link>
        </div>
        <button type="submit" disabled={submitting} className={`${primaryButtonClass} w-full`}>
          {submitting ? t('Signing in...') : t('Sign in')}
        </button>
      </form>
      )}
      <p className="text-center text-sm text-on-surface-muted">
        {t('New here?')}{' '}
        <Link to={`/register?next=${encodeURIComponent(next)}`} className="font-semibold text-primary hover:underline">
          {t('Create an account')}
        </Link>
      </p>
    </div>
  )
}
