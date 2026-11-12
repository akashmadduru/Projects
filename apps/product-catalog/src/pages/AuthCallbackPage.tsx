/**
 * Landing page after Google sign-in. auth-service has already set the refresh cookie and redirected
 * here with nothing sensitive in the URL; the app-wide bootstrap (main.tsx) turns that cookie into
 * an access token. A failure arrives as ?error=<AUTH_* code>.
 */

import { useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { takePostLoginRedirect } from '../lib/redirect'
import { useAuthStore } from '../stores'

export function AuthCallbackPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const status = useAuthStore((s) => s.status)
  const error = params.get('error')

  useEffect(() => {
    if (error) {
      const next = takePostLoginRedirect()
      navigate(`/login?error=${encodeURIComponent(error)}&next=${encodeURIComponent(next)}`, { replace: true })
      return
    }
    if (status === 'authenticated') {
      navigate(takePostLoginRedirect(), { replace: true })
    } else if (status === 'anonymous') {
      // Google succeeded but no session could be restored (e.g. cookie blocked).
      navigate(`/login?error=AUTH_OIDC_LOGIN_FAILED&next=${encodeURIComponent(takePostLoginRedirect())}`, {
        replace: true,
      })
    }
  }, [error, status, navigate])

  return (
    <div className="mx-auto max-w-md py-16 text-center text-on-surface-muted" role="status" aria-live="polite">
      Signing you in...
    </div>
  )
}
