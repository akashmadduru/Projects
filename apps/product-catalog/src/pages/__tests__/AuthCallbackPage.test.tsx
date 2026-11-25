/**
 * AuthCallbackPage (TD-TEST-02): where the Google return lands the user. The auth store is set
 * directly; the stored redirect uses the real lib/redirect through sessionStorage.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigationType } from 'react-router-dom'
import { AuthCallbackPage } from '../AuthCallbackPage'
import { useAuthStore } from '../../stores'

const STORED = 'pc.post-login-redirect'

function Probe() {
  const loc = useLocation()
  const type = useNavigationType()
  return (
    <p data-testid="where" data-nav={type}>
      {loc.pathname}
      {loc.search}
    </p>
  )
}

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="*" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  )
}

const whereText = () => screen.getByTestId('where').textContent

beforeEach(() => {
  sessionStorage.clear()
  useAuthStore.setState({ status: 'unknown', mfaToken: null, user: null })
})

afterEach(() => {
  cleanup()
  sessionStorage.clear()
  useAuthStore.setState({ status: 'unknown', mfaToken: null, user: null })
})

describe('AuthCallbackPage', () => {
  it('sends ?error=<code> to the login page with the stored redirect, and consumes it', async () => {
    sessionStorage.setItem(STORED, '/orders?tab=open')
    useAuthStore.setState({ status: 'anonymous' })
    renderAt('/auth/callback?error=AUTH_OIDC_EMAIL_UNVERIFIED')

    expect(await screen.findByTestId('where')).toHaveTextContent(
      `/login?error=AUTH_OIDC_EMAIL_UNVERIFIED&next=${encodeURIComponent('/orders?tab=open')}`,
    )
    expect(sessionStorage.getItem(STORED)).toBeNull()
    expect(screen.getByTestId('where')).toHaveAttribute('data-nav', 'REPLACE')
  })

  it('encodes an error value that needs it and falls back to "/" without a stored redirect', async () => {
    renderAt('/auth/callback?error=' + encodeURIComponent('a b&c=d/é'))

    expect(await screen.findByTestId('where')).toHaveTextContent(
      `/login?error=${encodeURIComponent('a b&c=d/é')}&next=%2F`,
    )
    const search = new URLSearchParams(whereText()?.split('?')[1])
    expect(search.get('error')).toBe('a b&c=d/é')
    expect(search.get('next')).toBe('/')
  })

  it('error wins over an authenticated session', async () => {
    useAuthStore.setState({ status: 'authenticated' })
    renderAt('/auth/callback?error=AUTH_OIDC_LOGIN_FAILED')

    expect(await screen.findByTestId('where')).toHaveTextContent('/login?error=AUTH_OIDC_LOGIN_FAILED&next=%2F')
  })

  it.each(['//evil.example/x', 'https://evil.example', '/\evil.example', 'javascript:alert(1)'])(
    'never builds the next URL from hostile stored value %s',
    async (hostile) => {
      sessionStorage.setItem(STORED, hostile)
      renderAt('/auth/callback?error=AUTH_OIDC_LOGIN_FAILED')

      expect(await screen.findByTestId('where')).toHaveTextContent('/login?error=AUTH_OIDC_LOGIN_FAILED&next=%2F')
    },
  )

  it('ignores a ?next= query parameter on the callback URL', async () => {
    useAuthStore.setState({ status: 'authenticated' })
    renderAt('/auth/callback?next=' + encodeURIComponent('//evil.example'))

    expect(await screen.findByTestId('where')).toHaveTextContent('/')
    expect(whereText()).not.toContain('evil')
  })

  it('goes to the stored post-login path when authenticated, replacing the callback entry', async () => {
    sessionStorage.setItem(STORED, '/checkout')
    useAuthStore.setState({ status: 'authenticated' })
    renderAt('/auth/callback')

    expect(await screen.findByTestId('where')).toHaveTextContent('/checkout')
    expect(screen.getByTestId('where')).toHaveAttribute('data-nav', 'REPLACE')
    expect(sessionStorage.getItem(STORED)).toBeNull()
  })

  it('defaults to "/" when authenticated with nothing stored', async () => {
    useAuthStore.setState({ status: 'authenticated' })
    renderAt('/auth/callback')

    expect(await screen.findByTestId('where')).toHaveTextContent(/^\/$/)
  })

  it('sends an mfaToken to the login second-factor step, ahead of authenticated', async () => {
    sessionStorage.setItem(STORED, '/account/profile')
    useAuthStore.setState({ status: 'authenticated', mfaToken: 'mfa-123' })
    renderAt('/auth/callback')

    expect(await screen.findByTestId('where')).toHaveTextContent(
      `/login?next=${encodeURIComponent('/account/profile')}`,
    )
    expect(whereText()).not.toContain('error=')
    expect(sessionStorage.getItem(STORED)).toBeNull()
  })

  it('sends an anonymous visitor without an error to login with AUTH_OIDC_LOGIN_FAILED', async () => {
    sessionStorage.setItem(STORED, '/wishlist')
    useAuthStore.setState({ status: 'anonymous' })
    renderAt('/auth/callback')

    expect(await screen.findByTestId('where')).toHaveTextContent(
      `/login?error=AUTH_OIDC_LOGIN_FAILED&next=${encodeURIComponent('/wishlist')}`,
    )
    expect(sessionStorage.getItem(STORED)).toBeNull()
  })

  it('does not navigate while the session status is loading and shows a status message', () => {
    sessionStorage.setItem(STORED, '/checkout')
    useAuthStore.setState({ status: 'unknown' })
    renderAt('/auth/callback')

    expect(screen.getByRole('status')).toHaveTextContent('Signing you in...')
    expect(screen.queryByTestId('where')).not.toBeInTheDocument()
    expect(sessionStorage.getItem(STORED)).toBe('/checkout')
  })
})
