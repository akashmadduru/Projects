/**
 * Route guard: waits for session restore, then renders the child routes or redirects to login
 */

import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { LoadingBlock } from './Form'
import { useAuthStore } from '../stores'

export function RequireAuth() {
  const status = useAuthStore((s) => s.status)
  const location = useLocation()

  if (status === 'unknown') return <LoadingBlock label="Checking your session" />
  if (status === 'anonymous') {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }
  return <Outlet />
}
