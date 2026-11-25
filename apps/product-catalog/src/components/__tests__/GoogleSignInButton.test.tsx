/**
 * GoogleSignInButton and OrDivider (TD-TEST-02). The enabled flag is read from
 * import.meta.env when the module loads, so each test stubs the env, resets the module graph and
 * imports the component afresh.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'

const redirect = vi.hoisted(() => ({
  rememberPostLoginRedirect: vi.fn(),
  takePostLoginRedirect: vi.fn(),
  safeNext: vi.fn(),
}))
vi.mock('../../lib/redirect', () => redirect)

const originalLocation = Object.getOwnPropertyDescriptor(window, 'location')
const assign = vi.fn()

async function load(flag?: string) {
  // undefined means genuinely unset (nothing defines it in the test env), not the string 'undefined'
  if (flag !== undefined) vi.stubEnv('VITE_GOOGLE_SSO_ENABLED', flag)
  vi.resetModules()
  return import('../GoogleSignInButton')
}

beforeEach(() => {
  assign.mockReset()
  redirect.rememberPostLoginRedirect.mockReset()
  vi.stubEnv('VITE_AUTH_BASE_URL', 'http://auth.test:9000/')
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { origin: 'http://shop.test', assign },
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllEnvs()
  vi.resetModules()
  if (originalLocation) Object.defineProperty(window, 'location', originalLocation)
})

describe('GoogleSignInButton', () => {
  it('remembers the next path and sends the browser to the Google sign-in URL', async () => {
    const { GoogleSignInButton } = await load()
    render(<GoogleSignInButton next="/checkout?x=1" />)

    fireEvent.click(screen.getByRole('button', { name: 'Continue with Google' }))

    expect(redirect.rememberPostLoginRedirect).toHaveBeenCalledTimes(1)
    expect(redirect.rememberPostLoginRedirect).toHaveBeenCalledWith('/checkout?x=1')
    expect(assign).toHaveBeenCalledTimes(1)
    expect(assign).toHaveBeenCalledWith(
      `http://auth.test:9000/oauth2/authorization/google?redirect_uri=${encodeURIComponent('http://shop.test/auth/callback')}`,
    )
    // remember happens before leaving the app
    expect(redirect.rememberPostLoginRedirect.mock.invocationCallOrder[0]).toBeLessThan(
      assign.mock.invocationCallOrder[0] ?? 0,
    )
  })

  it('renders a custom label instead of the default', async () => {
    const { GoogleSignInButton } = await load()
    render(<GoogleSignInButton next="/" label="Sign up with Google" />)

    expect(screen.getByRole('button', { name: 'Sign up with Google' })).toBeInTheDocument()
    expect(screen.queryByText('Continue with Google')).not.toBeInTheDocument()
  })

  it('renders nothing when VITE_GOOGLE_SSO_ENABLED is "false"', async () => {
    const { GoogleSignInButton } = await load('false')
    const { container } = render(<GoogleSignInButton next="/" />)

    expect(container).toBeEmptyDOMElement()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it.each([undefined, 'true', '', 'FALSE', '0'])('renders when VITE_GOOGLE_SSO_ENABLED is %j', async (flag) => {
    const { GoogleSignInButton } = await load(flag)
    render(<GoogleSignInButton next="/" />)

    expect(screen.getByRole('button', { name: 'Continue with Google' })).toBeInTheDocument()
  })
})

describe('OrDivider', () => {
  it('renders nothing when VITE_GOOGLE_SSO_ENABLED is "false"', async () => {
    const { OrDivider } = await load('false')
    const { container } = render(<OrDivider />)

    expect(container).toBeEmptyDOMElement()
  })

  it.each([undefined, 'true', 'anything'])('renders the "or" divider when the flag is %j', async (flag) => {
    const { OrDivider } = await load(flag)
    render(<OrDivider />)

    expect(screen.getByText('or')).toBeInTheDocument()
  })
})
