/**
 * Confirms an email address from the emailed link (?token=...)
 */

import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { LoadingBlock, cardClass } from '../components/Form'
import { errorMessage } from '../lib/errors'
import { authApi } from '../services/authApi'
import { useAuthStore } from '../stores'

type Result = { state: 'working' } | { state: 'ok' } | { state: 'failed'; message: string }

export function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const refreshUser = useAuthStore((s) => s.refreshUser)
  const [result, setResult] = useState<Result>(
    token ? { state: 'working' } : { state: 'failed', message: 'This verification link is missing its token.' },
  )

  useEffect(() => {
    if (!token) return
    let active = true
    authApi
      .verifyEmail(token)
      .then(() => {
        if (!active) return
        setResult({ state: 'ok' })
        void refreshUser()
      })
      .catch((err: unknown) => {
        if (active) setResult({ state: 'failed', message: errorMessage(err) })
      })
    return () => {
      active = false
    }
  }, [token, refreshUser])

  if (result.state === 'working') return <LoadingBlock label="Verifying your email" />

  return (
    <div className={`${cardClass} mx-auto max-w-md space-y-3`} role="status">
      <h1 className="font-display text-2xl font-bold text-on-surface">
        {result.state === 'ok' ? 'Email verified' : 'Could not verify email'}
      </h1>
      <p className="text-on-surface">{result.state === 'ok' ? 'Thanks, your email address is confirmed.' : result.message}</p>
      <Link to="/" className="font-semibold text-primary hover:underline">
        Continue shopping
      </Link>
    </div>
  )
}
